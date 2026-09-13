# Application composition (Gumdrop 3)

**Canonical way to run Gumdrop.** Gumdrop 3 applications are assembled in Java:
an explicit `Runtime`, protocol listeners, and **handler implementations** —
not XML configuration files or reflective dependency injection.

**Related:** [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C.3–C.5,
[hopf](https://github.com/cpkb-bluezoo/hopf) (reference architecture).

---

## Principles

1. **Handlers, not server subclasses** — implement `HttpRequestHandler` (or
   protocol-specific staged handlers for SMTP, IMAP, etc.). Do not subclass
   `HttpServer`, `ServletServer`, or `WebDAVServer` for application logic.
2. **One HTTP entry type** — `org.bluezoo.gumdrop.http.HttpServer` owns
   listeners and a single handler (or router/decorator chain). Servlet and
   WebDAV stacks attach as **`ServletRequestHandler`** and
   **`WebDAVRequestHandler`**, both implementing `HttpRequestHandler`.
3. **Explicit runtime** — create a `Runtime`, register servers and clients,
   call `start()`. No process-wide singleton in application code.
4. **No `gumdroprc` in 3.0** — XML configuration and `ComponentRegistry`
   are removed for the migration period. Examples and web documentation
   show composition only. A future declarative format is not planned unless
   there is a concrete design; do not maintain parallel XML while the Java
   API is still moving.

---

## Minimal HTTP server

```java
import org.bluezoo.gumdrop.Runtime;
import org.bluezoo.gumdrop.RuntimeConfig;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.HttpListener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.Headers;

public final class EchoMain {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.start(RuntimeConfig.builder().build());

        HttpServer server = HttpServer.builder()
                .listener(HttpListener.builder().port(8080).build())
                .handler(new EchoHandler())
                .build();

        rt.addServer(server);
        server.start(rt);
        rt.awaitShutdown();
    }

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

*(Builder APIs above are the **target** surface from workstream C.3; until they
land, the same structure applies using today's constructors and listener wiring
— see [Migration from 2.x](#migration-from-2x).)*

---

## Servlet container

The servlet stack is a **`ServletRequestHandler`** wired into `HttpServer`, not
a separate `ServletServer` type:

```java
Container container = Container.builder()
        .context(Context.builder()
                .path("")
                .root(Path.of("web"))
                .build())
        .build();

HttpServer server = HttpServer.builder()
        .listener(HttpListener.builder().port(8080).build())
        .listener(Http3Listener.builder().port(8443).tls(credentials).build())
        .handler(new ServletRequestHandler(container))
        .build();
```

`ServletRequestHandler` owns the servlet container lifecycle (contexts, filters,
JSP, security constraints). Multiple contexts, realms, and clustering options
are constructor/builder parameters on the handler or `Container`, not XML
properties on a `ServletServer` service.

---

## File server and WebDAV

Static files and RFC 4918 authoring use **`WebDAVRequestHandler`** (note
**WebDAV** — tradename; see [Naming exceptions](#naming-exceptions)):

```java
HttpServer server = HttpServer.builder()
        .listener(HttpListener.builder().port(8080).build())
        .handler(WebDAVRequestHandler.builder()
                .rootPath(Path.of("/var/www/html"))
                .welcomeFile("index.html")
                .allowWrite(false)
                .webdavEnabled(true)
                .build())
        .build();
```

There is no `WebDAVServer` / `WebdavServer` in the end state — only `HttpServer`
plus this handler.

---

## Cross-cutting concerns

Wrap handlers instead of subclassing servers or factories:

```java
HttpRequestHandler app = new ServletRequestHandler(container);
HttpRequestHandler withAuth = BasicAuthHandler.decorate(app, realm);
HttpRequestHandler withTelemetry = TelemetryHandler.decorate(withAuth, config);

HttpServer server = HttpServer.builder()
        .listener(...)
        .handler(withTelemetry)
        .build();
```

Hopf’s `BasicAuthFactory`-style pattern becomes **handler decorators**, not a
separate factory interface layer.

---

## Mail, FTP, and other protocols

Same model: **`SmtpServer`**, **`ImapServer`**, etc. own listeners and accept
handler implementations or staged reply handlers on the client side. Complex
stacks (mailbox + local delivery + SMTP) are wired in one Java `main` or test
harness — see hopf’s `LocalDeliveryServer` composition notes.

---

## Naming exceptions

CamelCase acronym rules ([NAMING-TAXONOMY.md](NAMING-TAXONOMY.md)) apply to
most protocols (`Http`, `Smtp`, `Dns`, …). **Exceptions:**

| Name | Rule |
|------|------|
| **WebDAV** | Tradename — use `WebDAVRequestHandler`, `WebDAV*` types, not `Webdav*` |
| **WebSocket** | One word — `WebSocketClient`, `WebSocketRequestHandler`, … |

Interim types from package migration (`ServletServer`, `WebdavServer`) are
**temporary**; documentation and new code should target handler composition.

---

## Migration from 2.x

| Gumdrop 2.x | Gumdrop 3 target |
|-------------|------------------|
| `gumdroprc.xml` + `ComponentRegistry` | Java composition (this document) |
| `<service class="…ServletServer">` | `HttpServer` + `ServletRequestHandler` |
| `<service class="…WebDAVService">` | `HttpServer` + `WebDAVRequestHandler` |
| `HttpRequestHandlerFactory` for routing | Handler or small router object on `HttpServer` |
| Subclass `*Server` for app logic | Implement handler interfaces |

During the transition branch, legacy XML may still exist in `etc/` for
regression tests, but it is **not** documented as supported for new deployments.

---

## Documentation map

| Audience | Location |
|----------|----------|
| Plan and sequencing | [GUMDROP-3-PLAN.md](GUMDROP-3-PLAN.md) §C.3–C.5 |
| Naming rules | [NAMING-TAXONOMY.md](NAMING-TAXONOMY.md) |
| Web docs | [web/configuration.html](../web/configuration.html) (composition) |
| Examples | `examples/*` — Java `main` entry points (being migrated) |
