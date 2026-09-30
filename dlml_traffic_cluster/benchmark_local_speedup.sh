#!/bin/bash
# Measures speedup, efficiency and load distribution of the traffic analyzer:
#   - Sequential (TrafficLocalSeq)        -> baseline (optional)
#   - O-DLML local (TrafficDLMLLocal)    -> with np = 1, 2, 4, 8, 16 threads
#                                           and strategies: auction, round_robin, work_stealing
#
# Usage:
#   ./benchmark_local_speedup.sh            # default configuration
#   NPS="1 2 4" REPS=3 ./benchmark_local_speedup.sh
#
# Configurable environment variables:
#   NPS         thread counts to evaluate             (default: "1 2 4 8 16")
#   STRATEGIES  load-balancing strategies to evaluate (default: "auction round_robin work_stealing")
#   REPS        repetitions per configuration        (default: 5)
#   REPSS       sequential baseline repetitions       (default: 1)
#   RUN_SEQ     run the sequential baseline (true/false)  (default: true)
#   OUT         speedup output CSV file               (default: speedup_results.csv)
#   OUT_DIST    load-distribution CSV file            (default: load_distribution.csv)

set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
JSON_JAR="../ODLML/lib/json-20180813.jar"
NPS=${NPS:-"2 4 8 16"}
STRATEGIES=${STRATEGIES:-"auction round_robin work_stealing"}
REPS=${REPS:-2}
REPSS=${REPSS:-1}
RUN_SEQ=${RUN_SEQ:-false}
OUT=${OUT:-"speedup_results.csv"}
OUT_DIST=${OUT_DIST:-"load_distribution.csv"}
CP="$DLML_JAR:$JSON_JAR:."

# ---------- compilation ----------
echo ">> Compiling TrafficLocalSeq, TrafficDLMLLocal and support classes..."
javac -cp "$DLML_JAR:$JSON_JAR" \
    Data.java AlertAgg.java CellAgg.java ClusterAgg.java \
    TrafficDLMLLocal.java TrafficLocalSeq.java
echo ""

# ---------- CSV headers ----------
echo "version,strategy,np,rep,time_s,speedup,efficiency" > "$OUT"
echo "strategy,np,rep,phase,thread,items" > "$OUT_DIST"

PHYSICAL_CORES=$(lscpu 2>/dev/null \
    | awk -F: '/Core\(s\) per socket/ {gsub(/ /,"",$2); c=$2}
               /Socket\(s\)/          {gsub(/ /,"",$2); s=$2}
               END {if (c+0>0 && s+0>0) print c*s; else print ""}')
[ -z "$PHYSICAL_CORES" ] && PHYSICAL_CORES=$(nproc 2>/dev/null)
echo "Physical cores detected: ${PHYSICAL_CORES:-?}"
echo ""

# ============================================================
TSEQ=""
# ---- Baseline: TrafficLocalSeq (sequential) ----
if [ "$RUN_SEQ" = "true" ] || [ "$RUN_SEQ" = "1" ]; then
    echo "===================================="
    echo "Baseline: TrafficLocalSeq (sequential)"
    echo "===================================="
    SEQ_TIMES=()
    for r in $(seq 1 "$REPSS"); do
        printf "  rep %d/%d... " "$r" "$REPSS"
        t=$(java -cp "$CP" TrafficLocalSeq 2>/dev/null \
            | grep "Total time:" | awk '{print $(NF-1)}' | tail -1)
        SEQ_TIMES+=("$t")
        echo "${t} s"
        echo "sequential,-,1,$r,$t,1.0000,1.0000" >> "$OUT"
    done
    TSEQ=$(printf "%s\n" "${SEQ_TIMES[@]}" \
        | awk '{s+=$1} END {printf "%.3f", s/NR}')
    echo "Sequential average: ${TSEQ} s"
    echo ""
else
    echo "(Sequential baseline skipped: RUN_SEQ=$RUN_SEQ)"
    echo ""
fi

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
                         -cp "$CP" TrafficDLMLLocal "$np" 2>/dev/null)
            t=$(echo "$OUTPUT" | grep "Total time:" | awk '{print $(NF-1)}' | tail -1)
            if [ -n "$TSEQ" ]; then
                speedup=$(awk -v ts="$TSEQ" -v tp="$t" 'BEGIN {printf "%.4f", ts/tp}')
                eff=$(awk -v s="$speedup" -v p="$np" 'BEGIN {printf "%.4f", s/p}')
            else
                speedup="-"
                eff="-"
            fi
            echo "${t} s  speedup=${speedup}  efficiency=${eff}"
            echo "odlml,$strategy,$np,$r,$t,$speedup,$eff" >> "$OUT"

            # Load distribution: "items_phase_N: X Y Z ..."
            for phase in 1 2 4; do
                 grep "items_fase_${fase}:")|items_line=$(echo "$OUTPUT" | grep "items_phase_${phase}:")| grep "items_fase_${fase}:")
                if [ -n "$items_line" ]; then
                    thread=0
                    for cnt in $(echo "$items_line" | awk '{for(i=2;i<=NF;i++) print $i}'); do
                        echo "$strategy,$np,$r,$phase,$thread,$cnt" >> "$OUT_DIST"
                        thread=$((thread + 1))
                    done
                fi
            done
        done
    done
    echo ""
done
# ============================================================

echo "Results saved to:           $OUT"
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
           "version","strategy","np","time_s","speedup","efficiency"
    for (k in cnt) {
        n=cnt[k]
        printf "%-12s %-14s %4s %10.3f %8.4f %10.4f\n",
               ver[k], strat[k], np[k], sum_t[k]/n, sum_s[k]/n, sum_e[k]/n
    }
}' "$OUT" | sort -k1,1 -k2,2 -k3,3n
