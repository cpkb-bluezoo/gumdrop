# Container & Cloud Deployment

> **Gumdrop 3:** the general framework has no config file — new deployments
> compose servers in **Java**, not `gumdroprc` XML. See
> [COMPOSITION.md](COMPOSITION.md) and
> [web/configuration.html](../web/configuration.html).
>
> The **stock servlet container distribution** (the one this Docker image
> builds) is the one exception: it is launched by
> [`Bootstrap`](../src/org/bluezoo/gumdrop/Bootstrap.java) and reads a
> minimal `conf/server.xml` describing webapp contexts, realms, session
> clustering and HTTP listeners — see [Configuration](#configuration) below.
> This format is specific to that one launcher; it is not a return to
> `gumdroprc` and nothing else in the framework reads it.

This guide covers running Gumdrop in ephemeral cloud containers (Docker /
Kubernetes): the operational knobs for per-instance robustness and clean
lifecycle, plus the horizontal-scaling constraints you must design around.

Gumdrop is built for a **robust single instance** that runs cleanly in a
container. Replicas should be treated as **stateless / sticky** — see
[Horizontal-scale constraints](#horizontal-scale-constraints) before running
more than one instance.

---

## Quick start

Requires **Java 25+** at build time (see [BUILDING.md](../BUILDING.md)).

```bash
# Build the image (lib/ distribution layout)
docker build -t gumdrop:latest .

# Run the stock servlet container (deploys webapps/ROOT + webapps/manager.war
# per conf/server.xml)
docker run --rm -p 8080:8080 gumdrop:latest
```

The image uses `bin/gumdrop.sh` with jars in `lib/` (no nested-jar
extraction), which launches
[`Bootstrap`](../src/org/bluezoo/gumdrop/Bootstrap.java) →
[`ContainerMain`](../src/org/bluezoo/gumdrop/servlet/container/ContainerMain.java),
reading `conf/server.xml`. To run your own composed `main` instead (see
[COMPOSITION.md](COMPOSITION.md)) in a container, replace the `ENTRYPOINT` —
that path still has no configuration file and reads any environment
variables it needs directly.

---

## Configuration

The **general framework** has no configuration file or file-based env-var
interpolation — compose servers in Java (see
[COMPOSITION.md](COMPOSITION.md)) and read `System.getenv(...)` directly in
your `main` for anything that needs to vary per environment (ports, keystore
passwords, etc.).

The **stock servlet container** (this image's default entrypoint) is
configured by `conf/server.xml`, parsed by
[`ServerXmlLoader`](../src/org/bluezoo/gumdrop/servlet/container/ServerXmlLoader.java)
— see that class's javadoc for the full element/attribute reference. It is
deliberately minimal: realms, session-cluster settings, webapp contexts, and
HTTP(S)/HTTP-3 listeners, nothing more. [`etc/server.xml`](../etc/server.xml)
is the shipped example, copied to `conf/server.xml` by the
`assemble-container` Ant target.

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

A secure (`secure="true"`) listener gets an HTTP/2+TLS listener and an
HTTP/3 (QUIC) listener on the same port automatically — HTTP/3 is a
pure-Java implementation, no native library or separate setup needed.

`ContainerMain` resolves `server.xml` in this order: an explicit CLI
argument, the `GUMDROP_CONFIG` environment variable, then
`$GUMDROP_HOME/conf/server.xml`, then `./conf/server.xml`. Anything more
elaborate than "one `server.xml`, one JVM" — multiple independently
configured containers, non-servlet protocols alongside it, programmatic
webapp discovery — is exactly what [COMPOSITION.md](COMPOSITION.md) covers:
write your own `main` using `ServerXmlLoader` directly, or compose
`Container`/`ServletRequestHandler` yourself.

---

## Health & readiness

Gumdrop does not expose a liveness/readiness HTTP endpoint. Polling a
service over HTTP to ask whether it's up is the wrong pattern for cloud
operations — the alternative is a service publishing readiness to a queue
when it comes online, which is application-specific and out of scope for
the framework itself. Build your own readiness signal into your composed
`main` if your orchestrator needs one.

This applies to the stock `server.xml`-driven container too: the
`HEALTHCHECK`/`EXPOSE 8081`/`/readyz` in the shipped `Dockerfile` predate
this constraint and currently have nothing listening behind them — treat
them as a placeholder for your own readiness `main`, not working health
checks, until one is added.

---

## Graceful shutdown / draining

On `SIGTERM` (or JVM shutdown), `Gumdrop.shutdown()` runs in three phases:

1. **Stop accepting** — the accept loop is stopped and server channels closed.
2. **Drain** — waits up to the drain timeout for in-flight connections to
   finish while the worker loops keep running.
3. **Force stop** — stops services, worker loops, timers and pools.

The `start` launcher uses `exec`, so the JVM receives `SIGTERM` directly.

Tune the drain window (milliseconds) with the `-Dgumdrop.drainTimeoutMs=<ms>`
system property, or explicitly via `GumdropConfig.drainTimeoutMs(...)` in
your composed `main`.

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
address (brittle in pods). Call `.bindWildcard()` on the listener, or pass an
explicit wildcard address to `.addresses(...)`:

```java
new Http2Listener().port(8080).bindWildcard();
// or
new Http2Listener().port(8080).addresses(InetAddress.getByName("0.0.0.0"));
```

---

## JVM sizing (the `start` launcher)

[`start`](../start) and [`bin/gumdrop.sh`](../bin/gumdrop.sh) are container-friendly:

- prefer the **lib/ distribution** (`GUMDROP_HOME/bin/gumdrop.sh`) when present;
  fall back to the legacy `gumdrop-container.jar` fat jar;
- size the heap from the container memory limit with
  `-XX:+UseContainerSupport -XX:MaxRAMPercentage` instead of a fixed `-Xmx`;
- log to the console (12-factor);
- `exec` the JVM so it receives `SIGTERM` directly.

Overridable environment variables:

| Variable             | Default                        | Purpose                                   |
|----------------------|--------------------------------|-------------------------------------------|
| `JAVA`               | `java`                         | Java binary.                              |
| `GUMDROP_HOME`       | (inferred)                     | Install root for lib/ layout.             |
| `GUMDROP_JAR`        | `./dist/gumdrop-container.jar` | Legacy fat jar when lib/ layout absent.   |
| `LOGGING_PROPERTIES` | `logging.properties`           | `java.util.logging` config.               |
| `MAX_RAM_PERCENTAGE` | `75.0`                         | Heap as a percentage of container memory. |
| `JAVA_OPTS`          | (empty)                        | Extra JVM options (appended last).        |

### QUIC / HTTP-3

The HTTP/3 listener is a pure-Java implementation and requires no native
library or extra setup -- just uncomment the HTTP/3 listener in the config.

---

## Hot deploy

Servlet hot deploy uses a filesystem `WatchService` (inotify), which is
pointless and sometimes unsupported on immutable/overlay container
filesystems. It is therefore **off by default**. Enable it explicitly when
needed, either with `container.setHotDeploy(true)` in your composed `main`,
or via the `GUMDROP_HOT_DEPLOY=true` environment variable (used as the
default when `setHotDeploy` isn't called explicitly).

---

## Filesystem expectations

- **Writable `/tmp`** is required for the **legacy fat jar** layout: nested jars are
  extracted to temp files at startup. The **lib/ distribution zip** loads plain
  jars from `GUMDROP_HOME/lib/` and does not extract nested dependencies (servlet
  temp dirs for multipart/JSP still need writable space).
- If you use `readOnlyRootFilesystem`, mount a writable `emptyDir` at `/tmp` (and
  at any servlet work directory).
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
sticky sessions if you use servlet sessions, a drain timeout below the pod
grace period, and no reliance on multicast replication.

For stateful workloads (mail, MQTT): prefer a single instance (optionally with
a shared volume for mail/quota) until an external shared store is introduced.

---

## Reference: environment variables

| Variable                    | Consumed by            | Default              | Purpose                                        |
|-----------------------------|------------------------|----------------------|------------------------------------------------|
| `GUMDROP_CONFIG`            | `ContainerMain`        | (unset)              | Explicit path to `server.xml`; overrides `$GUMDROP_HOME/conf/server.xml`. |
| `GUMDROP_HOME`              | `Bootstrap`, `ContainerMain` | (inferred)      | Install root; also where `conf/server.xml` is found by default. |
| `GUMDROP_HOT_DEPLOY`        | servlet container      | `false`              | Enable servlet hot deploy.                     |
| `MAX_RAM_PERCENTAGE`        | launcher               | `75.0`               | Heap percentage of container memory.           |
| `JAVA`, `GUMDROP_JAR`, `LOGGING_PROPERTIES`, `JAVA_OPTS` | launcher | see table above | Launcher overrides. |

Tune the graceful-drain window with the `gumdrop.drainTimeoutMs` system
property, or `GumdropConfig.drainTimeoutMs(...)` if you read your own
environment variable for it in your composed `main`.

System property equivalent: `-Dgumdrop.drainTimeoutMs=<ms>`.
