# HTTP echo server (Gumdrop 3 composition)

Minimal Gumdrop 3 HTTP server using `HttpServer.compose()` — no XML or
`gumdroprc`.

**Default:** HTTPS (HTTP/2 + HTTP/1.1) and HTTP/3 on the same port, shared TLS:

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
        .handler(new EchoHandler())
        .server();
```

**Legacy plaintext** (dev / backward compatibility only):

```java
HttpServer server = HttpServer.compose()
        .plaintextListener(8080)
        .handler(new EchoHandler())
        .server();
```

Run:

```bash
ant build
java -cp build/core:build/lib/* examples.http-echo-server.EchoServer cert.pem key.pem
java -cp build/core:build/lib/* examples.http-echo-server.EchoServer --plaintext 8080
```

See [docs/COMPOSITION.md](../../docs/COMPOSITION.md).
