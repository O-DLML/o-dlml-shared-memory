#!/bin/bash
set -e

DLML_JAR="../ODLML/dist/dlml-local-1.0.jar"
NP=${1:-4}   # number of threads; can be overridden: ./build-local.sh 8

echo ">> Compiling HelloDLMLLocal..."
javac -cp "$DLML_JAR" HelloDLMLLocal.java Data.java

echo ">> Running with $NP threads..."
java -Djava.util.logging.config.file=logging.properties \
     -cp ".:$DLML_JAR" HelloDLMLLocal "$NP"
