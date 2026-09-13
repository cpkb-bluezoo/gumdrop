/*
 * HttpServerBuilderValidationTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.server.HttpListener;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

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

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresListener() {
        HttpServer.builder()
                .handler(new org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler())
                .build();
    }

}
