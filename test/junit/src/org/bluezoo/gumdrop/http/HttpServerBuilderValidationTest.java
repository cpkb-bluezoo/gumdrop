/*
 * HttpServerBuilderValidationTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.server.HttpListener;
import org.junit.Test;

/**
 * Builder validation for {@link HttpServer#builder()}.
 */
public class HttpServerBuilderValidationTest {

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresHandler() {
        HttpServer.builder()
                .listener(HttpListener.builder().port(9999).build())
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresListener() {
        HttpServer.builder()
                .handler(new org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler())
                .build();
    }

}
