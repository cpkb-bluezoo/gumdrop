#!/bin/bash
# Runs the RESP codec micro-benchmark (decoder and encoder, no Redis server
# needed) and writes one CSV row per case to $RESULTS.
#
# Environment:
#   SECONDS_PER_CASE  measurement time per case, default 3
#   RESULTS           default results/redis-codec.csv
#   JVM_ARGS          extra JVM arguments (e.g. -Xlog:gc, -XX:+FlightRecorder)
set -eu
cd "$(dirname "$0")"
ROOT=../..
if [ ! -f "$ROOT/dist/gumdrop.jar" ]; then
    echo "dist/gumdrop.jar not found: run 'ant jar' in the project root first" >&2
    exit 1
fi
CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/redis"
mkdir -p results out/redis
javac -nowarn -cp "$CP" -d out/redis src/redis/RespCodecBench.java
RESULTS=${RESULTS:-results/redis-codec.csv}
# shellcheck disable=SC2086
java ${JVM_ARGS:-} -cp "$CP" RespCodecBench "${SECONDS_PER_CASE:-3}" | tee "$RESULTS"
