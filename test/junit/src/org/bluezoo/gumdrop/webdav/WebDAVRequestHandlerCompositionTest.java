/*
 * WebDAVRequestHandlerCompositionTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.webdav;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Workstream C.3 — {@link WebDAVRequestHandler} on {@link HttpServer#compose()}.
 */
public class WebDAVRequestHandlerCompositionTest {

    private static final String TEST_HOST = "127.0.0.1";
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(48200);

    private Gumdrop gumdrop;
    private HttpServer server;
    private Path root;
    private int testPort;

    @Before
    public void setUp() throws Exception {
        testPort = NEXT_PORT.getAndIncrement();
        root = Files.createTempDirectory("gumdrop-webdav-handler-test");
        Files.write(root.resolve("hello.txt"),
                "Hello, WebDAV!".getBytes(StandardCharsets.UTF_8));

        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));

        server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(testPort)
                        .addresses(InetAddress.ofLiteral(TEST_HOST)))
                .streamHandler(WebDAVRequestHandler.builder()
                        .rootPath(root)
                        .build())
                .server();

        gumdrop.addServer(server);
        gumdrop.start();
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null) {
            if (server != null) {
                gumdrop.removeServer(server);
                server = null;
            }
            gumdrop.shutdown();
            gumdrop.join();
        }
        if (root != null) {
            Files.deleteIfExists(root.resolve("hello.txt"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void testServesStaticFileViaComposedHttpServer() throws Exception {
        HttpClient client = connect(gumdrop, testPort);
        HttpRequest request = client.get("/hello.txt");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HttpResponse> responseRef = new AtomicReference<HttpResponse>();
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        AtomicReference<Exception> errorRef = new AtomicReference<Exception>();

        request.send(new DefaultHttpResponseHandler() {
            @Override
            public void ok(HttpResponse response) {
                responseRef.set(response);
            }

            @Override
            public void responseBodyContent(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    bodyBuffer.write(bytes);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void close() {
                latch.countDown();
            }

            @Override
            public void failed(Exception cause) {
                errorRef.set(cause);
                latch.countDown();
            }
        });

        assertTrue("response not received", latch.await(5, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        HttpResponse response = responseRef.get();
        assertNotNull(response);
        assertEquals(200, response.getStatus().code);
        assertEquals("Hello, WebDAV!",
                new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void testBuilderRejectsMissingRootDirectory() throws Exception {
        Path missing = root.resolve("does-not-exist");
        try {
            WebDAVRequestHandler.builder()
                    .rootPath(missing)
                    .build();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Cannot access root path"));
        }
    }

    private static HttpClient connect(Gumdrop gumdrop, int port) throws Exception {
        HttpClient client = new HttpClient(TEST_HOST, port);
        client.setAltSvcEnabled(false);
        client.setH2Enabled(false);
        client.setH2cUpgradeEnabled(false);

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connected.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                connected.countDown();
            }

            @Override
            public void onDisconnected() {
            }
        });

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        return client;
    }

}
