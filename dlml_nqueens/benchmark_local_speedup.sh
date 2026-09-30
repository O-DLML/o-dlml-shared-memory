#!/bin/bash
# Measures speedup and efficiency of the N-queens solver by comparing:
#   - Sequential (QueensSeq)           -> baseline
#   - O-DLML local (ApplicationLocal) -> with np = 1, 2, 4, 8 threads
#                                        and strategies: auction, round_robin, work_stealing
#
# It also records how many states each thread processed in each run,
# so the load distribution can be plotted per strategy and size.
#
# Usage:
#   ./benchmark_local_speedup.sh            # default configuration
#   TAMS="14 15" REPS=5 ./benchmark_local_speedup.sh
#
# Configurable environment variables:
#   TAMS        board sizes to evaluate               (default: "17")
#   NPS         thread counts to evaluate             (default: "1 2 4 8")
#   STRATEGIES  load-balancing strategies to evaluate (default: "auction round_robin work_stealing")
#   REPS        repetitions per configuration         (default: 1)
#   REPSS       sequential baseline repetitions       (default: 1)
#   RUN_SEQ     run the sequential baseline (true/false)  (default: true)
#   OUT         speedup CSV                           (default: speedup_results.csv)
#   OUT_DIST    load-distribution CSV                 (default: load_distribution.csv)

set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
TAMS=${TAMS:-"17"}
NPS=${NPS:-"1 2 4 8"}
STRATEGIES=${STRATEGIES:-"auction round_robin work_stealing"}
REPS=${REPS:-1}
REPSS=${REPSS:-1}
RUN_SEQ=${RUN_SEQ:-true}
OUT=${OUT:-"speedup_results.csv"}
OUT_DIST=${OUT_DIST:-"load_distribution.csv"}
CP=".:$DLML_JAR"

# ---------- compilation ----------
echo ">> Compiling QueensSeq, ApplicationLocal, Data..."
javac -cp "$DLML_JAR" QueensSeq.java ApplicationLocal.java Data.java
echo ""

# ---------- CSV headers ----------
echo "version,strategy,tam,np,rep,time_ms,speedup,efficiency" > "$OUT"
echo "strategy,tam,np,rep,thread,items" > "$OUT_DIST"

PHYSICAL_CORES=$(lscpu 2>/dev/null \
    | awk -F: '/Core\(s\) per socket/ {gsub(/ /,"",$2); c=$2}
               /Socket\(s\)/          {gsub(/ /,"",$2); s=$2}
               END {if (c+0>0 && s+0>0) print c*s; else print ""}')
[ -z "$PHYSICAL_CORES" ] && PHYSICAL_CORES=$(nproc 2>/dev/null)
echo "Physical cores detected: ${PHYSICAL_CORES:-?}"
echo ""

# ============================================================
for tam in $TAMS; do
    echo "===================================="
    echo "TAM = $tam"
    echo "===================================="

    # ---- Sequential ----
    TSEQ=""
    if [ "$RUN_SEQ" = "true" ] || [ "$RUN_SEQ" = "1" ]; then
        echo "  [Sequential]"
        SEQ_TIMES=()
        for r in $(seq 1 "$REPSS"); do
            printf "    rep %d/%d... " "$r" "$REPSS"
            t=$(java -Dnqueens.tam="$tam" -cp "$CP" QueensSeq \
                | grep "Total time:" | awk '{print $(NF-1)}')
            SEQ_TIMES+=("$t")
            echo "${t} ms"
            echo "sequential,-,$tam,1,$r,$t,1.0000,1.0000" >> "$OUT"
        done
        TSEQ=$(printf "%s\n" "${SEQ_TIMES[@]}" \
            | awk '{s+=$1} END {printf "%.2f", s/NR}')
        echo "  Sequential average: ${TSEQ} ms"
    else
        echo "  (Sequential baseline skipped: RUN_SEQ=$RUN_SEQ)"
    fi
    echo ""

    # ---- O-DLML local ----
    for strategy in $STRATEGIES; do
        echo "  [O-DLML local | strategy=$strategy]"
        for np in $NPS; do
            if [ -n "$PHYSICAL_CORES" ] && [ "$np" -gt "$PHYSICAL_CORES" ]; then
                echo "  (warning: np=$np exceeds $PHYSICAL_CORES physical cores; the CPUs will be oversubscribed)"
            fi
            for r in $(seq 1 "$REPS"); do
                printf "    np=%-2d  rep %d/%d... " "$np" "$r" "$REPS"
                OUTPUT=$(java -Djava.util.logging.config.file=logging.properties \
                              -Dnqueens.tam="$tam" \
                              -Dodlml.strategy="$strategy" \
                              -cp "$CP" ApplicationLocal "$np" 2>/dev/null)
                t=$(echo "$OUTPUT" | grep "Total time:" | awk '{print $(NF-1)}')
                if [ -n "$TSEQ" ]; then
                    speedup=$(awk -v ts="$TSEQ" -v tp="$t" 'BEGIN {printf "%.4f", ts/tp}')
                    eff=$(awk -v s="$speedup" -v p="$np" 'BEGIN {printf "%.4f", s/p}')
                else
                    speedup="-"
                    eff="-"
                fi
                echo "${t} ms  speedup=${speedup}  efficiency=${eff}"
                echo "odlml,$strategy,$tam,$np,$r,$t,$speedup,$eff" >> "$OUT"

                # Load distribution: "items_per_thread: X Y Z ..."
                datos_line=$(echo "$OUTPUT" | grep "items_per_thread:")
                if [ -n "$datos_line" ]; then
                    hilo=0
                    for cnt in $(echo "$datos_line" | awk '{for(i=2;i<=NF;i++) print $i}'); do
                        echo "$strategy,$tam,$np,$r,$hilo,$cnt" >> "$OUT_DIST"
                        hilo=$((hilo + 1))
                    done
                fi
            done
        done
        echo ""
    done
done
# ============================================================

echo "Speedup saved to:           $OUT"
echo "Load distribution saved to: $OUT_DIST"
echo ""
echo "---- Summary (averages per version/strategy/tam/np) ----"
awk -F',' 'NR>1 {
    key=$1 SUBSEP $2 SUBSEP $3 SUBSEP $4
    sum_t[key]+=$6; sum_s[key]+=$7; sum_e[key]+=$8; cnt[key]++
    ver[key]=$1; strat[key]=$2; tam[key]=$3; np[key]=$4
}
END {
    printf "%-12s %-12s %4s %4s %10s %8s %10s\n",
           "version","strategy","tam","np","time_ms","speedup","efficiency"
    for (k in cnt) {
        n=cnt[k]
        printf "%-12s %-12s %4s %4s %10.1f %8.4f %10.4f\n",
               ver[k], strat[k], tam[k], np[k], sum_t[k]/n, sum_s[k]/n, sum_e[k]/n
    }
}' "$OUT" | sort -k1,1 -k2,2 -k3,3n -k4,4n
