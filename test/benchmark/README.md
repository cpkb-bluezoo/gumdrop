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
only, so `h2-c50` is skipped.

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
