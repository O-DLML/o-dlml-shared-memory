#!/bin/bash
# Measures speedup of the car image classifier by comparing:
#   - Sequential (ApplicationLocalSeq)      -> baseline
#   - O-DLML local (ApplicationLocal)      -> np = 1, 2, 4, 8, 16 threads
#                                             and strategies: auction, round_robin, work_stealing
#
# It also records how many batches each thread processed in each run,
# so the load distribution can be plotted per strategy.
#
# Usage:
#   ./benchmark_local_speedup.sh            # default configuration
#   NPS="1 2 4" REPS=3 ./benchmark_local_speedup.sh
#
# Configurable environment variables:
#   NPS         thread counts to evaluate             (default: "1 2 4 8 16")
#   STRATEGIES  load-balancing strategies             (default: "auction round_robin work_stealing")
#   REPS        repetitions per configuration        (default: 3)
#   REPSS       sequential baseline repetitions       (default: 1)
#   OUT         speedup CSV                           (default: speedup_results.csv)
#   OUT_DIST    load-distribution CSV                 (default: load_distribution.csv)

set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
NPS=${NPS:-"1 2 4 8"}
STRATEGIES=${STRATEGIES:-"auction round_robin work_stealing"}
REPS=${REPS:-2}
REPSS=${REPSS:-1}
TAM_LOTE=${TAM_LOTE:-10}
OUT=${OUT:-"speedup_results.csv"}
OUT_DIST=${OUT_DIST:-"load_distribution.csv"}
CP="$DLML_JAR:."

# ---------- compilation ----------
echo ">> Compiling ApplicationLocalSeq, ApplicationLocal and Data..."
javac -cp "$DLML_JAR" Data.java ApplicationLocalSeq.java ApplicationLocal.java
echo ""

# ---------- CSV headers ----------
echo "version,strategy,np,rep,time_ms,speedup,efficiency" > "$OUT"
echo "strategy,np,rep,thread,batches" > "$OUT_DIST"

PHYSICAL_CORES=$(lscpu 2>/dev/null \
    | awk -F: '/Core\(s\) per socket/ {gsub(/ /,"",$2); c=$2}
               /Socket\(s\)/          {gsub(/ /,"",$2); s=$2}
               END {if (c+0>0 && s+0>0) print c*s; else print ""}')
[ -z "$PHYSICAL_CORES" ] && PHYSICAL_CORES=$(nproc 2>/dev/null)
echo "Physical cores detected: ${PHYSICAL_CORES:-?}"
echo "Batch size: ${TAM_LOTE}"
echo ""

# ============================================================
# ---- Baseline: ApplicationLocalSeq (sequential) ----
echo "===================================="
echo "Baseline: ApplicationLocalSeq (sequential)"
echo "===================================="
SEQ_TIMES=()
for r in $(seq 1 "$REPSS"); do
    printf "  rep %d/%d... " "$r" "$REPSS"
    t=$(java -cp "$CP" ApplicationLocalSeq "$TAM_LOTE" 2>/dev/null \
        | grep "Total time:" | awk '{print $3}')
    SEQ_TIMES+=("$t")
    echo "${t} ms"
    echo "sequential,-,1,$r,$t,1.0000,1.0000" >> "$OUT"
done
TSEQ=$(printf "%s\n" "${SEQ_TIMES[@]}" \
    | awk '{s+=$1} END {printf "%.2f", s/NR}')
echo "Sequential average: ${TSEQ} ms"
echo ""

# ---- O-DLML local ----
for strategy in $STRATEGIES; do
    echo "===================================="
    echo "Strategy: $strategy"
    echo "===================================="

    for np in $NPS; do
        if [ -n "$PHYSICAL_CORES" ] && [ "$np" -gt "$PHYSICAL_CORES" ]; then
            echo "  (warning: np=$np exceeds $PHYSICAL_CORES physical cores; the CPUs will be oversubscribed)"
        fi
        for r in $(seq 1 "$REPS"); do
            printf "  np=%-2d  rep %d/%d... " "$np" "$r" "$REPS"
            OUTPUT=$(java -Djava.util.logging.config.file=logging.properties \
                         -Dodlml.strategy="$strategy" \
                         -cp "$CP" ApplicationLocal "$np" "$TAM_LOTE" 2>/dev/null)
            t=$(echo "$OUTPUT" | grep "Total time:" | awk '{print $3}')
            speedup=$(awk -v ts="$TSEQ" -v tp="$t" \
                'BEGIN {printf "%.4f", ts/tp}')
            eff=$(awk -v s="$speedup" -v p="$np" \
                'BEGIN {printf "%.4f", s/p}')
            echo "${t} ms  speedup=${speedup}  efficiency=${eff}"
            echo "odlml,$strategy,$np,$r,$t,$speedup,$eff" >> "$OUT"

            # Load distribution: "batches_per_thread: X Y Z ..."
            lotes_line=$(echo "$OUTPUT" | grep "batches_per_thread:")
            if [ -n "$lotes_line" ]; then
                hilo=0
                for cnt in $(echo "$lotes_line" | awk '{for(i=2;i<=NF;i++) print $i}'); do
                    echo "$strategy,$np,$r,$hilo,$cnt" >> "$OUT_DIST"
                    hilo=$((hilo + 1))
                done
            fi
        done
    done
    echo ""
done
# ============================================================

echo "Speedup saved to:           $OUT"
echo "Load distribution saved to: $OUT_DIST"
echo ""
echo "---- Summary (averages per version/strategy/np) ----"
awk -F',' 'NR>1 {
    key=$1 SUBSEP $2 SUBSEP $3
    sum_t[key]+=$5; sum_s[key]+=$6; sum_e[key]+=$7; cnt[key]++
    ver[key]=$1; strat[key]=$2; np[key]=$3
}
END {
    printf "%-12s %-14s %4s %10s %8s %10s\n",
           "version","strategy","np","time_ms","speedup","efficiency"
    for (k in cnt) {
        n=cnt[k]
        printf "%-12s %-14s %4s %10.1f %8.4f %10.4f\n",
               ver[k], strat[k], np[k], sum_t[k]/n, sum_s[k]/n, sum_e[k]/n
    }
}' "$OUT" | sort -k1,1 -k2,2 -k3,3n
