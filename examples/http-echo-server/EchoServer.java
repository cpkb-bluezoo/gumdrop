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
import org.bluezoo.gumdrop.http.server.HttpListener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Echoes {@code ok} for every GET request on port 8080.
 *
 * <p>Run from the gumdrop tree after {@code ant build}:
 * <pre>{@code
 * java -cp build/core:build/lib/* examples.http-echo-server.EchoServer
 * }</pre>
 */
public final class EchoServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Gumdrop gumdrop = Gumdrop.getInstance();

        HttpServer server = HttpServer.builder()
                .listener(HttpListener.builder().port(port).build())
                .handler(new EchoHandler())
                .build();

        gumdrop.addServer(server);
        gumdrop.start();

        System.out.println("Echo server listening on port " + port);
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
