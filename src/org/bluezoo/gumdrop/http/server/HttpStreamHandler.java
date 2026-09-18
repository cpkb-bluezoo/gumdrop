/*
 * HttpStreamHandler.java
 * Copyright (C) 2026 Chris Burdess
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
