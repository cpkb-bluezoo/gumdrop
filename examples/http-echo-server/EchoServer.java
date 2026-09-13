/*
 * EchoServer.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Minimal HTTP server built with Gumdrop 3 composition (no XML).
 */

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpTlsConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Echoes {@code ok} for every GET request.
 *
 * <p>Default: HTTPS + HTTP/3 on port 443 with PEM certificate files.
 * Pass {@code --plaintext PORT} for legacy cleartext HTTP/1.1 only.
 *
 * <p>Run from the gumdrop tree after {@code ant build}:
 * <pre>{@code
 * java -cp build/core:build/lib/* examples.http-echo-server.EchoServer \
 *     cert.pem key.pem
 * java -cp build/core:build/lib/* examples.http-echo-server.EchoServer \
 *     --plaintext 8080
 * }</pre>
 */
public final class EchoServer {

    public static void main(String[] args) throws Exception {
        Gumdrop gumdrop = Gumdrop.getInstance();

        HttpServer.Builder builder = HttpServer.builder().handler(new EchoHandler());

        if (args.length >= 1 && "--plaintext".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            builder.plaintextListener(port);
            gumdrop.addServer(builder.build());
            gumdrop.start();
            System.out.println("Echo server (legacy plaintext) on port " + port);
        } else {
            String cert = args.length > 0 ? args[0] : "cert.pem";
            String key = args.length > 1 ? args[1] : "key.pem";
            int port = args.length > 2 ? Integer.parseInt(args[2]) : 443;
            builder.secureEndpoint(port, HttpTlsConfig.pem(cert, key));
            gumdrop.addServer(builder.build());
            gumdrop.start();
            System.out.println("Echo server (HTTPS + HTTP/3) on port " + port);
        }

        gumdrop.join();
    }

    /**
     * Stateless handler — safe to share via {@link HttpServer.Builder#handler}.
     */
    private static final class EchoHandler extends DefaultHttpRequestHandler {
        @Override
        public void headers(HttpResponseState state, Headers headers) {
            Headers response = new Headers();
            response.add(":status", "200");
            response.add("content-type", "text/plain");
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(
                    ByteBuffer.wrap("ok\n".getBytes(StandardCharsets.UTF_8)));
            state.endResponseBody();
            state.complete();
        }
    }

    private EchoServer() {
    }

}
