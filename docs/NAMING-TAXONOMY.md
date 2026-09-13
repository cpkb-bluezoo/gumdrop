# Gumdrop 3 naming taxonomy (workstream C.1)

Authoritative reference for public API names in Gumdrop 3. See
[CONTRIBUTING.md](../CONTRIBUTING.md#gumdrop-3-naming-conventions) for the
short rules; this document tracks the migration from Gumdrop 2.x names.

**Related:** [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C, [hopf](https://github.com/cpkb-bluezoo/hopf)
(reference architecture).

---

## Rules

### 1. CamelCase acronyms

Treat each protocol acronym as one word with **only the first letter capitalised**:

| Legacy | Gumdrop 3 |
|--------|-----------|
| `HTTP` | `Http` |
| `SMTP` | `Smtp` |
| `IMAP` | `Imap` |
| `POP3` | `Pop3` |
| `FTP` | `Ftp` |
| `DNS` | `Dns` |
| `DNSSEC` | `Dnssec` |
| `AMQP` | `Amqp` |
| `MQTT` | `Mqtt` |
| `SOCKS` | `Socks` |
| `MDNS` | `Mdns` |
| `GRPC` | `Grpc` |
| `WEBDAV` | **WebDAV** *(tradename — see below)* |
| `TLS` / `QUIC` / `UDP` / `TCP` | `Tls` / `Quic` / `Udp` / `Tcp` |

Examples: `HttpServer`, `SmtpClient`, `DnsMessage`, `Http3Listener`.
**WebDAV** keeps its tradename spelling in public types (`WebDAVRequestHandler`).

### 1.1 Tradenames and exceptions

| Name | Rule |
|------|------|
| **WebDAV** | Use **WebDAV**, not `Webdav`, in public API and documentation (RFC 4918 tradename). Interim `WebdavServer` from C.2.4 is deprecated; target is `WebDAVRequestHandler` on `HttpServer`. |
| **WebSocket** | One word: `WebSocketClient`, not `WebSocketClient` from `WEBSOCKET`. |

### 2. Application tier: `*Server`, not `*Service`

Types that own listeners plus handler wiring are **`{Protocol}Server`** facades
for **transport protocols** (e.g. `HttpServer`, `SmtpServer`), or — for HTTP
application stacks — a single **`HttpServer`** composed with a
**`{Feature}RequestHandler`** (e.g. `ServletRequestHandler`, `WebDAVRequestHandler`).
There is no `ServletServer` or `WebDAVServer` in the end state.

The top-level `org.bluezoo.gumdrop.Service` interface is **retired** (see
§C.4 in the plan); lifecycle moves to `Runtime` plus optional `start(rt)` on
facades.

**Gumdrop 3.0:** applications are wired in **Java composition** only — no
`gumdroprc` XML. See [COMPOSITION.md](COMPOSITION.md).

### 3. Client facades: `*Client`

Dial-side entry types use the same acronym rules: `HttpClient`, `SmtpClient`,
`AmqpClient`, etc.

### 4. Handler interfaces

| Role | Package (target, §C.2) | Name pattern |
|------|------------------------|--------------|
| **Stateless** server (HTTP, DNS) | `{protocol}.server` | `{Protocol}RequestHandler`, `{Protocol}QueryHandler` |
| **Stateful** server session minting | `{protocol}.server` | `{Protocol}ServerSessionProvider` extends `ServerSessionProvider` |
| **Stateful** server staged handlers | `{protocol}.server.handler` or `{protocol}.handler` | `{Stage}Handler` + `{Stage}State` |
| **Stateful** client session minting | `{protocol}.client` | `{Protocol}ClientSessionProvider` extends `ClientSessionProvider` |
| Client response / reply | `{protocol}.client.handler` | `{Stage}ReplyHandler` — **never** `Server*` |

**Stateless protocols do not use `ServerSessionProvider` or
`ClientSessionProvider`.** HTTP composes with `HttpRequestRouter` /
`HttpRequestHandler`; DNS with `DnsQueryHandler`.

**Stateful protocols** compose a session provider on the server (one pipeline
per accepted connection) and a client session provider (bootstrap handler per
dial). SMTP is the reference implementation (`SmtpServerSessionProvider`,
`SmtpClientSessionProvider`); FTP is planned next.

The `smtp.client.handler.ServerEhloReplyHandler` pattern is **legacy**: the
handler runs on the **client** and receives the **remote server's** reply. Rename
to `EhloReplyHandler` (package context supplies protocol). Similarly
`RemoteGreeting` → `RemoteGreeting`.

### 5. Listeners and transport

Keep `{Protocol}Listener` with camelCase acronyms: `Http2Listener`, `Http3Listener`,
`SmtpListener`, `DnsListener`.

Server TLS identity uses shared {@link org.bluezoo.gumdrop.tls.TlsConfig} on all
secure listeners ({@code listener.tls(tls)}). {@code HttpTlsConfig} and
{@code HttpListener} are deprecated aliases.

Configure listeners fluently ({@code new SmtpListener().port(25).bindWildcard().tls(tls)});
all protocol listeners support {@code port()}, {@code bindWildcard()},
{@code addresses(InetAddress...)}, {@code secure()}, and {@code tls()}. Nested
listener {@code Builder} types are deprecated. Multi-listener servers use
{@code compose()} ending in {@code server()} ({@link org.bluezoo.gumdrop.http.HttpServer},
{@link org.bluezoo.gumdrop.dns.server.DnsServer},
{@link org.bluezoo.gumdrop.smtp.server.SmtpServer}); {@code builder()} and
{@code build()} remain as deprecated aliases. Stateful clients (e.g.
{@link org.bluezoo.gumdrop.smtp.client.SmtpClient}) use fluent instance methods
before {@code connect()}; {@link org.bluezoo.gumdrop.dns.client.DnsResolver} uses
{@code server(InetAddress)} before {@code open()}.

### 6. Metrics

Move toward `{protocol}.server.ServerMetrics` (or neutral names in role
subpackages). Legacy `HttpServerMetrics` → `HttpServerMetrics` as an interim step.

### 7. Top-level facade re-exports (§C.2 — decided)

**Option 2:** primary **facade** types (`HttpServer`, `HttpClient`, …) are
re-exported at the protocol root package for ergonomics
(`org.bluezoo.gumdrop.http.HttpServer`) while implementation detail stays in
`server/` and `client/` subpackages. Use class `extends` re-exports for entry
types only — not handler interfaces (Java assignability).

---

## Migration slices (execution order)

Do renames in **vertical slices** on branch `v3-taxonomy`. Update
`test/junit/resources/gumdrop3-legacy-type-renames.properties` when each type
lands (remove its line so the guard test tracks remaining work).

| Slice | Scope | Notes |
|-------|--------|-------|
| **C.1.0** | Convention + inventory | CONTRIBUTING, this doc, guard test *(done)* |
| **C.1.1** | Core lifecycle names | `Server` contract; `Gumdrop` server registry *(done)* |
| **C.1.2** | HTTP stack | `HttpServer`, `HttpClient`, handlers, listeners, metrics; `http/server/` facade *(done)* |
| **C.1.3** | Servlet / WebDAV / WebSocket on HTTP | `ServletRequestHandler`, `WebDAVRequestHandler` **done** *(interim: `ServletServer`, `WebdavServer`)* |
| **C.1.4** | Mail protocols | SMTP, IMAP, POP3 servers, clients, client reply handlers *(done)* |
| **C.1.5** | Remaining protocols | FTP, DNS, MQTT, AMQP, SOCKS, mDNS, gRPC, health, transport types *(done)* |
| **C.1.6** | Internal / package-private | Lexers, protocol handlers, HPACK/QPACK, MIME/LDAP/JSP/RESP/OTLP *(done)* |
| **C.2.1** | HTTP facade layout | `HttpServer` and `HttpClient` at protocol root *(done)* |
| **C.2.5** | HTTP server SPI in `http/server/` | handlers, listeners, auth, metrics, `Stream` — symmetric to `http/client/` *(done)* |
| **C.2.6** | `HttpResponseState` in `http/server/` | server outbound response API; not client-facing *(done)* |
| **C.2.2** | Mail facade layout | SMTP/IMAP/POP3 `server/` facades + root `*Server`/`*Client` re-exports *(done)* |
| **C.2.3** | Remaining protocol `server/` facades | FTP, DNS, MQTT, SOCKS, mDNS, health + root re-exports *(done)* |
| **C.2.4** | Servlet / WebDAV / WebSocket package moves | Interim `*/server/*Server` facades — superseded by handler composition *(C.3)* |
| **C.3** | Handler-first HTTP | `HttpServer` + `HttpRequestHandler`; drop `HttpRequestHandlerFactory` public SPI |
| **C.3.1** | Session providers (stateful) | `ServerSessionProvider`, `ClientSessionProvider`; SMTP done; FTP next |
| **C.5** | Remove XML configuration | Java composition only; see [COMPOSITION.md](COMPOSITION.md) |

After **C.1.2**, begin **C.2** package moves (`http/server/`, `http/client/`) in
the same HTTP slice where practical.

---

## Application tier rename table

| Legacy (2.x) | Target (3.0) |
|--------------|--------------|
| `org.bluezoo.gumdrop.Service` | Deprecated; extends `Server` — use `Server` / `Gumdrop#addServer` |
| `HttpServer` | `HttpServer` |
| `ServletServer` | **`ServletRequestHandler`** on `HttpServer` *(interim: `ServletServer`)* |
| `WebDAVService` / `WebdavServer` | **`WebDAVRequestHandler`** on `HttpServer` *(interim: `WebdavServer`)* |
| `WebSocketServer` | `WebSocketRequestHandler` or dedicated listener stack *(TBD)* |
| `SmtpServer` | `SmtpServer` |
| `ImapServer` | `ImapServer` |
| `Pop3Server` | `Pop3Server` |
| `FtpServer` | `FtpServer` |
| `DnsServer` | `DnsServer` |
| `MqttServer` | `MqttServer` |
| `SocksServer` | `SocksServer` |
| `MdnsServer` | `MdnsServer` |
| `HealthServer` | `HealthServer` |
| `GrpcServer` | `GrpcServer` |

---

## SMTP client reply handlers

| Legacy | Target |
|--------|--------|
| `ReplyHandler` | `ReplyHandler` |
| `ServerEhloReplyHandler` | `EhloReplyHandler` |
| `ServerHeloReplyHandler` | `HeloReplyHandler` |
| `ServerStarttlsReplyHandler` | `StarttlsReplyHandler` |
| `ServerAuthReplyHandler` | `AuthReplyHandler` |
| `ServerAuthAbortHandler` | `AuthAbortHandler` |
| `ServerMailFromReplyHandler` | `MailFromReplyHandler` |
| `ServerRcptToReplyHandler` | `RcptToReplyHandler` |
| `ServerDataReplyHandler` | `DataReplyHandler` |
| `ServerMessageReplyHandler` | `MessageReplyHandler` |
| `ServerRsetReplyHandler` | `RsetReplyHandler` |
| `RemoteGreeting` | `RemoteGreeting` |

---

## Inventory and guard test

Every public top-level type in `src/org/bluezoo/gumdrop` that still uses a
legacy name must appear in:

`test/junit/resources/gumdrop3-legacy-type-renames.properties`

Format: `LegacyName=TargetName`

`Gumdrop3NamingConventionTest` fails if:

- a legacy-pattern public type is **missing** from the inventory (new debt), or
- the inventory lists a type that **no longer exists** (stale entry — remove after rename).

---

*Last updated: 2026-09-13*
