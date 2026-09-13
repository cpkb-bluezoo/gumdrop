/*
 * HttpRequestHandlersTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Headers;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpRequestHandlers}.
 */
public class HttpRequestHandlersTest {

    @Test
    public void testFixedReturnsSameHandler() {
        HttpRequestHandler handler = new DefaultHttpRequestHandler();
        HttpRequestRouter router = HttpRequestHandlers.fixed(handler);
        assertSame(handler, router.route(null, new Headers()));
    }

    @Test
    public void testPerRequestCreatesNewInstances() {
        HttpRequestRouter router = HttpRequestHandlers.perRequest(
                DefaultHttpRequestHandler::new);
        HttpRequestHandler first = router.route(null, new Headers());
        HttpRequestHandler second = router.route(null, new Headers());
        assertNotSame(first, second);
    }

    @Test
    public void testFromFactoryDelegatesCreateHandler() {
        HttpRequestHandlerFactory factory = new HttpRequestHandlerFactory() {
            @Override
            public HttpRequestHandler createHandler(HttpResponseState state,
                                                    Headers headers) {
                return new DefaultHttpRequestHandler();
            }

            @Override
            public Set<String> getSupportedMethods() {
                return Collections.singleton("GET");
            }
        };

        HttpRequestRouter router = HttpRequestHandlers.fromFactory(factory);
        assertNotNull(router.route(null, new Headers()));
        assertEquals(Collections.singleton("GET"), router.getSupportedMethods());
    }

    @Test
    public void testNotFoundRouterUsesSharedHandler() {
        HttpRequestRouter router = HttpRequestHandlers.notFound();
        assertSame(NotFoundHttpRequestHandler.INSTANCE,
                router.route(null, new Headers()));
    }

    @Test
    public void testToFactoryDelegatesToRouter() {
        HttpRequestRouter router = HttpRequestHandlers.fixed(new DefaultHttpRequestHandler());
        HttpRequestHandlerFactory factory = HttpRequestHandlers.toFactory(router);
        assertNotNull(factory.createHandler(null, new Headers()));
    }

}
