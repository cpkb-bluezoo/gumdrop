#!/bin/bash
# profile.sh <name> <mode> <port> <url> <concurrency> [client args...]
# Runs GumdropBenchServer under Java Flight Recorder for one load run and
# leaves results/prof/<name>.jfr. Read it with, for example:
#   jfr view hot-methods results/prof/<name>.jfr
#   jfr view allocation-by-class results/prof/<name>.jfr
#   jfr view cpu-load results/prof/<name>.jfr
# Set HISTO=1 to also take a live-heap class histogram at the end of the run,
# CLIENT=RawLoadClient for the lean HTTP/1.1 client, DURATION for the length.
set -u
cd "$(dirname "$0")"
ROOT=../..
name=$1; mode=$2; port=$3; url=$4; conc=$5; shift 5
mkdir -p results/prof
jfrfile=results/prof/$name.jfr
rm -f "$jfrfile"
extra=""
if [ "$mode" = "tls" ]; then extra="--keystore=certs/benchmark.p12 --keystore-pass=benchpass"; fi
java ${SERVER_JVM_ARGS:-} -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
    -XX:StartFlightRecording=filename=$jfrfile,settings=profile,dumponexit=true \
    -cp "$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/gumdrop" GumdropBenchServer --mode=$mode --port=$port $extra \
    > results/prof/$name.server.log 2>&1 &
pid=$!
for i in $(seq 1 60); do nc -z localhost $port 2>/dev/null && break; sleep 0.25; done
java -cp out/loadclient ${CLIENT:-LoadClient} --url=$url --concurrency=$conc --duration=${DURATION:-15} --warmup=5 \
    --label=$name "$@" 2>&1 | grep -v '^CSV'
if [ -n "${HISTO:-}" ]; then jcmd $pid GC.class_histogram | head -30 > results/prof/$name.histo.txt; fi
kill $pid; wait $pid 2>/dev/null
ls -la $jfrfile
