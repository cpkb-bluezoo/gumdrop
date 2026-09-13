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
| `WEBDAV` | `Webdav` |
| `TLS` / `QUIC` / `UDP` / `TCP` | `Tls` / `Quic` / `Udp` / `Tcp` |

Examples: `HTTPService` → `HttpServer`, `AMQPClient` → `AmqpClient`,
`DNSMessage` → `DnsMessage`, `Http3Listener` → `Http3Listener`.

### 2. Application tier: `*Server`, not `*Service`

Types that own listeners plus handler wiring are **`{Protocol}Server`** facades
(e.g. `HttpServer`, `SmtpServer`, `ServletServer`). They are **not** lifecycle
`Service` implementations in the J2EE sense.

The top-level `org.bluezoo.gumdrop.Service` interface is **retired** (see
§C.4 in the plan); lifecycle moves to `Runtime` plus optional `start(rt)` on
facades.

XML configuration will eventually use `<server>` (or composition builders) instead
of `<service class="…">`; until then, legacy XML class names remain valid during
migration.

### 3. Client facades: `*Client`

Dial-side entry types use the same acronym rules: `HttpClient`, `SmtpClient`,
`AmqpClient`, etc.

### 4. Handler interfaces

| Role | Package (target, §C.2) | Name pattern |
|------|------------------------|--------------|
| Server request handling | `{protocol}.server` | `{Protocol}RequestHandler`, staged server handlers |
| Client response / reply | `{protocol}.client` | `{Stage}ReplyHandler` — **never** `Server*` |

The `smtp.client.handler.ServerEhloReplyHandler` pattern is **legacy**: the
handler runs on the **client** and receives the **remote server's** reply. Rename
to `EhloReplyHandler` (package context supplies protocol). Similarly
`ServerGreeting` → `RemoteGreeting`.

### 5. Listeners and transport

Keep `{Protocol}Listener` with camelCase acronyms: `HttpListener`, `SmtpListener`,
`DnsListener`.

### 6. Metrics

Move toward `{protocol}.server.ServerMetrics` (or neutral names in role
subpackages). Legacy `HttpServerMetrics` → `HttpServerMetrics` as an interim step.

### 7. Top-level facade re-exports (§C.2 — decided)

**Option 2:** primary facades (`HttpServer`, `HttpClient`, …) are re-exported at
the protocol root package for ergonomics (`org.bluezoo.gumdrop.http.HttpServer`)
while implementation detail stays in `server/` and `client/` subpackages.

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
| **C.1.3** | Servlet / WebDAV / WebSocket on HTTP | `ServletServer`, `WebdavServer`, `WebSocketServer` *(done)* |
| **C.1.4** | Mail protocols | SMTP, IMAP, POP3 servers, clients, client reply handlers *(done)* |
| **C.1.5** | Remaining protocols | FTP, DNS, MQTT, AMQP, SOCKS, mDNS, gRPC, health, … |
| **C.1.6** | Internal / package-private | Lexers, protocol handlers, HPACK/QPACK (can follow public API) |

After **C.1.2**, begin **C.2** package moves (`http/server/`, `http/client/`) in
the same HTTP slice where practical.

---

## Application tier rename table

| Legacy (2.x) | Target (3.0) |
|--------------|--------------|
| `org.bluezoo.gumdrop.Service` | Deprecated; extends `Server` — use `Server` / `Gumdrop#addServer` |
| `HTTPService` | `HttpServer` |
| `ServletServer` | `ServletServer` |
| `WebdavServer` | `WebdavServer` |
| `WebSocketServer` | `WebSocketServer` *(WebSocket is one word)* |
| `SmtpServer` | `SmtpServer` |
| `ImapServer` | `ImapServer` |
| `Pop3Server` | `Pop3Server` |
| `FTPService` | `FtpServer` |
| `DNSService` | `DnsServer` |
| `MQTTService` | `MqttServer` |
| `SOCKSService` | `SocksServer` |
| `MDNSService` | `MdnsServer` |
| `HealthService` | `HealthServer` |
| `GrpcService` | `GrpcServer` |

---

## SMTP client reply handlers

| Legacy | Target |
|--------|--------|
| `ServerReplyHandler` | `ReplyHandler` |
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
| `ServerGreeting` | `RemoteGreeting` |

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
