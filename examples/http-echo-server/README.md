# HTTP echo server (Gumdrop 3 composition)

Minimal Gumdrop 3 HTTP server using `HttpServer.compose()` — no XML or
`gumdroprc`.

**Default:** HTTPS (HTTP/2 + HTTP/1.1) and HTTP/3 on the same port, shared TLS:

```java
HttpServer server = HttpServer.compose()
        .secureEndpoint(443, TlsConfig.pem("etc/tls/cert.pem", "etc/tls/key.pem"))
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

The TLS identity is two PEM files, the simplest form. `ant tls-certs` creates
them (with a `ca.pem` for clients) in `etc/tls/`; see
[BUILDING.md](../../BUILDING.md). To use a Java keystore instead, replace
`TlsConfig.pem(cert, key)` with `TlsConfig.keystore(Path.of("keystore.p12"), "changeit")`
(`ant tls-keystore` builds one), as described in
[web/security.html](../../web/security.html#tls-certificates).

Run:

```bash
ant build tls-certs
java -cp build/core:build/lib/* examples.http-echo-server.EchoServer etc/tls/cert.pem etc/tls/key.pem 8443
curl --cacert etc/tls/ca.pem https://localhost:8443/
java -cp build/core:build/lib/* examples.http-echo-server.EchoServer --plaintext 8080
```

(Port 443 is the default but needs elevated privileges on most systems.)

See [web/configuration.html](../../web/configuration.html).
