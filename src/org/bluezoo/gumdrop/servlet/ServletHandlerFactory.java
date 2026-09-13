/*
 * ServletHandlerFactory.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandlerFactory;
import org.bluezoo.gumdrop.http.server.HttpResponseState;

import java.util.Set;

/**
 * Factory for creating {@link ServletHandler} instances.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletHandlerFactory implements HttpRequestHandlerFactory {

    private final org.bluezoo.gumdrop.servlet.server.ServletServer service;
    private final Container container;

    public ServletHandlerFactory(
            org.bluezoo.gumdrop.servlet.server.ServletServer service,
            Container container) {
        this.service = service;
        this.container = container;
    }

    @Override
    public HttpRequestHandler createHandler(HttpResponseState state, Headers headers) {
        return new ServletHandler(service, container, service.getBufferSize());
    }

}

