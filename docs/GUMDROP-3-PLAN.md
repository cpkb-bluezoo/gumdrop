# Gumdrop 3 — migration and implementation plan

Living document for Gumdrop 3 planning. Update this file as goals are refined,
workstreams complete, or open questions are resolved.

**Related:** [CHANGELOG](../CHANGELOG.md) (3.0.0 release notes), sister project
[hopf](https://github.com/cpkb-bluezoo/hopf) (Rust reference architecture),
[FRAMEWORK-COMPARISON.md](FRAMEWORK-COMPARISON.md).

---

## Vision

Gumdrop 3 is not “a servlet container that also does clients”. It is a
**transport-and-protocol framework** for building modern networked software:
servers, clients, and peer-to-peer endpoints with equal first-class support.

Design goals:

- **Mesh-first, modern-internet-first** — IPv6, happy eyeballs, QUIC/HTTP/3,
  DNS privacy transports, post-quantum TLS where available. Deliberately omit
  legacy insecure features (TLS 1.2’s old cipher suites, gratuitous backward
  compatibility) rather than perpetuate them.
- **Handler-driven customisation** — applications implement handler interfaces
  (including staged state-machine handlers where protocols are stateful). They
  do not subclass framework “server” base classes to override behaviour.
- **Unified endpoint model** — transport (`Endpoint`) + protocol adapter
  (`ProtocolHandler` and protocol-specific adapters) + application handlers,
  symmetric for listen and dial paths.
- **Composition over reflection** — explicit builder/composition APIs in Java;
  **no `gumdroprc` XML** in Gumdrop 3.0, removed entirely in C.5
  (see [web/configuration.html](../web/configuration.html)).
- **Explicit runtime** — no process-wide singleton; a `Runtime` (name TBD)
  owns reactor loops, timers, executors, and listener registration.

---

## Major workstreams (size order)

These are the large pillars beyond clearing the GitHub issues list. They can
overlap in time but have dependencies noted below.

| # | Workstream | Nature | Depends on |
|---|------------|--------|------------|
| A | **In-tree TLS stack** | Spec-driven; largely underway | Java 25 baseline |
| B | **Servlet 6.1 container** | Spec-driven (Jakarta Servlet 6.1) | Modular servlet jar (partially A) |
| C | **Role-agnostic refactor** | Architectural; subjective | A stable core API surface |
| D | **Issues / RFC backlog** | Incremental (cert compression #445, ECH #446, etc.) | A for TLS features |
| E | **Telemetry architecture** | Modularisation + API strategy | Modular build (3.0); protobuf spin-off early |

**Suggested sequencing:** finish **A** enough for production parity → parallel
**B** (servlet) and early **C** (Runtime + naming policy, no mass rename yet) →
spin out **protobuf codec (E.1)** early (unblocks grpc/session/telemetry deps) →
**C** mass migration in coordinated slices → **D** ongoing → **E.2** OTel API
decision can land in 3.0 or 3.1 depending on appetite.

---

## Workstream A — In-tree TLS (summary)

Already tracked in CHANGELOG `[Unreleased]` and GitHub issues (#445 cert
compression, #446 ECH, #447 `record_size_limit`, etc.).

Key remaining themes:

- Certificate compression (RFC 8879): Brotli via [micula](https://github.com/cpkb-bluezoo/micula), zlib via JDK; see [#445](https://github.com/cpkb-bluezoo/gumdrop/issues/445).
- Encrypted Client Hello (RFC 9849): [#446](https://github.com/cpkb-bluezoo/gumdrop/issues/446).
- Parity across TCP, DTLS, QUIC for identity, SNI, trust, mTLS.
- RFC-COMPLIANCE.md and `web/tls.html` kept current.

---

## Workstream B — Servlet 6.1

**Scope:** implement Jakarta Servlet **6.1** in the servlet container module.

Servlet work is comparatively well bounded because the specification exists.
Plan items:

1. **Gap analysis** — diff current servlet support against Servlet 6.1 + related
   specs (JSP, authentication constraints, push/trailers already partially
   present in 2.x).
2. **Modular boundary** — servlet stack in its own JPMS module(s); container
   zip layout (`bin/`, `lib/`, `webapps/`, `conf/`) as primary distribution
   (already in CHANGELOG).
3. **TCK-oriented checklist** — track spec sections and compliance tests;
   update examples under `examples/servlet-*`.
4. **Documentation** — servlet chapter in web docs aligned with 6.1, not
   framed as “the reason Gumdrop exists”.

Servlet container remains **one application** of the framework, not the centre of
gravity.

---

## Workstream C — Role-agnostic design

### C.1 Naming and taxonomy

**Status (branch `v3-taxonomy`):** slices **C.1.0**–**C.1.6** complete — public
facades (C.1.1–C.1.5) plus internal renames: mail/FTP lexers, HPACK/QPACK,
SOCKS/AMQP/DNS/mDNS/WebDAV internals, MIME/LDAP/JSP/RESP/OTLP/auth types
(`scripts/c16-internal-rename.py`). **C.2.1** HTTP facades done (`HttpServer`, `HttpClient` at protocol root). **C.2.5** HTTP server SPI done (`http/server/`
handlers, listeners, auth, metrics, `Stream`; `scripts/c25-http-server-spi-move.py`).
**C.2.2** mail protocols done (`smtp/server/SmtpServer`,
`imap/server/ImapServer`, `pop3/server/Pop3Server` + root re-exports). **C.2.3**
remaining protocols done (FTP, DNS, MQTT, SOCKS, mDNS — `*/server/*Server`
implementations + root re-exports; `scripts/c23-remaining-package-move.py`).
`health` was also moved under C.2.3 but subsequently **removed entirely in
C.5** — HTTP polling for liveness/readiness is the wrong pattern for cloud
operations and the package is gone, not just relocated. **C.2.4**
servlet / WebDAV / WebSocket package moves done (`servlet/server/ServletServer`,
`webdav/server/WebdavServer`, … — interim, now superseded by §C.3's handler
composition: `ServletRequestHandler` / `WebDAVRequestHandler` /
`WebSocketRequestHandler` on `HttpServer`; `scripts/c24-http-app-package-move.py`).

#### Naming exceptions (tradenames)

Most acronyms use camelCase (`Http`, `Smtp`, `Dns`). **Documented exceptions:**

| Name | Spelling | Example target type |
|------|----------|---------------------|
| WebDAV | **WebDAV** (tradename) | `WebDAVRequestHandler`, not `WebdavRequestHandler` |
| WebSocket | one word | `WebSocketClient` |

The interim rename `WebDAVService` → `WebdavServer` was over-eager; fixed in
§C.3 (`WebdavLock`/`WebdavLockManager`/`WebdavRequestParser` → `WebDAVLock`/
`WebDAVLockManager`/`WebDAVRequestParser`, and the `Gumdrop3NamingRules`
acronym-table bug that let the misspelling through the guard test).

| Today (examples) | Gumdrop 3 target | Notes |
|------------------|------------------|-------|
| `HttpServer`, `SmtpServer` | `HttpServer`, `SmtpServer` | “Server” = collection of listeners + app wiring; not a `Service` lifecycle contract |
| `Service` interface | Retire or narrow | Lifecycle moves to `Runtime` + optional `Server`/`Client` facades |
| `HTTPServer`, `AMQPClient` | `HttpServer`, `AmqpClient` | **CamelCase acronyms** throughout (hopf precedent) |
| `HttpRequestHandler` | `http.server.HttpRequestHandler` | Handler interfaces live under role subpackages |
| `HttpClient` | `http.HttpClient` | Client facade at protocol root; SPI in `http/client/` |
| `smtp/client/handler/ServerEhloReplyHandler` | Rename to client-side reply handlers | “Server*” in client packages is confusing |

**Name churn:** expect a **mass rename** across `src/`, tests, examples, web
docs, and XML configs. Do it in **vertical slices** (one protocol at a time) or
one **flag day** 3.0.0 beta; document breaking changes in CHANGELOG.

### C.2 Package layout

**Target pattern** (per protocol, e.g. `org.bluezoo.gumdrop.http`):

```
http/
  (shared)          — constants, shared types, algorithms only; avoid heavy
                      materialisation; keep this layer small
  server/             — Http2Listener, HttpRequestHandler, staged handlers
                      server handlers, server-side adapters
  client/             — HttpClient, HttpResponseHandler, client adapters
  h1/, h2/, h2/hpack/, h3/, h3/qpack/, doh/  — version/codec subpackages
```

**Today:** many protocols use `base/` + `client/` subpackage and handlers
 scattered or named `Server*` on the client side. **Migrate** toward hopf’s
layout: `server/` and `client/` siblings under each protocol root.

#### Open question: top-level facades

Should `HttpServer` / `HttpClient` live at:

- **Option 1:** `org.bluezoo.gumdrop.http.server.HttpServer` and
  `.http.client.HttpClient` only (discoverable via package docs), or
- **Option 2:** also re-export at `org.bluezoo.gumdrop.http.HttpServer` /
  `.HttpClient` for ergonomics?

**Decision (2026-09-13): Option 2** — main entry types re-exported at the
protocol root; implementation detail in `server/` / `client/` subpackages.
See [CONTRIBUTING.md](../CONTRIBUTING.md#gumdrop-3-naming-conventions).

**C.2.1 (HTTP facades, done):** `HttpServer` and `HttpClient` live at the
protocol root (canonical implementations). Handler interfaces must **not**
be re-exported with `extends` — it breaks Java assignability.

**C.2.5 (HTTP server SPI, done):** Server-side handler interfaces, factories,
listeners, auth providers, metrics, and the h1/h2 `Stream` implementation live in
`http/server/` (canonical types), mirroring `http/client/`. Shared codec/transport
types (`Headers`, `HttpStatus`, `h2/`, `h3/`, `hpack/`, `qpack/`) remain at
`http/` or version subpackages; server handler SPI (`HttpResponseState`,
`HttpRequestHandler`, …) lives in `http/server/`. (At the time, `ConfigurationParser`
mapped legacy `org.bluezoo.gumdrop.http.HttpListener`/`HTTPService`/`HTTPServer`
XML class names to their canonical types; `ConfigurationParser` and the whole
XML path are now removed in C.5 — there is no legacy-name mapping of any kind.)

**C.2.6 (HttpResponseState, done):** `HttpResponseState` moved from the protocol
root into `http/server/` — it is server handler SPI (outbound response API), not
client-facing and not shared codec. `Stream` and `H3Stream` still implement it.

**C.2.2 (mail, done):** `SmtpServer`, `ImapServer`, `Pop3Server` in
`smtp/server/`, `imap/server/`, `pop3/server/` with root re-exports and
`*Client` re-exports (`scripts/c22-mail-package-move.py`).

**C.2.3 (remaining protocols, done):** FTP (`FtpServer`, file-server variants),
DNS (`DnsServer`), MQTT (`MqttServer`, `DefaultMQTTServer`), SOCKS
(`SocksServer`, `DefaultSOCKSServer`), mDNS (`MdnsServer`) — implementations
in `{protocol}/server/` with root re-exports; listeners and cross-package
helpers publicised where needed (`scripts/c23-remaining-package-move.py`).
`health` (`HealthServer`) was moved here too but removed entirely in C.5.

**C.2.4 (HTTP application servers, done):** `ServletServer`, `WebdavServer`,
`WebSocketServer` in `{protocol}/server/` with root re-exports; servlet and
WebDAV helpers publicised where cross-package wiring requires it
(`scripts/c24-http-app-package-move.py`).

### C.3 Handler-first API (no fat server bases)

**Principle:** applications implement **handler interfaces** and compose servers
in Java. Default server behaviour is stock handlers or explicit wiring — not
abstract classes you must extend.

| Anti-pattern | Target |
|--------------|--------|
| Subclass `HttpServer`, `ServletServer`, `WebdavServer`, `WebSocketServer`, … for app logic | **`HttpServer` + `HttpRequestHandler`** (e.g. `ServletRequestHandler`, `WebDAVRequestHandler`, `WebSocketRequestHandler`) |
| `HttpRequestHandlerFactory` as public SPI | **Handler or router** on `HttpServer` — factory removed |
| Override methods on protocol base classes | Staged handler interfaces + **decorators** for cross-cutting concerns |
| Client already OK (`HttpClient` without subclassing) | Same pattern for all protocols |

**HTTP end state (done):** only **`HttpServer`** at the protocol root. No
`ServletServer`, no `WebDAVServer` / `WebdavServer`, no `WebSocketServer` /
`WebSocketListener` / `Http3WebSocketListener` — servlet, WebDAV, and
WebSocket are **`ServletRequestHandler`**, **`WebDAVRequestHandler`**, and
**`WebSocketRequestHandler`**, all implementing `HttpStreamHandler` /
`HttpRequestHandler`.

**Keep and promote:** staged handler interfaces (SMTP, IMAP, POP3, FTP, etc.)
as the **primary implementer API**.

**Cross-cutting:** auth, telemetry, rate limits — **handler decorators** (hopf
`BasicAuthFactory` idea, applied to handlers not factories).

**Migration steps (C.3) — all done:**

1. `HttpServer.compose()` accepting `HttpStreamHandler`.
2. `ServletRequestHandler`, `WebDAVRequestHandler`, `WebSocketRequestHandler`
   introduced; `ServletServer`, `WebdavServer`, `WebSocketServer` (and its
   `WebSocketListener`/`Http3WebSocketListener` transport listeners) deleted.
3. Public `HttpRequestHandlerFactory` removed; routing lives in handler or a
   small composed router object.
4. Examples and `web/` docs updated to [web/configuration.html](../web/configuration.html).
5. `TlsConfig`/`ClientTlsConfig` unified into one material-only `TlsConfig`.
6. WebDAV spelling (`Webdav` → `WebDAV`) fixed.

See [web/configuration.html](../web/configuration.html) for canonical patterns.

### C.4 Runtime replaces `Gumdrop.getInstance()`

**Today:** ~96 call sites; singleton provides worker loops, timers,
`StorageExecutor`, `CryptoExecutor`, accept loop, client auto-start, service
registry.

**Target:** explicit `Runtime` (or `GumdropRuntime`) created by application
`main` or test harness:

```java
RuntimeConfig config = RuntimeConfig.builder()
    .workerThreads(4)
    .server();
Runtime rt = Runtime.start(config);

HttpServer server = HttpServer.compose()
    .listener(new Http2Listener().port(443).tls(credentials))
    .handler(new ServletRequestHandler(container))
    .server();
server.start(rt);

HttpClient client = HttpClient.builder()
    .host("example.com")
    .server();
client.connect(rt, myHandler);
```

**Migration steps:**

1. Introduce `Runtime` with the same capabilities as today’s singleton.
2. Thread `Runtime` through constructors / `start(rt)` on facades; default
   methods on interfaces may accept `Runtime` optionally during transition.
3. Deprecate `Gumdrop.getInstance()` in 3.0; remove in 3.x follow-up if needed.
4. Refactor `ChannelHandler`, `ClientEndpoint`, transport factories to hold
   `Runtime` reference instead of looking up singleton.
5. Replace `getServices()` pull model (mDNS) with explicit registration at
   composition time (hopf push model).

**Precedent in-tree:** `MailboxRuntime` + SPI — scoped runtime separate from
global singleton.

**Client ergonomics:** hopf requires explicit `Runtime` for dial; Gumdrop may
offer a **test/single-client shortcut** (embedded minimal runtime) but not a
hidden global singleton in library code.

### C.5 Remove XML configuration; composition only

**Status: done.** `org.bluezoo.gumdrop.config` (`ConfigurationParser`,
`ComponentRegistry`, `ComponentDefinition`, `ParseResult`,
`DefaultConfigurator`, …), the `GumdropConfigurator` SPI, and
`Gumdrop.getInstance(File)` are deleted. All 13 XML-driven integration tests
migrated to `AbstractServerIntegrationTest#buildServers()`/`buildListeners()`
composition first (see below), then the XML fixtures
(`test/integration/config/*.xml`, `etc/gumdroprc.*`, `conf/gumdroprc.xml.example`)
were deleted. `Gumdrop.main()` itself is gone too — the general framework
has no entry point of its own; applications write their own `main` (see
`web/configuration.html`).

The one exception is the stock servlet container distribution: its
`Bootstrap` launcher now reflectively invokes
`org.bluezoo.gumdrop.servlet.container.ContainerMain`, which reads a new,
deliberately minimal `server.xml` (`ServerXmlLoader`) — realms, session
clustering, webapp contexts, and HTTP(S)/HTTP-3 listeners, nothing else.
This is not a `gumdroprc` reinstatement (no generic property/component
reflection, scoped to the servlet container use case only) — see
`docs/CONTAINER-DEPLOYMENT.md`.

**Removed (3.0):**

- `gumdroprc` XML configuration as a supported deployment path
- `ComponentRegistry`, reflective setter injection, `<component class="…">`
  arbitrary class loading
- `ConfigurationParser` as the primary way to start applications
- Documentation and examples that teach XML-first setup
- The `health` package (`HealthServer`, the k8s liveness/readiness HTTP
  endpoint) — HTTP-polled health checks are the wrong pattern for cloud
  operations; no replacement is planned

**Replace with:**

- **Java composition** — explicit `Runtime`, listeners, handlers (see
  [web/configuration.html](../web/configuration.html))
- **Builder APIs** per protocol (`HttpServer.compose()`, `SmtpClient.builder()`, …)
- **`main` or test harness** wiring for complex stacks (mailbox + SMTP, etc.)
  as hopf documents

**Not planned for 3.0:** maintaining XML in parallel while composition APIs
change. A future declarative format is **out of scope** until there is a solid
design; do not track XML through handler-first refactors.

**Documentation obligation:** `web/configuration.html`, protocol pages
(`http.html`, `servlet.html`, `webdav.html`, …), `examples/*`, and
`docs/CONTAINER-DEPLOYMENT.md` must show composition as the **only** canonical
path.

### C.6 Listener / Server architecture

**Keep:** `Listener` as transport endpoint (addresses, port, TLS, limits).

**HTTP application tier:** **`HttpServer`** owns listeners and one
`HttpRequestHandler` (or decorator chain). Servlet and WebDAV are handlers, not
separate server types (§C.3).

**Other protocols:** `*Server` facades (SMTP, IMAP, …) own listeners and
handlers; same handler-first rules apply over time.

**Clarify:** `Server` is not a lifecycle interface for arbitrary apps — it is
“this process’s configured listeners + handlers for protocol X”. Optional
`start(rt)` / `stop()` on facades.

### C.7 Mesh-first / modern protocol policy

Document explicit **non-goals** and **deprecation** targets for 3.x:

- Prefer QUIC / HTTP/3 / DoQ / DoH where HTTP/DNS over cleartext or legacy
  paths existed for convenience.
- TLS 1.2 retained only where spec requires (narrow profile); no antique cipher
  suites “for compatibility”.
- IPv6-first listener defaults; **happy eyeballs** in client dial paths
  (centralise in `Runtime` / client facades, not per-protocol copies).
- P2P worked example: two peers, each with `Runtime`, symmetric handlers, no
  “client library” vs “server library” split in documentation.

---

## Workstream D — Issues and RFC backlog

Track on GitHub; link issues here as they are filed or closed.

| Issue | Topic |
|-------|--------|
| [#445](https://github.com/cpkb-bluezoo/gumdrop/issues/445) | RFC 8879 certificate compression (Brotli + zlib) |
| [#446](https://github.com/cpkb-bluezoo/gumdrop/issues/446) | RFC 9849 Encrypted Client Hello |
| [#447](https://github.com/cpkb-bluezoo/gumdrop/issues/447) | RFC 8449 `record_size_limit` |
| *(add rows)* | |

---

## Workstream E — Telemetry architecture

Gumdrop already implements **OTLP-compatible export** (HTTP/protobuf, gRPC,
JSONL) and **OTel-aligned semantics** (`SpanKind`, resource attributes, W3C
Trace Context) using **native types** (`Trace`, `Span`, `Meter`, …) — not the
`io.opentelemetry` API. The incremental protobuf codec lives under
`telemetry.protobuf` but is **shared infrastructure** (gRPC, servlet session
replication, OTLP serializers).

### E.1 Spin off the protobuf codec (like hopf `rprotobuf`)

**Problem:** `ProtobufWriter` / `ProtobufParser` are general-purpose,
zero-dependency, push-based wire codecs — the same role as [rprotobuf](https://crates.io/crates/rprotobuf)
in hopf — but they sit in a telemetry package name and ship inside
`gumdrop-telemetry.jar`, while **gRPC and servlet session code in core/grpc
already depend on them**.

**Target:**

| Layer | Contents | Artifact (proposed) |
|-------|----------|---------------------|
| **Codec** | `ProtobufWriter`, `ProtobufParser`, `ProtobufHandler`, `DefaultProtobufHandler`, `ByteBufferChannel`, `ProtobufParseException` | **`jprotobuf`** (or separate repo `cpkb-bluezoo/jprotobuf`) — LGPL, JPMS module, Maven Central |
| **OTLP encoders** | `TraceSerializer`, `MetricSerializer`, `LogSerializer`, `OtlpFieldNumbers` | `gumdrop-telemetry` (depends on jprotobuf) |
| **Export transport** | `OtlpExporter`, `OtlpGrpcExporter`, JSONL file exporter, HTTP/gRPC endpoints | `gumdrop-telemetry` (optional jar, unchanged role) |
| **Instrumentation** | `Trace`, `Span`, `TelemetryConfig`, `*Metrics`, protocol auto-instrumentation | `gumdrop-core` or `gumdrop-otel` module |

**Package rename:** `org.bluezoo.protobuf` →
`org.bluezoo.jprotobuf` (or `org.bluezoo.protobuf`), matching the
`gonzalez-core` / `jsonparser` sibling-library pattern.

**Repo vs in-tree jar:** either is fine. A **separate repo** mirrors hopf’s
crates.io split and lets non-Gumdrop projects use the codec; an **in-tree jar**
published as `org.bluezoo:jprotobuf` is enough for 3.0 if repo overhead is
undesirable. Decide in Phase 1.

**Consumers to migrate:** `grpc`, `servlet.session`, `telemetry.otlp`, tests,
`web/grpc.html` examples.

### E.2 OpenTelemetry Java API — adopt interfaces or stay native?

**Current state:** Gumdrop is an **OTel-style implementation** (wire formats +
semantics) without depending on `opentelemetry-api`. Exporters and async
SelectorLoop integration are **Gumdrop-native** and arguably better suited to
the framework than the OTel SDK’s threading model.

**OTel Java split (relevant to us):**

| Artifact | Role | Gumdrop 3 stance |
|----------|------|------------------|
| `opentelemetry-api` | Stable interfaces for libraries (`Tracer`, `Span`, `Meter`) | **Candidate dependency** — Apache 2.0 |
| `opentelemetry-context` | Context propagation (transitive of API) | Comes with API; evaluate `Context` bridge to Gumdrop `Trace` |
| `opentelemetry-sdk` | Reference implementation, batch processors, samplers | **Do not adopt** — keep Gumdrop engine |
| `opentelemetry-exporter-otlp` | SDK exporters | **Do not adopt** — keep Gumdrop OTLP exporters |

**Licence:** `opentelemetry-api` is **Apache License 2.0**, not LGPL. That is
**compatible** with Gumdrop’s LGPL distribution model: a normal Maven dependency
on an Apache-licensed library does not “infect” the combined work the way
copyleft-on-linking fears assume. (Same pattern as using the JDK or other
Apache-licensed libs today.)

**Pluggability:** The OTel API is explicitly designed for **multiple
implementations** — libraries depend on API only; the application chooses an
SDK or alternative backend. Gumdrop would register as the backend via
`OpenTelemetry`/`GlobalOpenTelemetry` **or** (preferably for Gumdrop 3) expose
`Runtime`-scoped `TracerProvider` / `MeterProvider` without global statics.

**Three options (pick one for 3.0):**

| Option | Summary | Pros | Cons |
|--------|---------|------|------|
| **A — Native API only** | Keep `org.bluezoo.gumdrop.telemetry.*` as the public surface | Zero OTel deps; full control; matches hopf | Third-party OTel-instrumented libs need adapters |
| **B — Dual surface** | Native API + optional `gumdrop-telemetry-otel-api` module implementing `io.opentelemetry.api.*` over Gumdrop internals | Ecosystem interop; still no OTel SDK | Two APIs to maintain; bridge complexity |
| **C — OTel API primary** | Instrumentation uses `io.opentelemetry.api.*`; Gumdrop types become internal | Best library compatibility | Largest refactor; must document “no OTel SDK required” clearly |

**Recommendation (draft):** **Option B for 3.0**, **Option C only if** servlet /
embedder demand for drop-in OTel library instrumentation is high.

- Keep Gumdrop **`Trace` / `Span` / export pipeline** as the implementation
  (SelectorLoop-friendly, no OTel SDK batch threads fighting the reactor).
- Add an **optional module** `gumdrop-telemetry-otel-api` (Apache 2.0 **dependency
  isolated to that module** if licence separation matters for downstream
  packaging) that implements `Tracer`, `Span`, `Meter` by delegating to Gumdrop
  types.
- Document clearly: **“Gumdrop is the SDK and exporter; you do not need
  `opentelemetry-sdk` or `opentelemetry-exporter-otlp`.”**
- W3C propagation stays in core (`traceparent`); map OTel `Context` ↔ Gumdrop
  active span at API boundaries.

**What we are not giving up:** OTLP/protobuf export quality, async export on the
SelectorLoop, JSONL file exporter, JMX bridge — all remain Gumdrop-owned.

### E.3 Telemetry module boundaries (3.0 target)

```
jprotobuf.jar              — wire codec only (E.1)
gumdrop-core               — Trace, Span, W3C propagation, TelemetryConfig hooks
gumdrop-telemetry.jar      — OTLP/JSONL export (optional), depends on jprotobuf + http
gumdrop-telemetry-otel-api — optional OTel API bridge (E.2 option B)
```

Fix today's awkward edge: `gumdrop-telemetry` POM depends on `gumdrop-grpc`
only because protobuf lived in telemetry — **grpc should depend on jprotobuf,
not telemetry**.

### E.4 Open questions (telemetry)

1. **Separate repo** for jprotobuf vs `org.bluezoo:jprotobuf` published from
   gumdrop monorepo?
2. **OTel API option** A / B / C above — native-only vs dual vs primary?
3. **GlobalOpenTelemetry** — support for apps that expect it, or
   Runtime-scoped providers only (hopf-style explicit composition)?
4. **Semantic conventions** — bundle OTel semconv attribute names as constants,
   or depend on `opentelemetry-semconv` (also Apache 2.0)?

---

## Additional recommendations (developer experience)

Items not in the initial notes but important for “maximally simple and
consistent” public API:

### Documentation and examples

- [ ] Restructure **web docs** so every protocol page has **Server** and
  **Client** sections of equal prominence (today many pages are server-first).
- [ ] Add **P2P / mesh** guide: one `Runtime`, two endpoints, same handler
  patterns — target audience for “new protocol in Gumdrop”.
- [ ] Pair examples: `examples/*-server` and `examples/*-client` for each
  major protocol; **Java composition** entry points (see [web/configuration.html](../web/configuration.html)).
- [ ] **FRAMEWORK-COMPARISON.md** — update hopf ↔ gumdrop mapping as 3.0
  lands.

### Public API hygiene

- [ ] Audit **`public`** types per JPMS module; shrink surface to facades +
  handler SPIs + config builders.
- [ ] **`@Deprecated` migration table** in CHANGELOG for every renamed type
  (even if 3.0 breaks binary compat — helps source migration).
- [ ] Metrics types: rename `*ServerMetrics` → neutral or
  `server.ServerMetrics` in role subpackages.

### Testing during migration

- [x] Rename guard via package scan (`Gumdrop3NamingConventionTest`).
- [ ] Integration tests: wire servers via Java composition builders (proves API
  before further doc churn).
- [ ] Keep **NoThreadSleepGuard** and async test rules (CONTRIBUTING) during
  refactors.

### Modular build (CHANGELOG alignment)

- [ ] JPMS module per protocol aligned with `server/` / `client/` packages
  (e.g. `org.bluezoo.gumdrop.http` exports server + client packages explicitly).
- [ ] Umbrella **`gumdrop.jar`** vs selective deps documented for embedders
  (P2P app may depend only on `gumdrop-core` + `gumdrop-http` + `gumdrop-quic`).

### Configuration

- [x] **Drop `gumdroprc` from 3.0 documentation** — [web/configuration.html](../web/configuration.html)
  and [web/configuration.html](../web/configuration.html) are composition-first.
- [x] Remove `ComponentRegistry` / `ConfigurationParser` from runtime startup path (§C.5, done).
- [x] Migrate `examples/*` and remaining `web/*.html` XML snippets to composition (§C.5, done — `examples/*` had none; `web/*.html` updated).

---

## Phased rollout (suggested)

### Phase 0 — Planning (current)

- [x] Capture vision and workstreams (this document).
- [x] Resolve facade re-exports (§C.2 — Option 2).
- [ ] Resolve exact `Runtime` name.
- [ ] Servlet 6.1 gap analysis document or checklist issue.

### Phase 1 — Foundation (3.0 alpha)

- [ ] TLS stack production-ready (workstream A).
- [ ] Introduce `Runtime` parallel to singleton (both work briefly).
- [x] Define naming convention RFC (camelCase acronyms) in CONTRIBUTING +
  [CONTRIBUTING.md](../CONTRIBUTING.md#gumdrop-3-naming-conventions); guard test
  (`Gumdrop3NamingConventionTest`).
- [ ] Introduce `HttpServer.compose()` + handler composition ([web/configuration.html](../web/configuration.html)).
- [ ] Extract **jprotobuf** codec jar; fix grpc/telemetry dependency direction (§E.1).

### Phase 2 — Servlet + modular container (3.0 beta)

- [ ] Servlet 6.1 implementation (workstream B).
- [ ] Container zip as primary distribution.
- [ ] JPMS modules stable.

### Phase 3 — Role-agnostic migration (3.0 RC)

- [ ] Rename `*Service` → `*Server` (application tier) — see
  [CONTRIBUTING.md](../CONTRIBUTING.md#gumdrop-3-naming-conventions) slices C.1.1–C.1.6.
- [ ] Protocol package moves (`server/`, `client/`).
- [ ] Mass type renames (`HttpServer`, `AmqpClient`, …).
- [x] Handler-first HTTP: `ServletRequestHandler`, `WebDAVRequestHandler`,
  `WebSocketRequestHandler`; `ServletServer` / `WebdavServer` / `WebSocketServer`
  removed from public API.
- [x] Remove reflection DI and XML configuration code paths (§C.5, done).
- [ ] Remove `Gumdrop.getInstance()` (or hard-deprecate with runtime-only path) — client-only singleton still in use; `boot()` covers server mode (§C.4).

### Phase 4 — Polish (3.0 GA)

- [ ] Documentation and examples fully migrated.
- [ ] P2P guide and mesh-first policy published.
- [ ] Issues backlog triaged for 3.0 vs 3.1.

---

## Open questions (discussion)

1. ~~**Top-level facade re-exports** — Option 1 vs 2 in §C.2?~~ **Resolved: Option 2.**
2. **`Runtime` naming** — `Runtime`, `GumdropRuntime`, or `EventRuntime`?
3. **3.0 breaking change budget** — single rename flag day vs phased deprecations
   across 3.0 alphas?
4. ~~**`gumdroprc` in 3.0**~~ — **Resolved: removed.** Java composition only;
   see [web/configuration.html](../web/configuration.html). No parallel XML tracking during C.3.
5. ~~**WebDAV naming** — converge public API on **WebDAV** (tradename); rename
   interim `Webdav*` types when handler migration lands.~~ **Resolved:**
   `WebdavLock`/`WebdavLockManager`/`WebdavRequestParser` renamed to
   `WebDAVLock`/`WebDAVLockManager`/`WebDAVRequestParser`.
6. **Servlet module optional?** — embedders who never serve HTTP may omit
   servlet jar entirely (already directionally true with modular build).
7. **Legacy protocol tier** — which listeners remain in 3.0 default build vs
   `optional` modules (e.g. FTP, Telnet-era patterns)?
8. **jprotobuf repo** — separate repository vs published jar from monorepo (§E.1)?
9. **OTel API strategy** — native-only vs optional bridge vs OTel-primary (§E.2)?
10. **`GlobalOpenTelemetry`** — support global static registration or Runtime-only?

---

## Tracking

| Item | Status |
|------|--------|
| This plan | Draft |
| CHANGELOG 3.0.0 section | Draft (TLS/modularity) |
| TLS cert compression #445 | Spec refined |
| Role-agnostic refactor | C.1–C.3, C.5 done *(branch `v3-taxonomy`)* |
| Servlet 6.1 | Not started |
| Runtime introduction | In progress — `Gumdrop.boot()` replaces the server-mode singleton (§C.4); rename to `Runtime` still open |
| XML configuration removal (§C.5) | Done |
| Telemetry / jprotobuf spin-off | Not started |
| OTel API strategy decision | Open (§E.2) |
| Facade re-exports (§C.2) | Option 2 decided |

*Last updated: 2026-09-16*
