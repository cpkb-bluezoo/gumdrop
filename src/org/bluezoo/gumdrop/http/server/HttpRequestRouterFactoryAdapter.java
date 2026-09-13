/*
 * HttpRequestRouterFactoryAdapter.java
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

/**
 * Bridges {@link HttpRequestRouter} to {@link HttpRequestHandlerFactory}
 * for listener wiring during the handler-first migration.
 */
final class HttpRequestRouterFactoryAdapter implements HttpRequestHandlerFactory {

    private final HttpRequestRouter router;

    HttpRequestRouterFactoryAdapter(HttpRequestRouter router) {
        if (router == null) {
            throw new NullPointerException("router");
        }
        this.router = router;
    }

    @Override
    public HttpRequestHandler createHandler(HttpResponseState state, Headers headers) {
        return router.route(state, headers);
    }

    @Override
    public Set<String> getSupportedMethods() {
        return router.getSupportedMethods();
    }

}
