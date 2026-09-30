#!/bin/bash
set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
NP=${1:-4}   # number of threads; can be overridden: ./build-local.sh 8

echo ">> Compiling ApplicationLocal..."
javac -cp "$DLML_JAR" ApplicationLocal.java Data.java

echo ">> Running with $NP threads (TAM=${TAM:-16})..."
java -Dnqueens.tam="${TAM:-16}" -Djava.util.logging.config.file=logging.properties \
     -cp ".:$DLML_JAR" ApplicationLocal "$NP"
