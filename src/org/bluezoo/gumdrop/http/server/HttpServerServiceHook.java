/*
 * HttpServerServiceHook.java
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

import org.bluezoo.gumdrop.Gumdrop;

/**
 * Optional lifecycle hook for {@link HttpStreamHandler} implementations that
 * own resources started by {@link org.bluezoo.gumdrop.http.HttpServer#start(Gumdrop)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface HttpServerServiceHook {

    /**
     * Called from {@link org.bluezoo.gumdrop.http.HttpServer#start(Gumdrop)}
     * after {@link org.bluezoo.gumdrop.http.HttpServer#initService(Gumdrop)}
     * begins.
     *
     * @param gumdrop the runtime this server is starting under
     */
    void initService(Gumdrop gumdrop);

    /**
     * Called from {@link org.bluezoo.gumdrop.http.HttpServer#stop()} before
     * listeners stop.
     */
    void destroyService();

    /**
     * Returns an authentication provider wired by this router, or {@code null}
     * if none (for example servlet security constraints).
     */
    default HttpAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

}
