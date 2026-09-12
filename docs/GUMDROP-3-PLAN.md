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
- **Composition over reflection** — explicit builder/configuration APIs; XML
  (`gumdroprc`) desugars into builders via a **closed registry**, not
  reflective DI.
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

| Today (examples) | Gumdrop 3 target | Notes |
|------------------|------------------|-------|
| `HTTPService`, `SMTPService` | `HttpServer`, `SmtpServer` | “Server” = collection of listeners + app wiring; not a `Service` lifecycle contract |
| `Service` interface | Retire or narrow | Lifecycle moves to `Runtime` + optional `Server`/`Client` facades |
| `HTTPServer`, `AMQPClient` | `HttpServer`, `AmqpClient` | **CamelCase acronyms** throughout (hopf precedent) |
| `HTTPRequestHandler` | `http.server.HttpRequestHandler` | Handler interfaces live under role subpackages |
| `HTTPClient` | `http.client.HttpClient` | Client facades mirror server naming |
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
  server/             — HttpServer, HttpListener, HttpRequestHandler, staged
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

**Recommendation:** Option 2 for main entry types only (facades + primary
handler interfaces), with implementation detail in subpackages — similar to
hopf’s umbrella crate re-exports.

### C.3 Handler-first API (no fat server bases)

**Principle:** default server behaviour is “do nothing” or stock handlers, not
abstract classes you must extend.

| Anti-pattern | Target |
|--------------|--------|
| Subclass `HTTPService` / `WebDAVService` / `ServletService` for app logic | Compose `HttpServer` with `HttpRequestHandlerFactory` / decorators |
| Override methods on protocol base classes | Implement staged handler interfaces or wrap factories |
| Client already OK (`HTTPClient` works without subclassing) | Extend that pattern to all protocols |

**Keep and promote:** staged handler interfaces (SMTP, IMAP, POP3, FTP, etc.)
as the **primary implementer API** — this is a Gumdrop strength.

**Add:** factory **decorators** for cross-cutting concerns (auth, telemetry,
rate limits) instead of subclassing `*Service` — hopf’s `BasicAuthFactory`
pattern.

### C.4 Runtime replaces `Gumdrop.getInstance()`

**Today:** ~96 call sites; singleton provides worker loops, timers,
`StorageExecutor`, `CryptoExecutor`, accept loop, client auto-start, service
registry.

**Target:** explicit `Runtime` (or `GumdropRuntime`) created by application
`main` or test harness:

```java
RuntimeConfig config = RuntimeConfig.builder()
    .workerThreads(4)
    .build();
Runtime rt = Runtime.start(config);

HttpServer server = HttpServer.builder()
    .listener(HttpListener.builder().port(443).tls(credentials).build())
    .handlerFactory(myFactory)
    .build();
server.start(rt);

HttpClient client = HttpClient.builder()
    .host("example.com")
    .build();
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

### C.5 Retire reflection DI; composition + closed registry

**Remove:**

- `ComponentRegistry`, reflective setter injection, `<component class="…">`
  arbitrary class loading.

**Replace with:**

- **Builder APIs** per protocol (`HttpServer.builder()`, `SmtpClient.builder()`).
- **Composition** type that applies bindings and starts `Runtime` (hopf
  `Composition` + `CompositionRegistry`).
- **XML** (optional): parses to builder calls; handler names resolve through a
  **closed** `Map<String, HandlerFactory>` registered at startup — no
  `Class.forName` for application code.
- Complex wiring (mailbox + SMTP + runtime refs) stays in Java composition
  code, as hopf documents for `LocalDeliveryService`-style stacks.

### C.6 Listener / Server architecture

**Keep:** `Listener` as transport endpoint (addresses, port, TLS, limits).

**Rename:** `*Service` → `*Server` for the application tier that owns listeners
and handler factories (Gumdrop 1.x naming restored with 2.x architecture).

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
| **OTLP encoders** | `TraceSerializer`, `MetricSerializer`, `LogSerializer`, `OTLPFieldNumbers` | `gumdrop-telemetry` (depends on jprotobuf) |
| **Export transport** | `OTLPExporter`, `OTLPGrpcExporter`, JSONL file exporter, HTTP/gRPC endpoints | `gumdrop-telemetry` (optional jar, unchanged role) |
| **Instrumentation** | `Trace`, `Span`, `TelemetryConfig`, `*Metrics`, protocol auto-instrumentation | `gumdrop-core` or `gumdrop-otel` module |

**Package rename:** `org.bluezoo.gumdrop.telemetry.protobuf` →
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
  major protocol; composition example replacing XML-only configs.
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

- [ ] Rename **allowlists** / package scans when enforcing style tests.
- [ ] Integration configs: migrate from XML components to Java composition
  builders in tests first (proves API before docs).
- [ ] Keep **NoThreadSleepGuard** and async test rules (CONTRIBUTING) during
  refactors.

### Modular build (CHANGELOG alignment)

- [ ] JPMS module per protocol aligned with `server/` / `client/` packages
  (e.g. `org.bluezoo.gumdrop.http` exports server + client packages explicitly).
- [ ] Umbrella **`gumdrop.jar`** vs selective deps documented for embedders
  (P2P app may depend only on `gumdrop-core` + `gumdrop-http` + `gumdrop-quic`).

### Configuration files

- [ ] **`gumdroprc` schema version** bump for 3.0: document mapping from
  `<service class="…">` to composition entries.
- [ ] Environment placeholder syntax retained; drop `#id` component graphs if
  composition is builder-only.

---

## Phased rollout (suggested)

### Phase 0 — Planning (current)

- [x] Capture vision and workstreams (this document).
- [ ] Resolve open questions (facade re-exports, exact `Runtime` name).
- [ ] Servlet 6.1 gap analysis document or checklist issue.

### Phase 1 — Foundation (3.0 alpha)

- [ ] TLS stack production-ready (workstream A).
- [ ] Introduce `Runtime` parallel to singleton (both work briefly).
- [ ] Define naming convention RFC (camelCase acronyms) in CONTRIBUTING.
- [ ] Closed `HandlerFactory` registry + one XML→builder proof (`examples/composition`).
- [ ] Extract **jprotobuf** codec jar; fix grpc/telemetry dependency direction (§E.1).

### Phase 2 — Servlet + modular container (3.0 beta)

- [ ] Servlet 6.1 implementation (workstream B).
- [ ] Container zip as primary distribution.
- [ ] JPMS modules stable.

### Phase 3 — Role-agnostic migration (3.0 RC)

- [ ] Rename `*Service` → `*Server` (application tier).
- [ ] Protocol package moves (`server/`, `client/`).
- [ ] Mass type renames (`HttpServer`, `AmqpClient`, …).
- [ ] Remove reflection DI; XML via registry only.
- [ ] Remove `Gumdrop.getInstance()` (or hard-deprecate with runtime-only path).

### Phase 4 — Polish (3.0 GA)

- [ ] Documentation and examples fully migrated.
- [ ] P2P guide and mesh-first policy published.
- [ ] Issues backlog triaged for 3.0 vs 3.1.

---

## Open questions (discussion)

1. **Top-level facade re-exports** — Option 1 vs 2 in §C.2?
2. **`Runtime` naming** — `Runtime`, `GumdropRuntime`, or `EventRuntime`?
3. **3.0 breaking change budget** — single rename flag day vs phased deprecations
   across 3.0 alphas?
4. **`gumdroprc` in 3.0** — supported via registry, or Java-only composition
   for 3.0 GA with XML returning in 3.1?
5. **Servlet module optional?** — embedders who never serve HTTP may omit
   servlet jar entirely (already directionally true with modular build).
6. **Legacy protocol tier** — which listeners remain in 3.0 default build vs
   `optional` modules (e.g. FTP, Telnet-era patterns)?
7. **jprotobuf repo** — separate repository vs published jar from monorepo (§E.1)?
8. **OTel API strategy** — native-only vs optional bridge vs OTel-primary (§E.2)?
9. **`GlobalOpenTelemetry`** — support global static registration or Runtime-only?

---

## Tracking

| Item | Status |
|------|--------|
| This plan | Draft |
| CHANGELOG 3.0.0 section | Draft (TLS/modularity) |
| TLS cert compression #445 | Spec refined |
| Role-agnostic refactor | Not started |
| Servlet 6.1 | Not started |
| Runtime introduction | Not started |
| Telemetry / jprotobuf spin-off | Not started |
| OTel API strategy decision | Open (§E.2) |

*Last updated: 2026-09-12*
