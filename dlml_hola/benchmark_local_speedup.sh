#!/bin/bash
# Measures speedup and efficiency of the Hello World example by comparing:
#   - Baseline: HelloDLMLLocal with np=1
#   - HelloDLMLLocal with np = 1, 2, 4, 8 threads
#     and strategies: auction, round_robin, work_stealing
#
# Usage:
#   ./benchmark_local_speedup.sh            # default configuration
#   N=40 SLEEP_MS=200 REPS=3 ./benchmark_local_speedup.sh
#
# Configurable environment variables:
#   N           number of messages to process          (default: 40)
#   SLEEP_MS    processing time per message            (default: 200)
#   NPS         thread counts to evaluate              (default: "1 2 4 8")
#   STRATEGIES  load-balancing strategies to evaluate  (default: "auction round_robin work_stealing")
#   REPS        repetitions per configuration          (default: 3)
#   OUT         output CSV file                        (default: speedup_results.csv)

set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
N=${N:-40}
SLEEP_MS=${SLEEP_MS:-200}
NPS=${NPS:-"1 2 4 8"}
STRATEGIES=${STRATEGIES:-"auction round_robin work_stealing"}
REPS=${REPS:-3}
OUT=${OUT:-"speedup_results.csv"}
CP=".:$DLML_JAR"

# ---------- compilation ----------
echo ">> Compiling HelloDLMLLocal, Data..."
javac -cp "$DLML_JAR" HelloDLMLLocal.java Data.java
echo ""

# ---------- CSV header ----------
echo "version,strategy,np,rep,time_ms,speedup,efficiency" > "$OUT"

PHYSICAL_CORES=$(lscpu 2>/dev/null \
    | awk -F: '/Core\(s\) per socket/ {gsub(/ /,"",$2); c=$2}
               /Socket\(s\)/          {gsub(/ /,"",$2); s=$2}
               END {if (c+0>0 && s+0>0) print c*s; else print ""}')
[ -z "$PHYSICAL_CORES" ] && PHYSICAL_CORES=$(nproc 2>/dev/null)
echo "Physical cores detected: ${PHYSICAL_CORES:-?}"
echo "N=$N messages, sleep=${SLEEP_MS}ms per message"
echo ""

# ============================================================
for strategy in $STRATEGIES; do
    echo "===================================="
    echo "Strategy: $strategy"
    echo "===================================="

    # ---- Baseline: np=1 ----
    echo "  [Baseline np=1]"
    BASE_TIMES=()
    for r in $(seq 1 "$REPS"); do
        printf "    rep %d/%d... " "$r" "$REPS"
        t=$(java -Djava.util.logging.config.file=logging.properties \
                 -Dhola.n="$N" \
                 -Dhola.sleep="$SLEEP_MS" \
                 -Dodlml.strategy="$strategy" \
                 -cp "$CP" HelloDLMLLocal 1 2>/dev/null \
            | grep "Total time:" | awk '{print $(NF-1)}')
        BASE_TIMES+=("$t")
        echo "${t} ms"
        echo "odlml,$strategy,1,$r,$t,1.0000,1.0000" >> "$OUT"
    done
    TBASE=$(printf "%s\n" "${BASE_TIMES[@]}" \
        | awk '{s+=$1} END {printf "%.2f", s/NR}')
    echo "  Baseline average (np=1): ${TBASE} ms"
    echo ""

    # ---- O-DLML local (np > 1) ----
    for np in $NPS; do
        [ "$np" -eq 1 ] && continue
        if [ -n "$PHYSICAL_CORES" ] && [ "$np" -gt "$PHYSICAL_CORES" ]; then
            echo "  (warning: np=$np exceeds $PHYSICAL_CORES physical cores; the CPUs will be oversubscribed)"
        fi
        for r in $(seq 1 "$REPS"); do
            printf "    np=%-2d  rep %d/%d... " "$np" "$r" "$REPS"
            t=$(java -Djava.util.logging.config.file=logging.properties \
                     -Dhola.n="$N" \
                     -Dhola.sleep="$SLEEP_MS" \
                     -Dodlml.strategy="$strategy" \
                     -cp "$CP" HelloDLMLLocal "$np" 2>/dev/null \
                | grep "Total time:" | awk '{print $(NF-1)}')
            speedup=$(awk -v ts="$TBASE" -v tp="$t" \
                'BEGIN {printf "%.4f", ts/tp}')
            eff=$(awk -v s="$speedup" -v p="$np" \
                'BEGIN {printf "%.4f", s/p}')
            echo "${t} ms  speedup=${speedup}  efficiency=${eff}"
            echo "odlml,$strategy,$np,$r,$t,$speedup,$eff" >> "$OUT"
        done
    done
    echo ""
done
# ============================================================

echo "Results saved to: $OUT"
echo ""
echo "---- Summary (averages per strategy/np) ----"
awk -F',' 'NR>1 {
    key=$2 SUBSEP $3
    sum_t[key]+=$5; sum_s[key]+=$6; sum_e[key]+=$7; cnt[key]++
    strat[key]=$2; np[key]=$3
}
END {
    printf "%-14s %4s %10s %8s %10s\n",
           "strategy","np","time_ms","speedup","efficiency"
    for (k in cnt) {
        n=cnt[k]
        printf "%-14s %4s %10.1f %8.4f %10.4f\n",
               strat[k], np[k], sum_t[k]/n, sum_s[k]/n, sum_e[k]/n
    }
}' "$OUT" | sort -k1,1 -k2,2n
