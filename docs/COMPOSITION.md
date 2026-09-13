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
   (both done) on `HttpServer.builder()`.
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
| **SMTP** *(today)* | `SmtpServer` requires `createHandler()` — no silent relay | Staged handler rejects at SMTP layer |

**Previously:** `DnsServer` always forwarded to upstream resolvers
(`useSystemResolvers=true` by default). **Now split:** default `DnsServer` uses
`DnsQueryHandlers.empty()`; **`UpstreamRelayHandler`** is an explicit one-line
composable stock implementation (like **`SimpleRelayHandler`** for SMTP).

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
import org.bluezoo.gumdrop.http.server.HttpTlsConfig;
import org.bluezoo.gumdrop.http.Headers;

import java.nio.file.Path;

public final class EchoMain {
    public static void main(String[] args) throws Exception {
        Gumdrop gumdrop = Gumdrop.getInstance();

        HttpServer server = HttpServer.builder()
                .secureEndpoint(443, HttpTlsConfig.pem(
                        Path.of("cert.pem"), Path.of("key.pem")))
                .handler(new EchoHandler())
                .build();

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
| TCP (TLS) | `HttpListener` | HTTP/2, HTTP/1.1 |
| UDP (QUIC) | `Http3Listener` | HTTP/3 |

TCP responses include **`Alt-Svc`** so clients can upgrade to HTTP/3.
The same `HttpTlsConfig` (PEM, keystore, or `ServerCredentials`) is applied
to both listeners.

Keystore form:

```java
HttpTlsConfig tls = HttpTlsConfig.keystore(
        Path.of("server.p12"), "changeit");
HttpServer.builder().secureEndpoint(443, tls) …
```

### Legacy plaintext fallback

Cleartext HTTP/1.1 on a separate port (not the default pattern):

```java
HttpServer server = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .plaintextListener(8080)   // legacy / dev only
        .handler(new EchoHandler())
        .build();
```

Advanced: wire listeners individually with
`HttpListener.builder()` / `Http3Listener.builder()` when ports or TLS
material differ per transport.

### Handler-less HTTP server

Omitting `.handler()` / `.router()` installs the default
**`NotFoundHttpRequestHandler`** (`HttpRequestHandlers.notFound()`): valid HTTP,
every request **404**.

```java
HttpServer empty = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .build();
```

### Per-request handlers and routing

```java
HttpServer server = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .handlerPerRequest(MyHandler::new)   // fresh instance per request
        .build();

HttpRequestRouter router = (state, headers) -> {
    if (headers.getPath().startsWith("/api/")) {
        return new ApiHandler();
    }
    return null;   // → 404
};

HttpServer routed = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .router(router)
        .build();
```

Legacy `HttpRequestHandlerFactory` code can bridge via
`HttpRequestHandlers.fromFactory(factory)`.

---

## Servlet container

**`ServletRequestHandler`** on `HttpServer`, not a separate `ServletServer` type.
The handler is constructed with a **`Container`** that owns servlet lifecycle
(worker pool, async timeouts, context init, authentication wiring):

```java
Container container = new Container();
container.addContext(new Context(container, "/app", appRoot));

HttpServer server = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .router(new ServletRequestHandler(container))
        .build();
```

`ServletRequestHandler` implements {@link HttpServerServiceHook}; composed
servers call {@link Container#start()} / {@link Container#destroy()} automatically.

`ServletServer` remains for XML configuration; new code should compose
`ServletRequestHandler` on `HttpServer.builder()` as above.

---

## File server and WebDAV

```java
HttpServer server = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .router(WebDAVRequestHandler.builder()
                .rootPath(Path.of("/var/www/html"))
                .webdavEnabled(true)
                .build())
        .build();
```

`WebdavServer` remains for XML configuration; new code should compose
`WebDAVRequestHandler` on `HttpServer.builder()` as above.

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
DnsServer dns = DnsServer.builder()
        .listener(DnsListener.builder().port(53).build())
        .build();

// One-line relay (replaces implicit upstream default)
DnsServer relay = DnsServer.builder()
        .listener(DnsListener.builder().port(53).build())
        .handler(new UpstreamRelayHandler(
                UpstreamRelayHandler.builder()
                        .servers("8.8.8.8", "1.1.1.1")
                        .cacheEnabled(true)
                        .build()))
        .build();

// Authoritative zone from file
DnsServer auth = DnsServer.builder()
        .listener(DnsListener.builder().port(53).build())
        .handler(new AuthoritativeZoneHandler(
                ZoneFile.load(Path.of("/etc/named/example.com.zone"))))
        .build();
```

Same pattern as SMTP:

```java
// MX relay — stock handler, explicit wiring
SmtpServer relay = new SimpleRelayServer();
relay.addListener(new SmtpListener());
relay.setHostname("relay.example.com");
// → createHandler() returns new SimpleRelayHandler(...)

// Local mailbox delivery — different stock handler
SmtpServer mbox = new LocalDeliveryServer();
mbox.addListener(new SmtpListener());
// → createHandler() returns new LocalDeliveryHandler(...)
```

---

## Cross-cutting concerns

Wrap handlers instead of subclassing servers or factories:

```java
HttpRequestHandler app = new ServletRequestHandler(container);
HttpRequestHandler withAuth = BasicAuthHandler.decorate(app, realm);
HttpRequestHandler withTelemetry = TelemetryHandler.decorate(withAuth, config);

HttpServer server = HttpServer.builder()
        .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
        .handler(withTelemetry)
        .build();
```

Hopf’s `BasicAuthFactory`-style pattern becomes **handler decorators**, not a
separate factory interface layer.

---

## Mail, FTP, and other protocols

Same model over time: protocol `*Server` owns listeners; application logic lives
in handler implementations or staged reply handlers on the client side. SMTP
(`SimpleRelayHandler`, `LocalDeliveryHandler`) is the reference split; DNS and
HTTP are catching up.

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
| `HttpServer.builder()`, `secureEndpoint()`, `HttpTlsConfig` | **Done** |
| `HttpListener.builder()`, `Http3Listener.builder()` | **Done** |
| `HttpRequestRouter`, `HttpRequestHandlers`, default 404 | **Done** |
| `ServletRequestHandler`, `WebDAVRequestHandler` | **Done** |
| `DnsServer.builder()`, `DnsQueryHandler`, default empty answers | **Done** |
| `UpstreamRelayHandler`, `AuthoritativeZoneHandler`, `ZoneFile` | **Done** |
| `Runtime` replaces `Gumdrop.getInstance()` | Planned (§C.4) |
