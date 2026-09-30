#!/bin/bash
set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
JSON_JAR="../ODLML/lib/json-20180813.jar"
NP=${1:-4}   # number of threads; override with: ./build-local.sh 8
CP="$DLML_JAR:$JSON_JAR"

echo ">> Compiling sources..."
javac -cp "$CP" \
    Data.java AlertAgg.java CellAgg.java ClusterAgg.java \
    TrafficDLMLLocal.java TrafficLocalSeq.java

echo ""
echo ">> Running TrafficDLMLLocal with $NP threads..."
java -Djava.util.logging.config.file=logging.properties \
     -cp ".:$CP" TrafficDLMLLocal "$NP"
