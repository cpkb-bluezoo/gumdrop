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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;

/**
 * Concrete {@link HttpServer} assembled from listeners and a stream handler.
 *
 * <p>Created via {@link HttpServer#compose()}; not intended for subclassing.
 */
final class ComposedHttpServer extends HttpServer {

    private final HttpStreamHandler streamHandler;

    ComposedHttpServer(final HttpStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    @Override
    protected void initService(Gumdrop gumdrop) {
        if (streamHandler instanceof HttpServerServiceHook) {
            ((HttpServerServiceHook) streamHandler).initService(gumdrop);
        }
    }

    @Override
    protected void destroyService() {
        if (streamHandler instanceof HttpServerServiceHook) {
            ((HttpServerServiceHook) streamHandler).destroyService();
        }
    }

    @Override
    protected HttpStreamHandler getStreamHandler() {
        return streamHandler;
    }

    @Override
    protected HttpAuthenticationProvider getAuthenticationProvider() {
        if (streamHandler instanceof HttpServerServiceHook) {
            return ((HttpServerServiceHook) streamHandler).getAuthenticationProvider();
        }
        return null;
    }

}
