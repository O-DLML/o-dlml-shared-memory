#!/bin/bash
set -e

# Shared classes (interfaces, strategies) — no MPI dependency
SRC_COMMON="DataLike.java DLMLOne.java AuctionStrategy.java LoadBalancingStrategy.java \
            RoundRobinStrategy.java StrategyFactory.java StrategyType.java WorkStealingStrategy.java"

# Threads implementation — does not use MPI
SRC_LOCAL="Msg.java ProcessState.java SharedBus.java ProtocolThread.java DLMLLocal.java"

BUILD_DIR="build-dlml"
DIST_DIR="dist"
VER="1.0"

rm -rf "$BUILD_DIR" && mkdir -p "$BUILD_DIR" "$DIST_DIR"

echo ">> Compiling common classes and DLMLLocal (javac)..."
javac -cp "lib/*" -d "$BUILD_DIR" $SRC_COMMON $SRC_LOCAL

echo ">> Packaging local JAR (DLMLLocal + common classes)..."
jar cf "$DIST_DIR/dlml-local-$VER.jar" -C "$BUILD_DIR" .
echo "   -> $DIST_DIR/dlml-local-$VER.jar"

echo ">> Building fat JAR (DLMLLocal + lib/*)..."
TMP="build-fat-tmp"
mkdir -p "$TMP"
cp -r "$BUILD_DIR"/. "$TMP"/
for J in lib/*.jar; do
  [ -e "$J" ] || continue
  (cd "$TMP" && jar xf "../$J") || true
done
rm -rf "$TMP/META-INF" 2>/dev/null || true
jar cf "$DIST_DIR/dlml-local-fat-$VER.jar" -C "$TMP" .
rm -rf "$TMP"
echo "   -> $DIST_DIR/dlml-local-fat-$VER.jar  (org.json included)"

echo "OK. O-DLML ready in dist/"
