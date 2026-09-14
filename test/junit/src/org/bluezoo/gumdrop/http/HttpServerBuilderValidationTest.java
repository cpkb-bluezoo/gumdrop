/*
 * HttpServerBuilderValidationTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Composer validation for {@link HttpServer#compose()}.
 */
public class HttpServerBuilderValidationTest {

    @Test
    public void testBuildWithoutHandlerUsesDefaultNotFound() {
        HttpServer server = HttpServer.compose()
                .listener(new Http2Listener().port(9999))
                .server();
        assertNotNull(server);
    }

    @Test
    public void testSecureEndpointWiresTcpAndQuicListeners() {
        TlsConfig tls = TlsConfig.pem("cert.pem", "key.pem");
        HttpServer server = HttpServer.compose()
                .secureEndpoint(8443, tls)
                .server();
        assertEquals(2, server.getListeners().size());
        assertTrue(server.getListeners().get(0) instanceof Http2Listener);
        assertTrue(server.getListeners().get(1) instanceof Http3Listener);
        assertEquals(8443, ((Http2Listener) server.getListeners().get(0)).getPort());
        assertEquals(8443, ((Http3Listener) server.getListeners().get(1)).getPort());
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresListener() {
        HttpServer.compose()
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(HttpResponseState stream) {
                        return new DefaultHttpRequestHandler();
                    }
                })
                .server();
    }

}
