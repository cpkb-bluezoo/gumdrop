/*
 * ComposedHttpServer.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpRequestRouter;
import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;

/**
 * Concrete {@link HttpServer} assembled from listeners and a request router.
 *
 * <p>Created via {@link HttpServer#compose()}; not intended for subclassing.
 */
final class ComposedHttpServer extends HttpServer {

    private final HttpRequestRouter router;

    ComposedHttpServer(final HttpRequestRouter router) {
        this.router = router;
    }

    @Override
    protected void initService() {
        if (router instanceof HttpServerServiceHook) {
            ((HttpServerServiceHook) router).initService();
        }
    }

    @Override
    protected void destroyService() {
        if (router instanceof HttpServerServiceHook) {
            ((HttpServerServiceHook) router).destroyService();
        }
    }

    @Override
    protected HttpRequestRouter getRequestRouter() {
        return router;
    }

    @Override
    protected HttpAuthenticationProvider getAuthenticationProvider() {
        if (router instanceof HttpServerServiceHook) {
            return ((HttpServerServiceHook) router).getAuthenticationProvider();
        }
        return null;
    }

}
