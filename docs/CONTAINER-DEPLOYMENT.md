# Container & Cloud Deployment

This guide covers running Gumdrop in ephemeral cloud containers (Docker /
Kubernetes): the operational knobs for per-instance robustness and clean
lifecycle, plus the horizontal-scaling constraints you must design around.

Gumdrop is built for a **robust single instance** that runs cleanly in a
container. Replicas should be treated as **stateless / sticky** — see
[Horizontal-scale constraints](#horizontal-scale-constraints) before running
more than one instance.

---

## Quick start

```bash
# Build the image (native QUIC/HTTP-3 is not built here; HTTP/1.1+HTTP/2 work)
docker build -t gumdrop:latest .

# Run with the container example config
docker run --rm -p 8080:8080 -p 8081:8081 gumdrop:latest

# Readiness / liveness
curl http://localhost:8081/readyz
curl http://localhost:8081/livez
```

The image starts from [`etc/gumdroprc.container`](../etc/gumdroprc.container),
which is parameterised entirely through environment variables.

---

## Configuration

### Config file location

`main()` resolves the configuration file in this order:

1. an explicit command-line argument,
2. the `GUMDROP_CONFIG` environment variable,
3. `~/.gumdroprc`,
4. `/etc/gumdroprc`.

Set `GUMDROP_CONFIG` to point an immutable image at a mounted config.

### Environment-variable interpolation

Any string value in the XML config (attribute values and element text) may
reference environment variables with:

```
${ENV:NAME}            # required; empty if unset
${ENV:NAME:default}    # with a fallback default
```

This lets a single immutable image be reconfigured per environment
(12-factor style), and keeps secrets such as keystore passwords out of the
image:

```xml
<listener class="org.bluezoo.gumdrop.http.HTTPListener">
    <property name="port" value="${ENV:HTTP_PORT:8080}"/>
    <property name="keystore-pass" value="${ENV:GUMDROP_KEYSTORE_PASS}"/>
</listener>
```

---

## Health & readiness endpoint

`HealthService` exposes a small, dependency-free HTTP endpoint that reports the
server lifecycle state, independent of the main protocol stacks (so it keeps
answering while listeners are still starting or while draining):

```xml
<service class="org.bluezoo.gumdrop.health.HealthService">
    <property name="port" value="${ENV:GUMDROP_HEALTH_PORT:8081}"/>
    <property name="addresses" value="0.0.0.0"/>
</service>
```

| Path                              | Meaning    | Response                                        |
|-----------------------------------|------------|-------------------------------------------------|
| `/livez`, `/healthz`, `/health`   | Liveness   | `200` while the process is up (incl. draining)  |
| `/readyz` (or any other path)     | Readiness  | `200 ready` when bound; `503 starting`/`draining` otherwise |

State transitions: `starting` → `ready` (all listeners bound) → `draining`
(graceful shutdown begun).

Kubernetes probes:

```yaml
livenessProbe:
  httpGet: { path: /livez, port: 8081 }
readinessProbe:
  httpGet: { path: /readyz, port: 8081 }
```

Liveness stays `200` during draining on purpose — an orchestrator should stop
routing new traffic (readiness fails) but must not kill a pod that is finishing
in-flight work.

---

## Graceful shutdown / draining

On `SIGTERM` (or JVM shutdown), `Gumdrop.shutdown()` runs in three phases:

1. **Stop accepting** — the accept loop is stopped and server channels closed;
   readiness immediately flips to `draining`.
2. **Drain** — waits up to the drain timeout for in-flight connections to
   finish while the worker loops keep running.
3. **Force stop** — stops services, worker loops, timers and pools.

The `start` launcher uses `exec`, so the JVM receives `SIGTERM` directly.

Tune the drain window (milliseconds) with, in order of precedence:

- `GUMDROP_DRAIN_TIMEOUT_MS` environment variable, or
- `-Dgumdrop.drainTimeoutMs=<ms>` system property.

Default is **25000 ms**. Set your orchestrator's
`terminationGracePeriodSeconds` comfortably above this.

---

## Per-instance resource safety

These listener properties bound resource use and protect against slow/abusive
peers. They are protocol-agnostic (enforced at the transport layer) and apply
to every listener type. All are optional with safe defaults.

| Property                  | Setter / config name       | Default    | Purpose                                                        |
|---------------------------|----------------------------|------------|----------------------------------------------------------------|
| Global connection cap     | `max-connections`          | `0` (off)  | Hard cap on concurrent connections; rejects at accept.         |
| Per-IP concurrency cap    | `max-connections-per-ip`   | `0` (off)  | Limits concurrent connections from a single source IP.         |
| Handshake timeout         | `connection-timeout`       | `60s`      | Bounds TLS/DTLS handshake completion; closes on expiry.        |
| First-byte / read timeout | `read-timeout`             | `30s`      | Bounds time to first byte on plaintext connections.            |
| Idle timeout              | `idle-timeout`             | `5m`       | Idle connection timeout (consumed by HTTP/IMAP etc.).          |
| Inbound buffer cap        | `max-net-in-size`          | `1 MiB`    | Maximum buffered unprocessed inbound bytes.                    |
| Outbound buffer cap       | `max-net-out-size`         | `4 MiB`    | Caps outbound buffering; closes the connection on overflow (slow/zero-window readers). |

Timeout values accept human-friendly strings (e.g. `60s`, `5m`).

Related, per-protocol:

- **WebSocket max message size** defaults to **64 MiB** so a fragmented message
  cannot grow the reassembly buffer without bound.
- **Rate-limiter maps** (connection & authentication limiters) now self-clean
  via lazy TTL eviction, so they stay bounded under high distinct-IP churn.
- **Accept backoff** — the accept loop backs off briefly on file-descriptor
  exhaustion (`EMFILE`) instead of busy-looping.

### Wildcard bind

For containers, bind a single wildcard socket rather than enumerating each NIC
address (brittle in pods). Either set `wildcard="true"` or use a wildcard
token in `addresses`:

```xml
<property name="wildcard" value="true"/>
<!-- or -->
<property name="addresses" value="0.0.0.0"/>   <!-- also: *, ::, [::] -->
```

---

## JVM & native library sizing (the `start` launcher)

[`start`](../start) is OS-aware and container-friendly:

- picks the correct dynamic-linker variable (`LD_LIBRARY_PATH` on Linux,
  `DYLD_LIBRARY_PATH` on macOS) for the optional native QUIC library;
- sizes the heap from the container memory limit with
  `-XX:+UseContainerSupport -XX:MaxRAMPercentage` instead of a fixed `-Xmx`;
- logs to the console (12-factor);
- `exec`s the JVM so it receives `SIGTERM` directly.

Overridable environment variables:

| Variable             | Default                        | Purpose                                   |
|----------------------|--------------------------------|-------------------------------------------|
| `JAVA`               | `java`                         | Java binary.                              |
| `GUMDROP_JAR`        | `./dist/gumdrop-container.jar` | Server jar.                               |
| `GUMDROP_CONFIG`     | (search order)                 | Config file path.                         |
| `LOGGING_PROPERTIES` | `logging.properties`           | `java.util.logging` config.               |
| `MAX_RAM_PERCENTAGE` | `75.0`                         | Heap as a percentage of container memory. |
| `NATIVE_LIB_PATH`    | `./dist`                       | Extra native library directories.         |
| `QUICHE_DIR`         | `../quiche`                    | quiche checkout for dev builds.           |
| `JAVA_OPTS`          | (empty)                        | Extra JVM options (appended last).        |

### QUIC / HTTP-3 is optional

The HTTP/3 listener requires the native `libgumdrop` library. If it is not on
the library path, the listener is skipped with a warning and the rest of the
server starts normally (serving HTTP/1.1 and HTTP/2). To enable HTTP/3, build
the native library and place it on `NATIVE_LIB_PATH`, then uncomment the
HTTP/3 listener in the config.

---

## Hot deploy

Servlet hot deploy uses a filesystem `WatchService` (inotify), which is
pointless and sometimes unsupported on immutable/overlay container
filesystems. It is therefore **off by default**. Enable it explicitly when
needed:

- in config: `<property name="hot-deploy" value="true"/>`, or
- when not set in config, via the `GUMDROP_HOT_DEPLOY=true` environment
  variable.

---

## Filesystem expectations

- **Writable `/tmp`** is required at startup: the bootstrap classloader
  extracts bundled jars, and the servlet container uses temp dirs (multipart,
  JSP). If you use `readOnlyRootFilesystem`, mount a writable `emptyDir` at
  `/tmp` (and at any servlet work directory).
- **Shared/persistent volumes** are required for stateful services (mail
  storage, quota) — see below.

---

## Horizontal-scale constraints

Gumdrop keeps a range of state **in-process**. Running multiple replicas is
supported only under the following constraints; otherwise run a single
instance.

- **Sessions require sticky routing.** Servlet sessions live in-process.
  Route each client consistently to one replica (sticky sessions / session
  affinity) or sessions will appear to be lost across replicas.
- **Multicast session replication does not form under Kubernetes.** The
  cluster session replication
  ([`servlet/session/Cluster.java`](../src/org/bluezoo/gumdrop/servlet/session/Cluster.java))
  relies on IP multicast, which typical pod networks do not deliver. Assume it
  is inactive and rely on sticky sessions instead.
- **MQTT broker is single-instance.** There is no broker clustering; retained
  messages, subscriptions and session state are per-instance. Run one broker
  replica.
- **Rate-limit / quota / auth state is per-instance.** Connection and
  authentication rate limiters, quotas, and HTTP Digest nonces are held
  in-process and are **not shared** across replicas. With N replicas, effective
  limits are roughly N× and nonces issued by one replica are unknown to
  others — another reason to use sticky routing.
- **Mail / quota storage needs a shared volume.** SMTP/IMAP/POP3 mailbox and
  quota data live on local disk; to share them across replicas, back them with
  a shared/persistent volume (and be aware of the single-writer expectations of
  the mailbox format you choose).
- **DNS cache, sessions, etc. are per-instance** and rebuilt on restart; this
  is fine for ephemeral containers but means no warm state survives a rollout.

### Recommended replica model

For stateless HTTP workloads: run N replicas behind a load balancer with
readiness/liveness probes on the health port, sticky sessions if you use
servlet sessions, a drain timeout below the pod grace period, and no reliance
on multicast replication.

For stateful workloads (mail, MQTT): prefer a single instance (optionally with
a shared volume for mail/quota) until an external shared store is introduced.

---

## Reference: environment variables

| Variable                    | Consumed by            | Default              | Purpose                                        |
|-----------------------------|------------------------|----------------------|------------------------------------------------|
| `GUMDROP_CONFIG`            | server + launcher      | (search order)       | Config file path.                              |
| `GUMDROP_DRAIN_TIMEOUT_MS`  | server                 | `25000`              | Graceful-drain window (ms).                    |
| `GUMDROP_HOT_DEPLOY`        | servlet container      | `false`              | Enable servlet hot deploy when not set in config. |
| `${ENV:NAME[:default]}`     | config parser          | —                    | Interpolate any env var into config values.    |
| `MAX_RAM_PERCENTAGE`        | launcher               | `75.0`               | Heap percentage of container memory.           |
| `JAVA`, `GUMDROP_JAR`, `LOGGING_PROPERTIES`, `NATIVE_LIB_PATH`, `QUICHE_DIR`, `JAVA_OPTS` | launcher | see table above | Launcher overrides. |

System property equivalent: `-Dgumdrop.drainTimeoutMs=<ms>`.
