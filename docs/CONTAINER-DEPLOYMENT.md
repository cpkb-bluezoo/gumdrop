# Cloud container deployment (Docker / Podman / Kubernetes)

This guide is about running Gumdrop in **OCI-style cloud containers**: image
layout, process lifecycle, resource limits, and scaling constraints in
orchestrators. It is **not** a tutorial for the stock **servlet container**
install tree (local smoke tests with `ant assemble-container`, `./start`, and
`server.xml` are covered in [BUILDING.md](../BUILDING.md)).

## What you are deploying

Gumdrop is an **async I/O framework**: you compose protocol servers in Java
(`Gumdrop.boot()`, listeners, handlers). That is the primary model for
application developers.

On top of the same core, the project also ships **optional pre-built stacks**
(analogous to bundled servers such as a WebDAV site or a simple FTP file
server):

| Stack | How it is started | Configuration |
|-------|-------------------|---------------|
| **Your custom service** | Your `main()` | Java composition; env vars in code ([web/configuration.html](../web/configuration.html)) |
| **Stock servlet container** | `Bootstrap` → `ContainerMain` | Minimal `conf/server.xml` (webapps, realms, HTTP listeners) |
| Other bundled servers | Their own `main` or composition docs | Varies by protocol |

Cloud deployment looks **different depending on which of these you ship**:

1. **Custom Gumdrop application** — build a jar (or module set) with your
   `main`, put it in **your** container image, set `ENTRYPOINT` to your
   process. No `server.xml`, no `GUMDROP_HOME` layout required unless you
   choose to use them.

2. **Stock servlet container distribution** — use the Ant/Docker build that
   produces the Tomcat-style tree (`bin/`, `lib/`, `webapps/`, `conf/`) and
   the default `bin/gumdrop.sh` entrypoint. This is one pre-built product,
   not a requirement for every Gumdrop user.

The sections below apply to **both** paths where noted; servlet-only details
are marked explicitly.

---

## Quick start: servlet container image

Requires **Java 25+** at build time ([BUILDING.md](../BUILDING.md)).

```bash
docker build -t gumdrop:latest .
docker run --rm -p 8080:8080 gumdrop:latest
```

This runs the **pre-built servlet container** (ROOT webapp + `manager.war` per
`conf/server.xml`). For local checkout smoke tests without Docker, use
`ant assemble-container` and `./start` instead.

To run **your own composed `main`** in a container, replace the image
`ENTRYPOINT` with your application and follow [Custom services in cloud
containers](#custom-gumdrop-services-in-cloud-containers) below.

---

## Custom Gumdrop services in cloud containers

Typical pattern for a protocol or HTTP service you wrote with Gumdrop 3:

1. **Compose in Java** — see [web/configuration.html](../web/configuration.html)
   and `examples/*`. One `main` calls `Gumdrop.boot()`, registers your
   `Server`(s), then `Gumdrop.serve(...)` or `awaitShutdown()`.
2. **Pack** — ship `gumdrop.jar` (and any extra module jars your app needs) plus
   your application classes in a runtime image (JRE 25+).
3. **Configure at runtime** — read `System.getenv(...)` in `main` for ports,
   TLS material paths, secrets, and feature flags. The framework has no global
   config file for composed apps.
4. **Entrypoint** — run your `main` directly, e.g.
   `java -cp app.jar:gumdrop.jar com.example.MyServer`. Use `-XX:MaxRAMPercentage`
   and `exec` so the JVM is PID 1 and receives `SIGTERM` (see [JVM sizing and
   launchers](#jvm-sizing-and-launchers)).
5. **Graceful stop** — call `GumdropConfig.drainTimeoutMs(...)` or set
   `-Dgumdrop.drainTimeoutMs` / `GUMDROP_DRAIN_TIMEOUT_MS`; block the main
   thread on `awaitShutdown()`. Set the orchestrator termination grace period
   above the drain timeout ([Graceful shutdown / draining](#graceful-shutdown--draining)).

Bind listeners with `.bindWildcard()` (or explicit dual-stack wildcard) in pods
rather than enumerating interface addresses ([Wildcard bind](#wildcard-bind)).

Build your own liveness/readiness signalling if the platform requires it
([Health & readiness](#health--readiness)).

---

## Servlet container in cloud containers

If you deploy the **stock servlet container** (zip or Docker image), not a
custom `main`:

- **Entrypoint:** `bin/gumdrop.sh` → `Bootstrap` → `ContainerMain`.
- **Config:** `conf/server.xml` ([`ServerXmlLoader`](../src/org/bluezoo/gumdrop/servlet/container/ServerXmlLoader.java)
  javadoc). Example sources: [`etc/server.xml`](../etc/server.xml),
  [`etc/server-tls.xml`](../etc/server-tls.xml). Override with `GUMDROP_CONFIG`
  or a CLI path to `server.xml`.
- **Layout:** `GUMDROP_HOME` with `lib/` jars (preferred) or legacy fat jar.

```xml
<?xml version='1.0' standalone='yes'?>
<server>
  <realm name="myRealm,Gumdrop Manager" class="org.bluezoo.gumdrop.auth.BasicRealm"
         href="realm-servlet.xml"/>
  <context path="" root="../webapps/ROOT" distributable="true"/>
  <context path="/manager" root="../webapps/manager.war"/>
  <listener port="8080"/>
  <listener port="8443" secure="true" keystore-file="keystore.p12"
            keystore-pass="changeit" bind-wildcard="true"/>
</server>
```

A `secure="true"` listener gets HTTP/2+TLS and **HTTP/3 (QUIC) on the same
port** automatically (pure Java; no native QUIC library).

Servlet-only options: `GUMDROP_HOT_DEPLOY`, session clustering, webapp paths —
see [Hot deploy](#hot-deploy) and [Horizontal-scale constraints](#horizontal-scale-constraints).

Local TLS smoke (mkcert, `./start-tls`) stays in [BUILDING.md](../BUILDING.md);
production TLS in cloud images is your keystore/secret wiring into `server.xml`.

---

## Health & readiness

Gumdrop does not ship a generic liveness/readiness HTTP endpoint for composed
services. Polling HTTP to ask “is the process up?” is usually the wrong
pattern; publish readiness in a way that fits your app (queue, sidecar, exec
probe against your own admin port, etc.).

The stock **Dockerfile** exposes only the servlet HTTP port (8080 by default) and
does **not** define a `HEALTHCHECK`. Add probes in your orchestrator manifest
or derivative image when you implement a real readiness signal.

---

## Graceful shutdown / draining

[`ContainerMain`](../src/org/bluezoo/gumdrop/servlet/container/ContainerMain.java)
and composed apps should block on `Gumdrop.awaitShutdown()` after startup.
`SIGTERM` and Ctrl+C trigger `Gumdrop.shutdown()` (stop accept → drain → force
stop). Launch scripts use `exec` so the JVM receives signals directly.

Phases:

1. **Stop accepting** — accept loop and server channels closed.
2. **Drain** — wait up to the drain timeout for in-flight connections.
3. **Force stop** — stop servers (including servlet `Container.destroy()` when
   applicable), worker loops, timers, pools.

Tune drain (milliseconds):

- System property: `-Dgumdrop.drainTimeoutMs=<ms>`
- Environment (container launchers): `GUMDROP_DRAIN_TIMEOUT_MS`
- Code: `GumdropConfig.drainTimeoutMs(...)`

Default **25000 ms**. Set `terminationGracePeriodSeconds` (or equivalent) above
that. For fast local servlet smoke, `GUMDROP_DRAIN_TIMEOUT_MS=0` is fine
([BUILDING.md](../BUILDING.md)).

---

## Per-instance resource safety

Listener limits apply to **any** Gumdrop server (custom or servlet-backed HTTP):

| Property                  | Setter / config name       | Default    | Purpose                                                        |
|---------------------------|----------------------------|------------|----------------------------------------------------------------|
| Global connection cap     | `max-connections`          | `0` (off)  | Hard cap on concurrent connections; rejects at accept.         |
| Per-IP concurrency cap    | `max-connections-per-ip`   | `0` (off)  | Limits concurrent connections from a single source IP.         |
| Handshake timeout         | `connection-timeout`       | `60s`      | Bounds TLS/DTLS handshake completion.                          |
| First-byte / read timeout | `read-timeout`             | `30s`      | Bounds time to first byte on plaintext connections.            |
| Idle timeout              | `idle-timeout`             | `5m`       | Idle connection timeout (HTTP, IMAP, etc.).                    |
| Inbound buffer cap        | `max-net-in-size`          | `1 MiB`    | Maximum buffered unprocessed inbound bytes.                    |
| Outbound buffer cap       | `max-net-out-size`         | `4 MiB`    | Caps outbound buffering; closes on slow readers.               |

Timeout strings accept forms like `60s`, `5m`.

Related: WebSocket reassembly cap (64 MiB), rate-limiter TTL eviction, accept
backoff on `EMFILE`.

---

## Wildcard bind

In pods, bind one wildcard listener instead of every NIC address:

```java
new Http2Listener().port(8080).bindWildcard();
```

In `server.xml`, use `bind-wildcard="true"` on listeners where supported.

---

## JVM sizing and launchers

**Servlet container / git checkout:** [`start`](../start), [`start.bat`](../start.bat),
[`bin/gumdrop.sh`](../bin/gumdrop.sh) — `-XX:MaxRAMPercentage`, console logging,
`exec` for signals. See [BUILDING.md](../BUILDING.md) for assemble and smoke.

**Custom images:** apply the same JVM flags in your `ENTRYPOINT`; read
`MAX_RAM_PERCENTAGE`, `JAVA_OPTS`, etc. in your launcher or Dockerfile.

| Variable             | Default                        | Purpose                                   |
|----------------------|--------------------------------|-------------------------------------------|
| `JAVA`               | `java`                         | Java binary.                              |
| `GUMDROP_HOME`       | (inferred)                     | Servlet install root (`lib/` layout).     |
| `GUMDROP_JAR`        | `./dist/gumdrop-container.jar` | Legacy fat jar fallback.                  |
| `LOGGING_PROPERTIES` | `logging.properties`           | JUL config file.                          |
| `MAX_RAM_PERCENTAGE` | `75.0`                         | Heap as % of container memory limit.      |
| `JAVA_OPTS`          | (empty)                        | Extra JVM flags.                          |

---

## Hot deploy (servlet container only)

Servlet hot deploy uses filesystem `WatchService` (inotify), which is a poor fit
on immutable overlay roots. **Off by default.** Enable with
`container.setHotDeploy(true)` or `GUMDROP_HOT_DEPLOY=true` when you deliberately
mount mutable webapp directories.

---

## Filesystem expectations

- **Legacy fat jar:** writable `/tmp` for nested-jar extraction.
- **Lib layout:** plain jars under `GUMDROP_HOME/lib/`; servlet/JSP work dirs
  still need writable space.
- **`readOnlyRootFilesystem`:** mount writable `emptyDir` for `/tmp` and servlet
  work directories.
- **Stateful protocols** (mail, quota): shared/persistent volumes as needed.

---

## Horizontal-scale constraints

Applies to **any** Gumdrop process; servlet session notes matter only when you
run the servlet container with distributable apps.

- **Servlet sessions:** in-process; use sticky routing or accept lost sessions
  across replicas.
- **Multicast session replication:** not viable on typical Kubernetes pod
  networks; assume inactive.
- **MQTT broker:** single instance; no clustering.
- **Rate limits / quotas / Digest nonces:** per-instance; sticky routing helps.
- **Mail storage:** shared volume if multiple replicas must see the same mailboxes.
- **DNS cache and similar:** per-instance; cold after restart.

**Stateless HTTP (custom or servlet):** N replicas + LB; sticky sessions if you
use HTTP sessions; drain below pod grace period.

**Stateful mail/MQTT:** prefer one replica or external shared storage.

---

## Reference: environment variables

| Variable                    | Used by                         | Purpose |
|-----------------------------|----------------------------------|---------|
| `GUMDROP_CONFIG`            | Servlet container (`ContainerMain`) | Path to `server.xml`. |
| `GUMDROP_HOME`              | Servlet container bootstrap      | Install root. |
| `GUMDROP_HOT_DEPLOY`        | Servlet container                | Hot deploy toggle. |
| `GUMDROP_DRAIN_TIMEOUT_MS`  | `GumdropConfig` / launchers      | Graceful drain window. |
| `MAX_RAM_PERCENTAGE`, `JAVA`, `JAVA_OPTS`, `LOGGING_PROPERTIES`, `GUMDROP_JAR` | Launchers | JVM and logging. |

Composed applications: read whatever env vars **you** define in your `main`; the
framework does not interpret app-specific settings beyond the drain timeout
above.
