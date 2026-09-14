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
2. **One entry type per protocol** — e.g. `HttpServer` owns listeners and an
   **`HttpStreamHandler`** that binds a fresh **`HttpRequestHandler`** per
   stream. Servlet and WebDAV stacks attach as **`ServletRequestHandler`** and
   **`WebDAVRequestHandler`** (both done) on `HttpServer.compose()`.
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
| **IMAP** / **POP3** | Protocol stack and **CAPABILITY** / greeting work; no mailbox backing — SELECT/LIST/AUTH fail with protocol errors | Staged handler / protocol layer |
| **FTP** *(empty)* | {@code 220} greeting; USER/PASS without session provider → login failure | Protocol layer |

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
| **Server compose with** | `HttpStreamHandler` | **`ServerSessionProvider`** |
| **Client compose with** | Per-request / per-query API | **`ClientSessionProvider`** |
| **Per-event API** | `HttpRequestHandler`, `DnsQueryHandler` | Staged `{Stage}Handler` + `{Stage}State` |
| **Per connection** | Fresh handler per stream (HTTP) or shared handler (DNS) | Fresh **session pipeline** per accept / dial |

**Stateless protocols do not implement `ServerSessionProvider` or
`ClientSessionProvider`.** HTTP binds one {@link HttpRequestHandler} per stream
via {@link org.bluezoo.gumdrop.http.server.HttpStreamHandler}; routing and method
policy belong in the handler (typically via delegation), not in the stream
binder. Do not conflate this with session minting on SMTP/IMAP/FTP.

### Stateful server (SMTP reference)

```java
SmtpServer server = SmtpServer.compose()
        .listener(new SmtpListener().port(2525).bindWildcard())
        .sessionProvider(SmtpServerSessionProviders.relay()
                .hostname("relay.example.com"))
        .server();
gumdrop.addServer(server);
```

Each accept calls {@link org.bluezoo.gumdrop.ServerSessionProvider#openSession},
which returns the first stage of a **connection-private** staged handler pipeline
({@link org.bluezoo.gumdrop.smtp.handler.ClientConnected}, then {@code HelloHandler},
{@code MailFromHandler}, …). The session provider may also be set on the listener
({@code new SmtpListener().sessionProvider(...)}); {@link SmtpServer#start()} wires
it on the accept path.

Stock providers: {@link org.bluezoo.gumdrop.smtp.server.SimpleRelaySessionProvider}
(MX relay) and {@link org.bluezoo.gumdrop.smtp.server.LocalDeliverySessionProvider}
(local mailbox delivery). Legacy {@code SimpleRelayServer} /
{@code LocalDeliveryServer} subclasses delegate to these providers for XML
configuration.

### Client dial (all outbound clients)

Every outbound client — mail, FTP, **HTTP**, **WebSocket**, CONNECT, MQTT, … —
must configure **where and how to connect** before `connect()`. That dial axis is
the same everywhere; only the protocol-specific extras differ.

| Dial setting | Purpose |
|--------------|---------|
| `host` / `host(InetAddress)` / `socketPath` | Target (hostname defers DNS until connect) |
| `port` | TCP/UDP port (or `-1` for UNIX socket) |
| **TLS** | {@link org.bluezoo.gumdrop.tls.ClientTlsConfig}: `secure`, `trustJvm()`, client identity, trust verification — merged with {@link org.bluezoo.gumdrop.client.ClientDefaults} until `Runtime` §C.4 |
| **DNS** | Hostname → address via {@link org.bluezoo.gumdrop.dns.client.DnsResolver}; optional protocol lookups (SMTP TLSA/DANE, HTTP/HTTPS/SRV records, …) |

**Interim process defaults ({@link org.bluezoo.gumdrop.client.ClientDefaults}):** until `Runtime` §C.4
lands, use {@link org.bluezoo.gumdrop.client.ClientDefaults#setDefaultTls} for process-wide
TLS material and rely on {@link org.bluezoo.gumdrop.client.ClientDefaults#dnsResolver} /
{@link org.bluezoo.gumdrop.dns.client.DnsResolver#forLoop} for DNS.

**TLS gating:** without explicit {@link org.bluezoo.gumdrop.tls.ClientTlsConfig} material on
the client *or* in {@link org.bluezoo.gumdrop.client.ClientDefaults}, connections are
**plaintext only**. {@code secure(true)} alone does not enable TLS — callers must also
supply trust or identity material (typically {@code .trustJvm()} for public CAs, or
{@code trustManager}, keystore, PEM paths, {@code clientCredentials}, …). STARTTLS /
STLS / AUTH TLS upgrades require the same material ({@link
org.bluezoo.gumdrop.tls.ClientTlsConfig#allowsTlsUpgrade()}).

**DNS default chain:** per-client {@code dnsResolver(...)} wins; otherwise {@link
org.bluezoo.gumdrop.dns.client.DnsResolver#forLoop} parses {@code resolv.conf}. When no
usable system nameservers remain, fallbacks are **Cloudflare → Quad9 → Google** (matching
DoQ → DoT → UDP preference — Google Public DNS has no DoQ).

**Implementation status:** all outbound facades use {@link
org.bluezoo.gumdrop.client.ClientDial} and/or {@link org.bluezoo.gumdrop.tls.ClientTlsConfig}
internally (mail, FTP, HTTP, WebSocket, Redis, LDAP, MQTT). Fluent {@code host()} /
{@code port()} / {@code socketPath()} / {@code dnsResolver()} / {@code trustJvm()} are
exposed on the high-traffic clients; CONNECT-IP/UDP and gRPC still mirror the same TLS
fields directly (migration optional — see below).

Protocol-specific dial extras (not duplicated on mail clients):

| Client | Extra dial / transport knobs |
|--------|------------------------------|
| **HttpClient** | HTTP/2, HTTP/3, Alt-Svc, HTTPS DNS record, connection pool, `blockPrivateAddresses` |
| **WebSocketClient** | Subprotocol, extensions, HTTP/3 WebSocket (`:protocol`), delegates to {@link org.bluezoo.gumdrop.http.HttpClient} for QUIC path |
| **ConnectIpClient** / **ConnectUdpClient** | Proxy **host:port** dial (not the tunneled target); RFC 9484 / RFC 9298 capsule session after Extended CONNECT |
| **GrpcClient** | No dial of its own — unary/streaming RPC over an already-configured {@link org.bluezoo.gumdrop.http.HttpClient} |

### Session composition (stateful mail/FTP)

Stateful mail and FTP clients have a **second** axis — which handler runs after
the transport is up:

| Axis | Configure before `connect()` | Purpose |
|------|------------------------------|---------|
| **Dial** | see above | Open the transport |
| **Session** | `{Protocol}ClientSessionProvider` or `sessionPerConnection(...)` | Bootstrap handler ({@link org.bluezoo.gumdrop.smtp.client.handler.RemoteGreeting}, …) |

Session providers apply to SMTP, IMAP, POP3, and FTP only. **HTTP and WebSocket do
not use `{Protocol}ClientSessionProvider`:** they are request- or handshake-oriented.
Pass {@code connect(HttpClientHandler)}, {@code connect(path, WebSocketEventHandler)},
or issue requests after connect — application logic lives in those handlers or per
request, not in a mail-style session provider.

Dial settings are required on every client. Session providers are required only
when calling parameterless `connect()` on mail/FTP clients;
`connect(RemoteGreeting)` remains valid for one-off use.

```java
SmtpClient client = new SmtpClient()
        .host("smtp.example.com")
        .port(587)
        .sessionPerConnection(() -> new MyRemoteGreeting());
client.connect();

HttpClient http = new HttpClient("api.example.com", 443)
        .secure(true)
        .trustJvm()
        .credentials("alice", "secret");   // HTTP-layer auth, not session SPI
http.connect(handler);

WebSocketClient ws = new WebSocketClient("echo.example.com", 443)
        .secure(true)
        .trustJvm();
ws.connect("/ws", eventHandler);
```

### Client authentication (cross-cutting)

“Client auth” spans three layers. Only the first belongs on the shared dial /
`Runtime` defaults axis; the others stay in handlers or HTTP-specific APIs.

| Layer | What it proves | Where it belongs today | Runtime default? |
|-------|----------------|------------------------|------------------|
| **1. Transport (TLS)** | Client certificate to the TLS stack (mTLS); which CAs to trust | {@link org.bluezoo.gumdrop.tls.ClientTlsConfig} on each facade (+ {@link org.bluezoo.gumdrop.client.ClientDefaults}) | **Yes** — target `Runtime.clientTls()` (name TBD): identity + trust when not set per client |
| **2. Application (protocol)** | Username/password, tokens, SASL mechanisms after connect | Staged client handlers: SMTP `AUTH`, IMAP `LOGIN`, FTP `USER`/`PASS`, POP3 `USER`/`PASS`, MQTT connect credentials | **No** — protocol-specific; stays in `{Protocol}ClientSessionProvider` handlers |
| **3. HTTP application** | `Authorization` on requests (Basic, Digest, Bearer, …) | {@link org.bluezoo.gumdrop.http.HttpClient#credentials(String, String)} → {@code Authorization} / {@code Proxy-Authorization} on requests | Optional future: default credentials on `Runtime` for outbound HTTP only |

**Naming note:** {@link org.bluezoo.gumdrop.tls.ServerCredentials} on a **client**
means “this client's own TLS identity” (cert chain + key to present if the server
requests mTLS), not server-side listener identity. All outbound facades funnel transport
TLS through {@link org.bluezoo.gumdrop.tls.ClientTlsConfig}; facades still expose the
same fluent setters ({@code secure}, {@code trustJvm}, {@code clientCredentials}, …)
as thin delegates.

#### WebSocket upgrade authentication (spec)

WebSocket opens with an HTTP request (RFC 6455 upgrade, RFC 8441 Extended CONNECT, or
RFC 9220 HTTP/3 CONNECT). **Transport TLS** ({@link org.bluezoo.gumdrop.tls.ClientTlsConfig})
is orthogonal to **handshake HTTP credentials**:

| Mechanism | Header / field | Layer | Status |
|-----------|------------------|-------|--------|
| Basic / Bearer / custom | {@code Authorization} on the upgrade request | HTTP application (layer 3) | **HttpClient** only — {@link org.bluezoo.gumdrop.http.HttpClient#credentials} or per-request headers via {@link org.bluezoo.gumdrop.http.HttpClient#connectWebSocket} |
| Cookie | {@code Cookie} on the upgrade request | HTTP application | Caller-supplied headers on {@code connectWebSocket} |
| mTLS | Client cert in TLS handshake | Transport (layer 1) | {@code clientCredentials} / keystore on {@link org.bluezoo.gumdrop.websocket.client.WebSocketClient} or {@link org.bluezoo.gumdrop.http.HttpClient} |

**{@link org.bluezoo.gumdrop.websocket.client.WebSocketClient}** today sets only
WebSocket-specific headers ({@code Sec-WebSocket-Key}, {@code Sec-WebSocket-Protocol},
extension offers). It does **not** yet offer {@code credentials()} or {@code
upgradeHeader(name, value)} helpers — authenticated handshakes should use {@link
org.bluezoo.gumdrop.http.HttpClient#connectWebSocket} (which can attach {@code
Authorization}) or a future {@code WebSocketClient.upgradeHeaders(...)} API.

#### CONNECT-IP, CONNECT-UDP, and gRPC (dial vs application)

These clients share the **same transport dial** as {@link org.bluezoo.gumdrop.http.HttpClient}
(host, port, TLS, DNS) because the tunnel or RPC rides HTTP:

```
ConnectIpClient / ConnectUdpClient          GrpcClient
        |                                        |
        |  dial: proxy host:port + TLS           |  no dial — takes HttpClient
        v                                        v
   Extended CONNECT request              POST + application/grpc
        |                                        |
        v                                        v
 ConnectIpClientSession /              ProtoMessageHandler /
 ConnectUdpClientSession               GrpcEventHandler
```

- **ConnectIpClient** / **ConnectUdpClient**: dial targets the **proxy**, not the final
  IP/UDP endpoint. After connect, {@code ConnectIpTarget} / UDP target selectors choose
  what the proxy opens. Transport negotiation (HTTPS record, Alt-Svc, ALPN) mirrors
  {@link org.bluezoo.gumdrop.http.HttpClient}; TLS fields should converge on {@link
  org.bluezoo.gumdrop.tls.ClientTlsConfig} the same way (not yet refactored).
- **GrpcClient**: a **pure application adapter** over {@link org.bluezoo.gumdrop.http.HttpClient}.
  Configure dial + TLS on the {@code HttpClient} instance passed to {@code unaryCall} /
  streaming methods; {@code GrpcClient} only adds protobuf framing and {@code
  content-type: application/grpc} semantics.

**WebSocket gap (summary):** transport TLS is configured on {@link
org.bluezoo.gumdrop.websocket.client.WebSocketClient}; HTTP credentials for the upgrade
request are not — see table above.

**Not the same as server `realm`:** HTTP Basic on the server uses {@link
org.bluezoo.gumdrop.http.server.BasicAuthHandler} on the request handler chain.
Client-side credentials are outbound dial/application configuration, not listener
`realm()`.

### FTP (session-based)

Same session-provider model as SMTP and IMAP. The server-side staged handler SPI lives
in {@code org.bluezoo.gumdrop.ftp.handler} ({@link org.bluezoo.gumdrop.ftp.handler.ClientConnected},
{@link org.bluezoo.gumdrop.ftp.handler.NotAuthenticatedHandler},
{@link org.bluezoo.gumdrop.ftp.handler.AuthenticatedHandler}, …). Legacy
{@link org.bluezoo.gumdrop.ftp.FtpConnectionHandler} implementations continue to work
via {@link org.bluezoo.gumdrop.ftp.server.FtpServerSessionProviders#connectionHandler(java.util.function.Supplier)}.

```java
FtpServer server = FtpServer.compose()
        .listener(new FtpListener().port(21).bindWildcard())
        .realm(realm)
        .sessionProvider(FtpServerSessionProviders.fileSystem()
                .rootDirectory(Path.of("/var/ftp")))
        .server();

// Empty default — 220 greeting, login fails without a session provider
FtpServer empty = FtpServer.compose()
        .listener(new FtpListener().port(21).bindWildcard())
        .server();

FtpClient ftp = new FtpClient()
        .host("ftp.example.com")
        .port(21)
        .sessionPerConnection(() -> new MyRemoteGreeting());
ftp.connect();
```

{@link org.bluezoo.gumdrop.ftp.client.FtpClientSessionProvider} mirrors the server-side
{@link org.bluezoo.gumdrop.ftp.server.FtpServerSessionProvider}; there is no stock client
provider beyond {@link org.bluezoo.gumdrop.ftp.client.FtpClientSessionProviders#perSession(java.util.function.Supplier)} —
applications implement {@link org.bluezoo.gumdrop.ftp.client.handler.RemoteGreeting}.
Passing {@code connect(RemoteGreeting)} directly remains supported for one-off use.

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
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.http.Headers;

import java.nio.file.Path;

public final class EchoMain {
    public static void main(String[] args) throws Exception {
        Gumdrop gumdrop = Gumdrop.getInstance();

        HttpServer server = HttpServer.compose()
                .secureEndpoint(443, TlsConfig.pem(
                        Path.of("cert.pem"), Path.of("key.pem")))
                .streamHandler(new EchoStreamHandler())
                .server();

        gumdrop.addServer(server);
        gumdrop.start();
        gumdrop.join();
    }

    private static final class EchoStreamHandler implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new EchoHandler();
        }
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
        .streamHandler(new EchoStreamHandler())
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
        .streamHandler(new EchoStreamHandler())
        .server();
```

### Handler-less HTTP server

Omitting `.streamHandler(...)` leaves the server with **no application
handler**: valid HTTP, every request **404**. The protocol layer still applies
the built-in method table (**501 Not Implemented** for unknown methods).

```java
HttpServer empty = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .server();
```

### `HttpStreamHandler` — one handler per stream

`HttpStreamHandler` has a single job: {@code openStream(HttpResponseState)}
returns the {@link HttpRequestHandler} for that stream. It is called once per
stream, **before** request headers arrive. Routing, authentication decorators,
and method policy belong in {@link HttpRequestHandler}, not in
`HttpStreamHandler`.

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .streamHandler(new MyStreamHandler())
        .server();
```

When a stream handler **is** configured, the protocol layer does **not**
filter methods — the handler decides validity in {@code headers()}. When **no**
stream handler is configured, the built-in method table applies before 404.

Path matching, authentication, and method policy belong in
{@link HttpRequestHandler} — typically by inspecting {@code headers} in
{@code headers()} and delegating to another handler if you need it:

```java
public final class ApiStreamHandler implements HttpStreamHandler {
    @Override
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return new ApiPathHandler();
    }
}

public final class ApiPathHandler extends DefaultHttpRequestHandler {
    @Override
    public void headers(HttpResponseState state, Headers headers) {
        if (headers.getPath().startsWith("/api/")) {
            new ApiHandler().headers(state, headers);
            return;
        }
        NotFoundHttpRequestHandler.INSTANCE.headers(state, headers);
    }
}
```

### HTTP client — one response handler per request

On the client, attach a fresh {@link org.bluezoo.gumdrop.http.client.HttpResponseHandler}
per in-flight request ({@code HttpRequest.send(...)} /
{@code startRequestBody(...)}). Do not share one handler across concurrent
streams on the same connection.

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
        .streamHandler(new ServletRequestHandler(container))
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
        .streamHandler(WebDAVRequestHandler.builder()
                .rootPath(Path.of("/var/www/html"))
                .webdavEnabled(true)
                .build())
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
// MX relay — stock session provider
SmtpServer relay = SmtpServer.compose()
        .listener(new SmtpListener().port(25).bindWildcard())
        .sessionProvider(SmtpServerSessionProviders.relay()
                .hostname("relay.example.com"))
        .server();

// Local mailbox delivery
SmtpServer mbox = SmtpServer.compose()
        .listener(new SmtpListener().port(25).bindWildcard())
        .mailboxFactory(maildirFactory)
        .sessionProvider(SmtpServerSessionProviders.localDelivery()
                .localDomain("example.com"))
        .server();
```

*(The DNS examples above use stateless `DnsQueryHandler` — no session provider.)*

### IMAP and POP3 (mailbox protocols)

Same session-provider model as SMTP. With **no** session provider, the transport
and protocol stack run but there is no mailbox configuration — clients see normal
CAPABILITIES / greeting and get protocol-level failures when they try to use
mailboxes. Plug-and-go mailbox storage attaches via
{@code MailboxStoreImapSessionProvider} / {@code MailboxStorePop3SessionProvider}
(the {@link org.bluezoo.gumdrop.mailbox.MailboxFactory} is a property of the
provider, not the server):

```java
ImapServer imap = ImapServer.compose()
        .listener(new ImapListener().port(993).bindWildcard().secure(true).tls(tls))
        .realm(realm)
        .sessionProvider(ImapServerSessionProviders.mailbox(maildirFactory))
        .server();

// Empty default — CAPABILITIES only, no mailbox backing
ImapServer empty = ImapServer.compose()
        .listener(new ImapListener().port(143).bindWildcard())
        .realm(realm)
        .server();

Pop3Server pop3 = Pop3Server.compose()
        .listener(new Pop3Listener().port(995).bindWildcard().secure(true).tls(tls))
        .realm(realm)
        .sessionProvider(Pop3ServerSessionProviders.mailbox(maildirFactory)
                .greeting("ready"))
        .server();
```

Clients:

```java
ImapClient imap = new ImapClient()
        .host("imap.example.com")
        .port(993)
        .secure(true)
        .sessionPerConnection(() -> new MyRemoteGreeting());
imap.connect();

Pop3Client pop3 = new Pop3Client()
        .host("pop.example.com")
        .port(995)
        .secure(true)
        .sessionPerConnection(() -> new MyRemoteGreeting());
pop3.connect();

FtpClient ftp = new FtpClient()
        .host("ftp.example.com")
        .port(21)
        .sessionPerConnection(() -> new MyRemoteGreeting());
ftp.connect();
```

Legacy XML may still declare {@code DefaultIMAPServer} / {@code DefaultPOP3Server};
those names resolve to {@link org.bluezoo.gumdrop.imap.server.ImapServer} /
{@link org.bluezoo.gumdrop.pop3.server.Pop3Server}. {@code setMailboxFactory()}
installs a {@link MailboxStoreImapSessionProvider} /
{@link MailboxStorePop3SessionProvider} automatically.

---

## Cross-cutting concerns

Wrap request handlers instead of subclassing servers:

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .streamHandler(new TelemetryStreamHandler(realm, config))
        .server();
```

Hopf’s `BasicAuthFactory`-style pattern becomes **handler decorators** inside
your {@link HttpStreamHandler} / {@link HttpRequestHandler} implementations,
not a separate routing SPI.

---

## Mail, FTP, and other stateful protocols

**SMTP** is the reference for session-based composition: {@code SmtpServer}
implements {@code SmtpServerSessionProvider}; the client uses
{@code SmtpClientSessionProvider} / staged {@code *ReplyHandler} interfaces.
**IMAP** and **POP3** use the same pattern ({@code ImapServerSessionProvider},
{@code Pop3ServerSessionProvider}, and matching client providers).
**FTP** uses {@code FtpServerSessionProvider} and staged {@code ftp.handler.*} on the
server; the client uses {@code FtpClientSessionProvider} and staged
{@code ftp.client.handler.*} interfaces (same pattern as SMTP/IMAP/POP3).

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
| `HttpRequestHandlerFactory` | `HttpStreamHandler` + `HttpRequestHandler` |
| `SmtpServer#createHandler()` | `SmtpServer#openSession()` / `SmtpServerSessionProvider` |
| `FtpServer` `createHandler()` | `openSession()` / `FtpServerSessionProvider` |
| `ImapServer` / `Pop3Server` `createHandler()` | `openSession()` / `{Protocol}ServerSessionProvider` |
| `DnsServer` with implicit upstream | `DnsServer` + `UpstreamRelayHandler` |
| Subclass `*Server` for app logic | Handler interfaces + composition; use `{Protocol}Server#compose()` |
| `DefaultIMAPServer` / `DefaultPOP3Server` | `ImapServer` / `Pop3Server` + session providers |

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
| `HttpStreamHandler`, default 404 | **Done** |
| `HttpRequestHandlerFactory` (XML legacy) | **Deprecated** |
| `ServletRequestHandler`, `WebDAVRequestHandler` | **Done** |
| `DnsServer.compose()`, `DnsQueryHandler`, default empty answers | **Done** |
| `SmtpServer.compose()`, fluent `SmtpClient`, `DnsResolver.server()` | **Done** |
| `UpstreamRelayHandler`, `AuthoritativeZoneHandler`, `ZoneFile` | **Done** |
| `SimpleRelaySessionProvider`, `LocalDeliverySessionProvider`, listener `.sessionProvider()` | **Done** |
| `ImapServer.compose()`, `Pop3Server.compose()`, mailbox session providers | **Done** |
| `ImapClient` / `Pop3Client` session providers | **Done** |
| `FtpServer.compose()`, `FtpServerSessionProvider`, staged `ftp.handler.*` | **Done** (server) |
| `FtpClientSessionProvider`, `FtpClient` session composition | **Done** (client) |
| `ClientDial`, `ClientTlsConfig`, fluent client dial across facades | **In progress** |
| `ServerSessionProvider`, `ClientSessionProvider`; SMTP/IMAP/POP3 session SPI | **Done** |
| `Runtime` replaces `Gumdrop.getInstance()` | Planned (§C.4) |
