#!/bin/bash
# Runs the Redis client benchmark against a live Redis or Valkey and writes
# one CSV row per scenario to $RESULTS. See README.md for the server setup.
#
# Environment:
#   HOST, PORT, TLS_PORT   standalone node, default 127.0.0.1 6390 6391
#   CLUSTER_PORT           cluster seed node, default 7100 (set to "" to skip)
#   SCENARIOS              default: getset at depth 1 16 256, publish, tls, cluster
#   WARMUP, SECONDS_PER_RUN  default 3 and 8
#   VALUE_BYTES            default 128
#   JVM_ARGS               extra JVM arguments
#   RESULTS                default results/redis.csv
set -eu
cd "$(dirname "$0")"
ROOT=../..
if [ ! -f "$ROOT/dist/gumdrop.jar" ]; then
    echo "dist/gumdrop.jar not found: run 'ant jar' in the project root first" >&2
    exit 1
fi
CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/redis"
mkdir -p results out/redis
javac -nowarn -cp "$CP" -d out/redis src/redis/RedisBench.java
HOST=${HOST:-127.0.0.1}
PORT=${PORT:-6390}
TLS_PORT=${TLS_PORT:-6391}
CLUSTER_PORT=${CLUSTER_PORT-7100}
WARMUP=${WARMUP:-3}
SECS=${SECONDS_PER_RUN:-8}
VB=${VALUE_BYTES:-128}
RESULTS=${RESULTS:-results/redis.csv}
: > results/redis-bench.log
echo "label,depth,tls,value_bytes,ops,errors,duration_s,ops_per_s,p50_us,p99_us,p999_us,cpu_us_per_op,alloc_bytes_per_op" > "$RESULTS"

bench() { # workload host port tls depth
    # shellcheck disable=SC2086
    java ${JVM_ARGS:-} -cp "$CP" RedisBench "$1" "$2" "$3" "$4" "$5" "$VB" "$WARMUP" "$SECS" 2>> results/redis-bench.log | tee -a "$RESULTS"
}

for d in 1 16 256; do bench getset "$HOST" "$PORT" false $d; done
for d in 1 16 256; do bench publish "$HOST" "$PORT" false $d; done
for d in 1 16 256; do bench getset "$HOST" "$TLS_PORT" true $d; done
if [ -n "$CLUSTER_PORT" ]; then
    for d in 1 16 256; do bench cluster-getset "$HOST" "$CLUSTER_PORT" false $d; done
    for d in 1 16 256; do bench cluster-ping "$HOST" "$CLUSTER_PORT" false $d; done
    for d in 1 16 256; do bench cluster-publish "$HOST" "$CLUSTER_PORT" false $d; done
fi
