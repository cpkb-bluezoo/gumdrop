/*
 * ServletHandlerFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandlerFactory;
import org.bluezoo.gumdrop.http.server.HttpResponseState;

/**
 * Factory for creating {@link ServletHandler} instances.
 *
 * @deprecated use {@link org.bluezoo.gumdrop.servlet.server.ServletRequestHandler}
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@Deprecated
public class ServletHandlerFactory implements HttpRequestHandlerFactory {

    private final Container container;

    public ServletHandlerFactory(Container container) {
        this.container = container;
    }

    @Override
    public HttpRequestHandler createHandler(HttpResponseState state, Headers headers) {
        return new ServletHandler(container, container.getBufferSize());
    }

}
