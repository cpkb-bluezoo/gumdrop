#!/bin/bash
# Runs the HTTP/3 scenario for Gumdrop and Netty, back-to-back, REPEATS times.
# Separate from run_bench.sh because the load client is different: the JDK
# HttpClient does not speak HTTP/3, so both servers are driven by Gumdrop's
# own HTTP/3 client (H3LoadClient). Writes CSV rows to $RESULTS in the same
# form as run_bench.sh.
#
# Environment: FRAMEWORKS, REPEATS, RESULTS, SERVER_JVM_ARGS as run_bench.sh;
# CONCURRENCY (default 50); NETTY_THREADS (event loop threads for Netty's
# QUIC channel, default 1).
set -u
cd "$(dirname "$0")"
ROOT=../..

GUMDROP_CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/gumdrop"
NETTY_CP="lib/netty/*:lib/netty-h3/*:$ROOT/lib/jsonparser-1.3.jar:out/netty:out/netty-h3"
CLIENT_CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/loadclient:out/h3client"
CERT=certs/benchmark-cert.pem
KEY=certs/benchmark-key.pem
FRAMEWORKS=${FRAMEWORKS:-"gumdrop netty"}
RESULTS=${RESULTS:-results/results_h3.csv}
REPEATS=${REPEATS:-2}
SERVER_JVM_ARGS=${SERVER_JVM_ARGS:-}
CONCURRENCY=${CONCURRENCY:-50}
NETTY_THREADS=${NETTY_THREADS:-1}
DURATION=12
WARMUP=5
LABEL=h3-c$CONCURRENCY

mkdir -p results
echo "label,framework,repeat,concurrency,requests,errors,duration_s,rps,mbps,p50_ms,p90_ms,p99_ms,p999_ms,server_cpu_cores,server_cpu_us_per_req" > "$RESULTS"

cpu_seconds() {
    ps -o cputime= -p "$1" | awk '{ n = split($1, t, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + t[i]; print s }'
}

for fw in $FRAMEWORKS; do
    for rep in $(seq 1 $REPEATS); do
        logf="results/server-$LABEL-$fw-$rep.log"
        if [ "$fw" = "gumdrop" ]; then
            port=19902
            java $SERVER_JVM_ARGS -cp "$GUMDROP_CP" GumdropBenchServer --mode=h3 --port=$port --cert=$CERT --key=$KEY > "$logf" 2>&1 &
        else
            port=19903
            java -cp "$NETTY_CP" NettyH3BenchServer --port=$port --cert=$CERT --key=$KEY --threads=$NETTY_THREADS > "$logf" 2>&1 &
        fi
        pid=$!
        # UDP: there is no port to probe, so wait for the server to say it is up
        for i in $(seq 1 50); do
            grep -q ' ready' "$logf" 2>/dev/null && break
            sleep 0.2
        done

        cpuf="results/.cpu-$$"
        (
            sleep $((WARMUP + 1))
            c1=$(cpu_seconds "$pid")
            sleep $((DURATION - 2))
            c2=$(cpu_seconds "$pid")
            echo "$c1 $c2 $((DURATION - 2))" > "$cpuf"
        ) &
        sampler=$!

        echo ">>> $LABEL / $fw / rep $rep" >&2
        # the client's log goes to a file: mixed into its output, a log
        # line can land in the middle of the CSV line
        out=$(java -cp "$CLIENT_CP" H3LoadClient --host=localhost --port=$port \
            --concurrency=$CONCURRENCY --duration=$DURATION --warmup=$WARMUP --label=$LABEL \
            2> "results/client-$LABEL-$fw-$rep.log")
        echo "$out" | grep -E '^===|^url=|^requests=|^throughput|^latency|failed' >&2
        wait "$sampler" 2>/dev/null

        csvline=$(echo "$out" | grep '^CSV,' | sed 's/^CSV,[^,]*,//')
        if [ -n "$csvline" ]; then
            rps=$(echo "$csvline" | cut -d, -f5)
            cpu=$(awk -v rps="$rps" '{ cores = ($2 - $1) / $3; printf "%.2f,%.2f", cores, (rps > 0 ? cores * 1000000 / rps : 0) }' "$cpuf" 2>/dev/null)
            echo "$LABEL,$fw,$rep,$csvline,${cpu:-,}" >> "$RESULTS"
        else
            echo "WARNING: no CSV line for $LABEL/$fw/rep$rep" >&2
        fi
        rm -f "$cpuf"

        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        sleep 0.5
    done
done

echo "Done. Results in $RESULTS" >&2
cat "$RESULTS"
