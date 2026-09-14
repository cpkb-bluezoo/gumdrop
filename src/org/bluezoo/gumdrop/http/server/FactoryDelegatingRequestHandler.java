/*
 * FactoryDelegatingRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Headers;

import java.nio.ByteBuffer;

/**
 * Legacy bridge: one {@link HttpRequestHandler} per stream that delegates to
 * a deprecated {@link HttpRequestHandlerFactory} on the first {@link #headers}
 * call.
 */
final class FactoryDelegatingRequestHandler extends DefaultHttpRequestHandler {

    private final HttpRequestHandlerFactory factory;
    private HttpRequestHandler delegate;

    FactoryDelegatingRequestHandler(HttpRequestHandlerFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        this.factory = factory;
    }

    @Override
    public void headers(HttpResponseState state, Headers headers) {
        if (delegate == null) {
            delegate = factory.createHandler(state, headers);
            if (delegate == null) {
                NotFoundHttpRequestHandler.INSTANCE.headers(state, headers);
                return;
            }
        }
        delegate.headers(state, headers);
    }

    @Override
    public void startRequestBody(HttpResponseState state) {
        forward(state).startRequestBody(state);
    }

    @Override
    public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
        forward(state).requestBodyContent(state, data);
    }

    @Override
    public void endRequestBody(HttpResponseState state) {
        forward(state).endRequestBody(state);
    }

    @Override
    public void requestComplete(HttpResponseState state) {
        if (delegate != null) {
            delegate.requestComplete(state);
        }
    }

    @Override
    public void failed(HttpResponseState state, Exception cause) {
        if (delegate != null) {
            delegate.failed(state, cause);
        }
    }

    @Override
    public boolean wantsDatagrams() {
        return delegate != null && delegate.wantsDatagrams();
    }

    @Override
    public void datagramReceived(HttpResponseState state, ByteBuffer data) {
        forward(state).datagramReceived(state, data);
    }

    @Override
    public void capsuleReceived(HttpResponseState state, long type, ByteBuffer value) {
        forward(state).capsuleReceived(state, type, value);
    }

    private HttpRequestHandler forward(HttpResponseState state) {
        if (delegate == null) {
            NotFoundHttpRequestHandler.INSTANCE.headers(state, new Headers());
            throw new IllegalStateException("no delegate before body event");
        }
        return delegate;
    }

}
