# HTTP benchmark: Gumdrop and Netty

The harness behind the "Benchmarks" table in the top-level `README.md`. It
runs a raw-API (no servlet container) HTTP server built on Gumdrop and an
equivalent one built on Netty, drives each with the same closed-loop load
client over loopback, and writes one CSV row per run.

## Setup

Netty is not kept in this repository. `setup.sh` downloads it from Maven
Central into `lib/netty/` (ignored by git), generates a throwaway TLS
keystore in `certs/`, and compiles everything into `out/`:

```bash
ant jar
test/benchmark/setup.sh
```

The Netty version is `NETTY_VERSION` (default `4.1.121.Final`). The jars it
fetches, should you want to place them by hand, are, from
`https://repo1.maven.org/maven2/io/netty/<module>/<version>/`:

    netty-common  netty-buffer  netty-transport  netty-resolver  netty-codec
    netty-codec-http  netty-codec-http2  netty-handler
    netty-transport-native-unix-common

For HTTP/3 it also fetches, from
`https://repo1.maven.org/maven2/io/netty/incubator/<module>/<version>/`:

    netty-incubator-codec-http3          (NETTY_HTTP3_VERSION, default 0.0.29.Final)
    netty-incubator-codec-classes-quic   (NETTY_QUIC_VERSION, default 0.0.71.Final)
    netty-incubator-codec-native-quic    (same version, with a platform classifier
                                          such as osx-aarch_64 or linux-x86_64)

and writes the keystore's certificate and key out as PEM files, which both
HTTP/3 servers take.

Re-run `ant jar` and `setup.sh` after changing Gumdrop.

## Running

```bash
test/benchmark/run_bench.sh
```

Each scenario gets a 5 s warmup (discarded) and a 12 s measurement window,
repeated `REPEATS` times (default 2) with the server restarted each time;
the two servers of a scenario are measured back-to-back. Results go to
`results/results.csv`.

| Scenario | What it does |
|---|---|
| `plaintext-c50`, `-c200`, `-c500` | `GET /`, HTTP/1.1 keep-alive, 13-byte body |
| `json-c100` | `POST` of a small JSON body, parsed and answered with JSON |
| `tls-c50-keepalive` | as plaintext, over TLS 1.3 |
| `tls-c20-handshake` | TLS 1.3 with a new connection per request |
| `h2-c50` | HTTP/2 over TLS 1.3 (ALPN), one connection |

Environment variables: `FRAMEWORKS` (`"gumdrop netty"`), `CLIENT`,
`SCENARIOS` (labels to run), `REPEATS`, `RESULTS`, `SERVER_JVM_ARGS`.

## HTTP/3

```bash
test/benchmark/run_bench_h3.sh
```

One scenario, `h3-c50`: `GET /` over HTTP/3, 50 connections each issuing
requests one after another. Results go to `results/results_h3.csv` in the
same form. `CONCURRENCY` and `NETTY_THREADS` (event loop threads for Netty's
QUIC channel, default 1) can be set in the environment.

Two things to keep in mind when reading it:

- **Netty's QUIC is native code.** `netty-incubator-codec-native-quic` is a
  JNI wrapper around Cloudflare's quiche (Rust) with BoringSSL, shipped
  prebuilt in the platform jar; Netty's Java code handles the datagram I/O
  and the HTTP/3 framing above it. Gumdrop's QUIC, TLS and HTTP/3 are all
  Java. The comparison is of a Java stack with a native one.
- **The load client is Gumdrop's own.** The JDK `HttpClient` does not speak
  HTTP/3, so `H3LoadClient` uses Gumdrop's HTTP/3 client for both servers.
  Its cost is in every figure, and a Gumdrop-to-Gumdrop run has the same
  implementation at both ends.

## The two load clients

`CLIENT=LoadClient` (the default) uses the JDK `HttpClient` with one virtual
thread per concurrent client. This is the method the published figures came
from. It is the only client here that speaks HTTP/2.

**Its HTTP/1.1 numbers say little about the server.** Measured in October
2026 on plaintext at concurrency 50, the load client used about four cores
while the server used less than one and sat idle in the selector: the client
is the bottleneck, and both servers score much the same because both are
waiting for it.

`CLIENT=RawLoadClient` uses one blocking socket and one platform thread per
client, a pre-encoded request and the least response parsing that finds the
end of each response. It roughly doubles HTTP/1.1 throughput against the same
server and makes the server a real share of the cost. It speaks HTTP/1.1
only; for `h2-c50` the run script uses its HTTP/2 counterpart,
`H2LoadClient`: one TLS connection, a request header block encoded once, and
a new stream opened whenever a response ends. It can also be run by hand
with `--connections=N` to spread the streams over several connections, and
`H2_DEBUG_FRAMES=n` in the environment prints the first `n` frames it
receives.

`CLIENT=h2load` uses nghttp2's `h2load` instead, where it is installed
(MacPorts: `sudo port install nghttp2-tools`): an independent check on the
harness's own clients, for the HTTP/1.1 keep-alive and HTTP/2 scenarios. It
cannot open a connection per request, so `tls-c20-handshake` is skipped, and
it reports the median, 95th and 99th percentiles, so the `p90_ms` and
`p999_ms` columns are empty. The MacPorts build has no HTTP/3 support (it is
not built with nghttp3), so the HTTP/3 scenario still needs `H3LoadClient`.

`curl` is not a substitute for any of these: it has no closed loop of
concurrent clients and reports no latency distribution.

## Reading the results

Beside requests per second and latency percentiles, each row has
`server_cpu_cores` (CPU the server process used during the measurement
window, in cores) and `server_cpu_us_per_req` (that divided by throughput).
On a shared machine, where client and server compete for the same cores, the
CPU cost per request is the steadier figure for comparing two servers or two
builds: throughput depends on what else is running and on the load client.

Absolute numbers vary from run to run and between sessions. Compare the two
servers within one run, never a figure from one session with a figure from
another.

## Profiling

```bash
CLIENT=RawLoadClient test/benchmark/profile.sh plain plaintext 18200 http://localhost:18200/ 50
jfr view hot-methods test/benchmark/results/prof/plain.jfr
```

`profile.sh` runs the Gumdrop server under Java Flight Recorder for one load
run; see the comments at the top of the script.

## Redis client

### RESP codec (no server needed)

`run_redis_codec_bench.sh` measures `RespDecoder` and `RespEncoder` in
isolation, on one thread, with no network. It needs only `ant jar`, not
`setup.sh`:

```bash
ant jar
test/benchmark/run_redis_codec_bench.sh
```

| Case | What it does |
|---|---|
| `decode-bulk-{1k,64k,1m}-chunk{512,16384}` | decode one bulk-string reply fed in chunks of 512 bytes or 16 KiB |
| `decode-array100x1k-chunk{512,16384}` | decode an array of 100 bulk strings of 1 KiB, same chunking |
| `encode-set-{16,64k}` | encode `SET key value` with a 16-byte or 64 KiB value |
| `encode-get` | encode `GET key` |

Each case is warmed up for half the measurement time, then run for
`SECONDS_PER_CASE` (default 3). One CSV row per case goes to
`results/redis-codec.csv`: operations per second, MB per second, bytes
allocated per operation (the thread's own allocation counter) and CPU
nanoseconds per operation. `JVM_ARGS` adds JVM options, for example
`-Xlog:gc` or a JFR recording.

### Client against a live server

`run_redis_bench.sh` drives the client against a real Redis or Valkey. One
connection (or one cluster client) keeps a fixed number of commands in
flight, so the number in flight is the pipeline depth: each completion
issues the next command. It needs `ant jar` and a server; Valkey 8 in
podman is what the baseline used. One pod holds a standalone node with a
plaintext port and a TLS port, and three cluster masters:

```bash
mkdir -p /tmp/gd-bench-tls && cd /tmp/gd-bench-tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout key.pem -out cert.pem -days 2 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
chmod 644 key.pem cert.pem
podman pod create --name gd-bench-redis -p 6390-6391:6390-6391 -p 7100-7102:7100-7102
podman run -d --pod gd-bench-redis --name gd-bench-6390 -v /tmp/gd-bench-tls:/tls:ro \
  docker.io/valkey/valkey:8 valkey-server --port 6390 --tls-port 6391 \
  --tls-cert-file /tls/cert.pem --tls-key-file /tls/key.pem --tls-ca-cert-file /tls/cert.pem \
  --tls-auth-clients no --appendonly no --save ""
for p in 7100 7101 7102; do
  podman run -d --pod gd-bench-redis --name gd-bench-$p docker.io/valkey/valkey:8 \
    valkey-server --port $p --cluster-enabled yes --cluster-config-file /tmp/nodes-$p.conf \
    --cluster-node-timeout 5000 --appendonly no --save ""
done
podman exec gd-bench-7100 valkey-cli --cluster create 127.0.0.1:7100 127.0.0.1:7101 \
  127.0.0.1:7102 --cluster-replicas 0 --cluster-yes
```

```bash
test/benchmark/run_redis_bench.sh
```

Each run is warmed up for `WARMUP` seconds (default 3), then measured for
`SECONDS_PER_RUN` (default 8). Rows go to `results/redis.csv`, and the JVM's
own log to `results/redis-bench.log`.

| Scenario | What it does |
|---|---|
| `getset` | 50% `GET`, 50% `SET` over 1000 keys with a 128-byte value, plain, at depth 1, 16 and 256 |
| `publish` | `PUBLISH` of 128 bytes to one channel with no subscribers, plain |
| `getset` (tls) | the same as `getset`, to the TLS port |
| `cluster-getset` | `getset` through the cluster client: keyed commands spread over the three masters |
| `cluster-ping` | `PING` through the cluster client: a keyless command |
| `cluster-publish` | `PUBLISH` through the cluster client |

Columns: operations, errors, duration, operations per second, latency
percentiles in microseconds (from issue to reply, measured on the loop),
CPU microseconds per operation, and bytes allocated per operation. CPU and
allocation are for the whole benchmark JVM, which holds only the client and
the load generator's own small handler objects; the server is in the
container. Settings: `HOST`, `PORT`, `TLS_PORT`, `CLUSTER_PORT` (empty to
skip the cluster rows), `VALUE_BYTES`, `JVM_ARGS`.
