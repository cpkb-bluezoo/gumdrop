/*
 * HttpServerBuilderValidationTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.server.HttpListener;
import org.bluezoo.gumdrop.http.server.HttpTlsConfig;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Builder validation for {@link HttpServer#builder()}.
 */
public class HttpServerBuilderValidationTest {

    @Test
    public void testBuildWithoutHandlerUsesDefaultNotFound() {
        HttpServer server = HttpServer.builder()
                .listener(HttpListener.builder().port(9999).build())
                .build();
        assertNotNull(server);
    }

    @Test
    public void testSecureEndpointWiresTcpAndQuicListeners() {
        HttpTlsConfig tls = HttpTlsConfig.pem("cert.pem", "key.pem");
        HttpServer server = HttpServer.builder()
                .secureEndpoint(8443, tls)
                .build();
        assertEquals(2, server.getListeners().size());
        assertTrue(server.getListeners().get(0) instanceof HttpListener);
        assertTrue(server.getListeners().get(1) instanceof Http3Listener);
        assertEquals(8443, ((HttpListener) server.getListeners().get(0)).getPort());
        assertEquals(8443, ((Http3Listener) server.getListeners().get(1)).getPort());
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresListener() {
        HttpServer.builder()
                .handler(new org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler())
                .build();
    }

}
