# HTTP echo server (composition)

Minimal Gumdrop 3 HTTP server using `HttpServer.builder()` — no XML or
`ServletServer` subclass.

```java
HttpServer server = HttpServer.builder()
        .listener(HttpListener.builder().port(8080).build())
        .handler(new EchoHandler())
        .build();
gumdrop.addServer(server);
```

See `EchoServer.java` and [docs/COMPOSITION.md](../docs/COMPOSITION.md).
