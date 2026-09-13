/*
 * HttpRequestHandlers.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Headers;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Factory methods for {@link HttpRequestRouter} implementations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequestRouter
 */
public final class HttpRequestHandlers {

    private HttpRequestHandlers() {
    }

    /**
     * Returns a router that always uses the same handler instance.
     *
     * <p>Use only when the handler is stateless and safe to share across
     * concurrent requests (for example, a handler that responds entirely
     * from {@link HttpRequestHandler#headers} without storing per-request
     * fields on {@code this}).
     */
    public static HttpRequestRouter fixed(final HttpRequestHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        return new HttpRequestRouter() {
            @Override
            public HttpRequestHandler route(HttpResponseState state, Headers headers) {
                return handler;
            }
        };
    }

    /**
     * Returns a router that creates a fresh handler for each request.
     */
    public static HttpRequestRouter perRequest(final Supplier<HttpRequestHandler> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new HttpRequestRouter() {
            @Override
            public HttpRequestHandler route(HttpResponseState state, Headers headers) {
                return supplier.get();
            }
        };
    }

    /**
     * Returns a router that always declines the request with {@code 404}.
     *
     * <p>This is the default when {@link org.bluezoo.gumdrop.http.HttpServer.Builder}
     * is built without an explicit handler.
     */
    public static HttpRequestRouter notFound() {
        return fixed(NotFoundHttpRequestHandler.INSTANCE);
    }

    /**
     * Adapts a legacy {@link HttpRequestHandlerFactory} to a router.
     *
     * @deprecated {@link HttpRequestHandlerFactory} is deprecated; implement
     *             {@link HttpRequestRouter} directly.
     */
    @Deprecated
    public static HttpRequestRouter fromFactory(final HttpRequestHandlerFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        return new HttpRequestRouter() {
            @Override
            public HttpRequestHandler route(HttpResponseState state, Headers headers) {
                return factory.createHandler(state, headers);
            }

            @Override
            public Set<String> getSupportedMethods() {
                return factory.getSupportedMethods();
            }
        };
    }

    /**
     * Adapts a router to the legacy factory SPI.
     *
     * @deprecated use {@link HttpRequestRouter} on listeners directly.
     */
    @Deprecated
    public static HttpRequestHandlerFactory toFactory(HttpRequestRouter router) {
        if (router == null) {
            return null;
        }
        return new HttpRequestRouterFactoryAdapter(router);
    }

}
