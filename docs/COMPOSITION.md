# Application composition (Gumdrop 3)

**Canonical way to run Gumdrop.** Gumdrop 3 applications are assembled in Java:
listeners, **handler implementations**, and an explicit process entry point —
not XML configuration files or reflective dependency injection.

**Related:** [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C.3–C.5,
[hopf](https://github.com/cpkb-bluezoo/hopf) (reference architecture).

---

## Principles

1. **Handlers, not server subclasses** — implement protocol handler interfaces
   (`HttpRequestHandler`, staged SMTP handlers, `DnsQueryHandler`, …). Do not
   subclass `HttpServer`, `ServletServer`, or `WebdavServer` for application
   logic.
2. **One entry type per protocol** — e.g. `HttpServer` owns listeners and a
   single handler (or router/decorator chain). Servlet and WebDAV stacks attach
   as **`ServletRequestHandler`** and **`WebDAVRequestHandler`**
   (both done) on `HttpServer.compose()`.
3. **Default servers do nothing (application layer)** — a composed server with
   no handler wired must not silently pick up relay, upstream, or mailbox
   behaviour. “Do nothing” is protocol-specific (see [Default behaviour](#default-behaviour)).
4. **Stock handlers compose in one line** — relay, authoritative zones, servlet
   container, etc. are explicit handler implementations you attach when you want
   them (`new UpstreamRelayHandler(...)`, `new SimpleRelayHandler(...)`, …).
5. **Explicit runtime (target)** — [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C.4
   introduces `Runtime` to replace `Gumdrop.getInstance()`. Until then,
   examples use `Gumdrop` as the process entry point.
6. **No `gumdroprc` in 3.0** — XML configuration and `ComponentRegistry` are
   not supported for new deployments. Legacy XML may remain in `etc/` for
   regression tests only.

---

## Default behaviour

Unconfigured servers must be safe and predictable: the **transport and protocol
stack works**, but the **application layer answers empty / not-found**, never
implicit upstream relay or similar.

| Protocol | Default (no handler) | Unknown method / opcode |
|----------|----------------------|-------------------------|
| **HTTP** | **404 Not Found** for standard methods on any path | **501 Not Implemented** (HTTP layer) |
| **DNS** | **Empty answer** (NOERROR, zero RRs) — no upstream, no cache side effects | Appropriate REFUSED / NOTIMP where applicable |
| **SMTP** *(today)* | `SmtpServer` requires `openSession()` — no silent relay | Staged handler rejects at SMTP layer |

**Previously:** `DnsServer` always forwarded to upstream resolvers
(`useSystemResolvers=true` by default). **Now split:** default `DnsServer` uses
`DnsQueryHandlers.empty()`; **`UpstreamRelayHandler`** is an explicit one-line
composable stock implementation (like **`SimpleRelayHandler`** for SMTP).

---

## Stateful vs stateless composition

Gumdrop uses **two composition models**, depending on whether the protocol
maintains session state across many exchanges on one connection.

| | **Stateless** | **Stateful (session-based)** |
|---|---|---|
| **Examples** | HTTP, DNS | SMTP, FTP, IMAP, POP3, … |
| **Unit of work** | One request / one query | One control connection, many phases |
| **Server compose with** | Handler or router | **`ServerSessionProvider`** |
| **Client compose with** | Per-request / per-query API | **`ClientSessionProvider`** |
| **Per-event API** | `HttpRequestHandler`, `DnsQueryHandler` | Staged `{Stage}Handler` + `{Stage}State` |
| **Per connection** | Fresh handler per stream (HTTP) or shared handler (DNS) | Fresh **session pipeline** per accept / dial |

**Stateless protocols do not implement `ServerSessionProvider` or
`ClientSessionProvider`.** HTTP's deprecated `HttpRequestHandlerFactory` was
request **routing**, not session minting — do not conflate the two.

### Stateful server (SMTP reference)

```java
SmtpServer server = SmtpServer.compose()
        .listener(new SmtpListener().port(2525).bindWildcard())
        .sessionPerConnection(() -> new SimpleRelayHandler(...))
        .server();
gumdrop.addServer(server);
```

Each accept calls {@link org.bluezoo.gumdrop.ServerSessionProvider#openSession},
which returns the first stage of a **connection-private** staged handler pipeline
({@link org.bluezoo.gumdrop.smtp.handler.ClientConnected}, then {@code HelloHandler},
{@code MailFromHandler}, …).

Legacy {@code SimpleRelayServer} / {@code LocalDeliveryServer} subclasses remain
for XML configuration; new code should use {@link SmtpServer#compose()} as above.

### Stateful client (SMTP reference)

```java
SmtpClient client = new SmtpClient()
        .host("smtp.example.com")
        .port(587)
        .sessionPerConnection(() -> new MyRemoteGreeting());
client.connect();
```

{@link org.bluezoo.gumdrop.ClientSessionProvider#openSession} supplies the
bootstrap handler ({@link org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting}).
Passing {@code connect(RemoteGreeting)} directly remains supported for one-off use.

### FTP (planned)

FTP will adopt the same pattern ({@code FtpServerSessionProvider}, staged server
handlers, {@code FtpClientSessionProvider}) when its monolithic
{@code FtpConnectionHandler} is split — after SMTP shape is stable.

---

## Minimal HTTP server

Gumdrop’s default HTTP stack is **TLS + HTTP/3**, with HTTPS on TCP for
HTTP/2 and HTTP/1.1 compatibility. Plaintext HTTP/1.1 is a **legacy
fallback** — use it only for local development or backward compatibility.

```java
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.http.Headers;

import java.nio.file.Path;

public final class EchoMain {
    public static void main(String[] args) throws Exception {
        Gumdrop gumdrop = Gumdrop.getInstance();

        HttpServer server = HttpServer.compose()
                .secureEndpoint(443, TlsConfig.pem(
                        Path.of("cert.pem"), Path.of("key.pem")))
                .handler(new EchoHandler())
                .server();

        gumdrop.addServer(server);
        gumdrop.start();
        gumdrop.join();
    }

    /** Stateless — safe with {@code .handler(...)}. */
    private static final class EchoHandler implements HttpRequestHandler {
        @Override
        public void headers(HttpResponseState state, Headers headers) {
            Headers response = new Headers();
            response.add(":status", "200");
            response.add("content-type", "text/plain");
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(
                    java.nio.ByteBuffer.wrap("ok\n".getBytes()));
            state.endResponseBody();
            state.complete();
        }
    }
}
```

`secureEndpoint(port, tls)` adds **both**:

| Transport | Listener | Protocols |
|-----------|----------|-----------|
| TCP (TLS) | `Http2Listener` | HTTP/2, HTTP/1.1 |
| UDP (QUIC) | `Http3Listener` | HTTP/3 |

TCP responses include **`Alt-Svc`** so clients can upgrade to HTTP/3.
The same `TlsConfig` (PEM, keystore, or `ServerCredentials`) is applied
to both listeners.

Keystore form:

```java
TlsConfig tls = TlsConfig.keystore(
        Path.of("server.p12"), "changeit");
HttpServer.compose().secureEndpoint(443, tls) …
```

### Legacy plaintext fallback

Cleartext HTTP/1.1 on a separate port (not the default pattern):

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .plaintextListener(8080)   // legacy / dev only
        .handler(new EchoHandler())
        .server();
```

Advanced: wire listeners individually when ports or TLS material differ:

```java
TlsConfig tls = TlsConfig.pem(Path.of("cert.pem"), Path.of("key.pem"));

Http2Listener h2 = new Http2Listener()
        .port(443)
        .bindWildcard()
        .secure(true)
        .tls(tls);

Http3Listener h3 = new Http3Listener()
        .port(443)
        .bindWildcard()
        .tls(tls);

HttpServer server = HttpServer.compose()
        .listener(h2)
        .listener(h3)
        .handler(new EchoHandler())
        .server();
```

### Handler-less HTTP server

Omitting `.handler()` / `.router()` installs the default
**`NotFoundHttpRequestHandler`** (`HttpRequestHandlers.notFound()`): valid HTTP,
every request **404**.

```java
HttpServer empty = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .server();
```

### Per-request handlers and routing

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .handlerPerRequest(MyHandler::new)   // fresh instance per request
        .server();

HttpRequestRouter router = (state, headers) -> {
    if (headers.getPath().startsWith("/api/")) {
        return new ApiHandler();
    }
    return null;   // → 404
};

HttpServer routed = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .router(router)
        .server();
```

Legacy {@link HttpRequestHandlerFactory} implementations can migrate with
{@link HttpRequestHandlers#fromFactory(HttpRequestHandlerFactory)}; new code
should implement {@link HttpRequestRouter} directly.

---

## Servlet container

**`ServletRequestHandler`** on `HttpServer`, not a separate `ServletServer` type.
The handler is constructed with a **`Container`** that owns servlet lifecycle
(worker pool, async timeouts, context init, authentication wiring):

```java
Container container = new Container();
container.addContext(new Context(container, "/app", appRoot));

HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .router(new ServletRequestHandler(container))
        .server();
```

`ServletRequestHandler` implements {@link HttpServerServiceHook}; composed
servers call {@link Container#start()} / {@link Container#destroy()} automatically.

`ServletServer` remains for XML configuration; new code should compose
`ServletRequestHandler` on `HttpServer.compose()` as above.

---

## File server and WebDAV

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .router(WebDAVRequestHandler.builder()
                .rootPath(Path.of("/var/www/html"))
                .webdavEnabled(true)
                .server())
        .server();
```

`WebdavServer` remains for XML configuration; new code should compose
`WebDAVRequestHandler` on `HttpServer.compose()` as above.

---

## DNS *(target — C.3)*

Split today's monolithic `DnsServer` (implicit upstream relay) into:

| Piece | Role |
|-------|------|
| **`DnsServer`** | Listeners + dispatch only; default = empty answers |
| **`UpstreamRelayHandler`** | Today's forwarder / cache / `proxyToUpstream` logic |
| **`AuthoritativeZoneHandler`** | Answers from zone file(s); AA bit set |

Composition (target API):

```java
// Default: speaks DNS, returns empty results
DnsServer dns = DnsServer.compose()
        .listener(new DnsListener().port(53).bindWildcard())
        .server();

// One-line relay (replaces implicit upstream default)
DnsServer relay = DnsServer.compose()
        .listener(new DnsListener().port(53).bindWildcard())
        .handler(new UpstreamRelayHandler(
                UpstreamRelayHandler.builder()
                        .servers("8.8.8.8", "1.1.1.1")
                        .cacheEnabled(true)
                        .build()))
        .server();

// Authoritative zone from file
DnsServer auth = DnsServer.compose()
        .listener(new DnsListener().port(53).bindWildcard())
        .handler(new AuthoritativeZoneHandler(
                ZoneFile.load(Path.of("/etc/named/example.com.zone"))))
        .server();
```

Same pattern as SMTP (stateful — session provider, not query handler):

```java
// MX relay — stock handler pipeline, explicit wiring
SmtpServer relay = new SimpleRelayServer();
relay.addListener(new SmtpListener());
relay.setHostname("relay.example.com");
// → openSession() returns new SimpleRelayHandler(...) per connection

// Local mailbox delivery — different stock pipeline
SmtpServer mbox = new LocalDeliveryServer();
mbox.addListener(new SmtpListener());
// → openSession() returns new LocalDeliveryHandler(...)
```

*(The DNS examples above use stateless `DnsQueryHandler` — no session provider.)*

---

## Cross-cutting concerns

Wrap handlers instead of subclassing servers or factories:

```java
HttpRequestHandler app = new ServletRequestHandler(container);
HttpRequestHandler withAuth = BasicAuthHandler.decorate(app, realm);
HttpRequestHandler withTelemetry = TelemetryHandler.decorate(withAuth, config);

HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .handler(withTelemetry)
        .server();
```

Hopf’s `BasicAuthFactory`-style pattern becomes **handler decorators**, not a
separate factory interface layer.

---

## Mail, FTP, and other stateful protocols

**SMTP** is the reference for session-based composition: {@code SmtpServer}
implements {@code SmtpServerSessionProvider}; the client uses
{@code SmtpClientSessionProvider} / staged {@code *ReplyHandler} interfaces.
**FTP** will follow after its server SPI is restaged. **IMAP** and **POP3**
already use staged server handlers and will gain explicit session-provider
interfaces in a later slice.

---

## Naming exceptions

CamelCase acronym rules ([NAMING-TAXONOMY.md](NAMING-TAXONOMY.md)) apply to
most protocols (`Http`, `Smtp`, `Dns`, …). **Exceptions:**

| Name | Rule |
|------|------|
| **WebDAV** | Tradename — `WebDAVRequestHandler`, `WebDAV*`, not `Webdav*` |
| **WebSocket** | One word — `WebSocketClient`, `WebSocketRequestHandler`, … |

Interim types (`ServletServer`, `WebdavServer`) are **temporary**.

---

## Migration from 2.x

| Gumdrop 2.x | Gumdrop 3 target |
|-------------|------------------|
| `gumdroprc.xml` + `ComponentRegistry` | Java composition (this document) |
| `<service class="…ServletServer">` | `HttpServer` + `ServletRequestHandler` |
| `<service class="…WebDAVService">` | `HttpServer` + `WebDAVRequestHandler` |
| `HttpRequestHandlerFactory` for routing | `HttpRequestRouter` / handler on `HttpServer` |
| `SmtpServer#createHandler()` | `SmtpServer#openSession()` / `SmtpServerSessionProvider` |
| `DnsServer` with implicit upstream | `DnsServer` + `UpstreamRelayHandler` |
| Subclass `*Server` for app logic | Handler interfaces + composition |

---

## Documentation map

| Audience | Location |
|----------|----------|
| Plan and sequencing | [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C.3–C.5 |
| Naming rules | [NAMING-TAXONOMY.md](NAMING-TAXONOMY.md) |
| Web docs | [web/configuration.html](../web/configuration.html) |
| Examples | `examples/*` — Java `main` entry points |

---

## Implementation status (C.3)

| Item | Status |
|------|--------|
| `HttpServer.compose()`, `secureEndpoint()`, `TlsConfig` | **Done** |
| Fluent listeners (`SmtpListener`, `FtpListener`, …) | **Done** |
| `Http2Listener.builder()`, `Http3Listener.builder()` | **Deprecated** |
| `HttpRequestRouter`, `HttpRequestHandlers`, default 404 | **Done** |
| `ServletRequestHandler`, `WebDAVRequestHandler` | **Done** |
| `DnsServer.compose()`, `DnsQueryHandler`, default empty answers | **Done** |
| `SmtpServer.compose()`, fluent `SmtpClient`, `DnsResolver.server()` | **Done** |
| `UpstreamRelayHandler`, `AuthoritativeZoneHandler`, `ZoneFile` | **Done** |
| `ServerSessionProvider`, `ClientSessionProvider`; SMTP session SPI | **Done** (SMTP); FTP planned |
| `Runtime` replaces `Gumdrop.getInstance()` | Planned (§C.4) |
