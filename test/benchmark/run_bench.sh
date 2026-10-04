#!/bin/bash
# Runs the Gumdrop-vs-Netty benchmark matrix: for each scenario, starts one
# server, drives it with the load client, stops it, then does the same for
# the other server, so the two are always measured back-to-back. Each
# scenario is repeated REPEATS times. Writes CSV rows to $RESULTS.
#
# Environment:
#   FRAMEWORKS  servers to run, default "gumdrop netty"
#   CLIENT      LoadClient (JDK HttpClient, the method behind the README
#               figures), RawLoadClient (lean blocking sockets; the
#               HTTP/2 scenario then uses H2LoadClient), or h2load
#               (nghttp2's load tool, if installed: an independent check;
#               it cannot open a connection per request, so that scenario
#               is skipped); default LoadClient
#   SCENARIOS   space-separated scenario labels to run, default all
#   REPEATS     default 2
#   RESULTS     default results/results.csv
#   SERVER_JVM_ARGS  extra JVM arguments for the server under test
set -u
cd "$(dirname "$0")"
ROOT=../..

GUMDROP_CP="$ROOT/dist/gumdrop.jar:$ROOT/lib/*:out/gumdrop"
NETTY_CP="lib/netty/*:$ROOT/lib/jsonparser-1.3.jar:out/netty"
LOADCLIENT_CP=out/loadclient
KEYSTORE=certs/benchmark.p12
KEYPASS=benchpass
FRAMEWORKS=${FRAMEWORKS:-"gumdrop netty"}
CLIENT=${CLIENT:-LoadClient}
SCENARIOS=${SCENARIOS:-"plaintext-c50 plaintext-c200 plaintext-c500 json-c100 tls-c50-keepalive tls-c20-handshake h2-c50"}
RESULTS=${RESULTS:-results/results.csv}
REPEATS=${REPEATS:-2}
SERVER_JVM_ARGS=${SERVER_JVM_ARGS:-}

mkdir -p results
echo "label,framework,repeat,concurrency,requests,errors,duration_s,rps,mbps,p50_ms,p90_ms,p99_ms,p999_ms,server_cpu_cores,server_cpu_us_per_req" > "$RESULTS"

wait_for_port() {
    local port=$1
    for i in $(seq 1 50); do
        if nc -z localhost "$port" 2>/dev/null; then return 0; fi
        sleep 0.2
    done
    echo "server on port $port did not come up" >&2
    return 1
}

# Runs h2load for one scenario and prints the same "CSV,..." line as the
# Java clients. h2load reports the median, 95th and 99th percentiles, so the
# p90 and p999 columns are left empty.
h2load_client() {
    local label=$1 url=$2 concurrency=$3 duration=$4 warmup=$5 extra=$6
    local args="-D $duration --warm-up-time=$warmup"
    case "$extra" in
        *--http2*) args="$args -c 1 -m $concurrency -t 1" ;;
        *) args="$args --h1 -c $concurrency -t 4" ;;
    esac
    case "$extra" in
        *--method=POST*) args="$args -d results/bench-body.json -H content-type:application/json" ;;
    esac
    h2load $args "$url" 2>&1 | awk -v label="$label" -v conc="$concurrency" -v duration="$duration" '
        function ms(v) {
            if (v ~ /us$/) { sub(/us$/, "", v); return v / 1000 }
            if (v ~ /ms$/) { sub(/ms$/, "", v); return v + 0 }
            if (v ~ /s$/) { sub(/s$/, "", v); return v * 1000 }
            return v + 0
        }
        { print }
        # "finished in" gives the time including the warm-up; the rate
        # is over the measured duration alone
        /^finished in/ { secs = duration; rps = $4; mbps = $6; sub(/MB\/s$/, "", mbps) }
        /^requests:/ { ok = $8; failed = $10; errored = $12 }
        /^request  / { p50 = ms($5); p99 = ms($7) }
        END {
            printf "CSV,%s,%d,%d,%d,%.4f,%.2f,%.2f,%.3f,,%.3f,\n", label, conc, ok, failed + errored, secs, rps, mbps, p50, p99
        }'
}

# Total CPU time the process has used, in seconds ("[[HH:]MM:]SS.ss").
cpu_seconds() {
    ps -o cputime= -p "$1" | awk '{ n = split($1, t, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + t[i]; print s }'
}

run_scenario() {
    local label=$1 framework=$2 port=$3 mode=$4 url=$5 concurrency=$6 duration=$7 warmup=$8 extra_client_args=$9
    case " $SCENARIOS " in *" $label "*) ;; *) return ;; esac

    for rep in $(seq 1 $REPEATS); do
        local logf="results/server-$label-$framework-$rep.log"
        local main cp tlsargs=""
        if [ "$framework" = "gumdrop" ]; then
            main=GumdropBenchServer; cp=$GUMDROP_CP
        else
            main=NettyBenchServer; cp=$NETTY_CP
        fi
        if [ "$mode" = "tls" ]; then
            tlsargs="--keystore=$KEYSTORE --keystore-pass=$KEYPASS"
        fi
        java $SERVER_JVM_ARGS -cp "$cp" $main --mode="$mode" --port="$port" $tlsargs > "$logf" 2>&1 &
        local pid=$!

        if ! wait_for_port "$port"; then
            echo "FAILED to start $framework for $label rep $rep" >&2
            kill "$pid" 2>/dev/null
            continue
        fi

        # Server CPU over the middle of the measurement window: one second
        # in from each end, so warmup and shutdown are left out.
        local cpuf="results/.cpu-$$"
        (
            sleep $((warmup + 1))
            c1=$(cpu_seconds "$pid")
            sleep $((duration - 2))
            c2=$(cpu_seconds "$pid")
            echo "$c1 $c2 $((duration - 2))" > "$cpuf"
        ) &
        local sampler=$!

        echo ">>> $label / $framework / rep $rep" >&2
        local out
        if [ "$CLIENT" = "h2load" ]; then
            out=$(h2load_client "$label" "$url" "$concurrency" "$duration" "$warmup" "$extra_client_args")
        else
            out=$(java $CLIENT_JVM_ARGS -cp "$LOADCLIENT_CP" $CLIENT --url="$url" --concurrency="$concurrency" \
                --duration="$duration" --warmup="$warmup" --label="$label" $extra_client_args 2>&1)
        fi
        echo "$out" >&2
        wait "$sampler" 2>/dev/null

        local csvline
        csvline=$(echo "$out" | grep '^CSV,' | sed 's/^CSV,[^,]*,//')
        if [ -n "$csvline" ]; then
            local rps cpu
            rps=$(echo "$csvline" | cut -d, -f5)
            cpu=$(awk -v rps="$rps" '{ cores = ($2 - $1) / $3; printf "%.2f,%.2f", cores, (rps > 0 ? cores * 1000000 / rps : 0) }' "$cpuf" 2>/dev/null)
            echo "$label,$framework,$rep,$csvline,${cpu:-,}" >> "$RESULTS"
        else
            echo "WARNING: no CSV line for $label/$framework/rep$rep" >&2
        fi
        rm -f "$cpuf"

        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
        sleep 0.5
    done
}

# --- Scenario matrix ---

http2_args="--insecure --http2"
insecure="--insecure"
if [ "$CLIENT" = "RawLoadClient" ]; then
    insecure=""
fi

for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "plaintext-c50"  "$fw" 18100 plaintext "http://localhost:18100/" 50  12 5 ""
done

for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "plaintext-c200" "$fw" 18101 plaintext "http://localhost:18101/" 200 12 5 ""
done

for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "plaintext-c500" "$fw" 18102 plaintext "http://localhost:18102/" 500 12 5 ""
done

echo '{"id":42,"name":"gumdrop-bench-load-client"}' > results/bench-body.json
for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "json-c100" "$fw" 18103 json "http://localhost:18103/" 100 12 5 \
        "--method=POST --body-file=results/bench-body.json --content-type=application/json"
done

for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "tls-c50-keepalive" "$fw" 18104 tls "https://localhost:18104/" 50 12 5 "$insecure"
done

for fw in $FRAMEWORKS; do
    [ "$CLIENT" = "h2load" ] && continue
    CLIENT_JVM_ARGS="-Djdk.httpclient.allowRestrictedHeaders=connection"
    run_scenario "tls-c20-handshake" "$fw" 18105 tls "https://localhost:18105/" 20 12 5 "$insecure --close-per-request"
done

# HTTP/2 over the same TLS+ALPN listener, 50 streams on one connection.
# RawLoadClient speaks HTTP/1.1 only; its HTTP/2 counterpart is H2LoadClient.
if [ "$CLIENT" = "RawLoadClient" ]; then
    CLIENT=H2LoadClient
    http2_args=""
fi
# (h2load is told which scenario is HTTP/2 by the --http2 in http2_args)
for fw in $FRAMEWORKS; do
    CLIENT_JVM_ARGS=""
    run_scenario "h2-c50" "$fw" 18106 tls "https://localhost:18106/" 50 12 5 "$http2_args"
done

echo "Done. Results in $RESULTS" >&2
cat "$RESULTS"
