/*
 * HandlerFactoryStreamHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.server;

/**
 * Adapts a deprecated {@link HttpRequestHandlerFactory} to {@link HttpStreamHandler}.
 */
public final class HandlerFactoryStreamHandler implements HttpStreamHandler {

    private final HttpRequestHandlerFactory factory;

    public HandlerFactoryStreamHandler(HttpRequestHandlerFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        this.factory = factory;
    }

    @Override
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return new FactoryDelegatingRequestHandler(factory);
    }

    HttpRequestHandlerFactory getFactory() {
        return factory;
    }

}
