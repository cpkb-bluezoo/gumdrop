/*
 * HttpStreamHandler.java
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

/**
 * Binds an {@link HttpRequestHandler} when the server discovers a new HTTP
 * stream.
 *
 * <p>Called once per stream, before any request events are delivered. Routing,
 * method policy, and header interpretation belong in {@link HttpRequestHandler}
 * (typically via delegation), not here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequestHandler
 */
public interface HttpStreamHandler {

    /**
     * Opens the application handler for a new stream.
     *
     * @param stream response state for this stream
     * @return the handler for this stream's lifecycle, or {@code null} to
     *         reject with {@code 404 Not Found} if no response was sent via
     *         {@code stream}
     */
    HttpRequestHandler openStream(HttpResponseState stream);

}
