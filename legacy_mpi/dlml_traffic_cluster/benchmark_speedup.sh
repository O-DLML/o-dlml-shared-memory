#!/bin/bash

REPS=5
NPS="2 4 8"
STRATEGY="auction"

CP_LOCAL=".:../ODLML/lib/*"
CP_ODLML="../ODLML/dist/dlml-1.0-all.jar:."

OUT="speedup_results.csv"

echo "version,np,rep,time_s,speedup,efficiency" > "$OUT"

echo "===================================="
echo "Ejecutando secuencial TrafficLocalSeq"
echo "===================================="

SEQ_TIMES=()

#for r in $(seq 1 $REPS); do
#    echo "Secuencial rep $r..."

#    t=$(java -cp "$CP_LOCAL" TrafficLocalSeq \
#        | grep "Tiempo total:" \
#        | awk '{print $(NF-1)}')

#    SEQ_TIMES+=("$t")
#done


#for r in $(seq 1 $REPS); do
#    echo "Secuencial rep $r..."

#    t=$(cd ../traffic_cluster_local && \
#        java -cp ".:../ODLML/lib/*" TrafficLocalSeq \
#        | grep "Tiempo total:" \
#        | awk '{print $(NF-1)}')

#    SEQ_TIMES+=("$t")
#done


# promedio secuencial
#TSEQ=$(printf "%s\n" "${SEQ_TIMES[@]}" | awk '{s+=$1} END {print s/NR}')

#echo "Tiempo secuencial promedio: $TSEQ s"

#for r in $(seq 1 $REPS); do
#    t=${SEQ_TIMES[$((r-1))]}
#    echo "sequential,1,$r,$t,1.0,1.0" >> "$OUT"
#done

echo ""
echo "===================================="
echo "Ejecutando O-DLML"
echo "===================================="

PHYSICAL_CORES=$(lscpu | awk -F: '/Core\(s\) per socket/ {gsub(/ /,"",$2); cores=$2}
                          /Socket\(s\)/ {gsub(/ /,"",$2); sockets=$2}
                          END {print cores*sockets}')

if [ "$np" -gt "$PHYSICAL_CORES" ]; then
    EXTRA="--oversubscribe"
else
    EXTRA=""
fi

for np in $NPS; do
    for r in $(seq 1 $REPS); do
        echo "O-DLML np=$np rep=$r..."

        t=$(mpirun $EXTRA -np "$np" \
            java --enable-native-access=ALL-UNNAMED \
            -Djava.util.logging.config.file=logging.properties \
            -Dodlml.strategy="$STRATEGY" \
            -cp "$CP_ODLML" Traffic \
            | grep "Tiempo total:" \
            | awk '{print $(NF-1)}' \
            | tail -1)

        speedup=$(awk -v ts="$TSEQ" -v tp="$t" 'BEGIN {print ts/tp}')
        eff=$(awk -v s="$speedup" -v p="$np" 'BEGIN {print s/p}')

        echo "odlml,$np,$r,$t,$speedup,$eff" >> "$OUT"
    done
done

echo ""
echo "Resultados guardados en: $OUT"
