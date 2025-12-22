/*
 * DefaultHTTPResponseHandler.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link HTTPResponseHandler} with no-op methods.
 *
 * <p>This class provides sensible default behaviour for all handler methods,
 * making it easy to override only the methods you care about.
 *
 * <p><strong>Default Behaviours:</strong>
 * <ul>
 *   <li>{@link #ok(HTTPResponse)} - no action</li>
 *   <li>{@link #error(HTTPResponse)} - no action</li>
 *   <li>{@link #header(String, String)} - ignored</li>
 *   <li>{@link #startResponseBody()} - no action</li>
 *   <li>{@link #responseBodyContent(ByteBuffer)} - data discarded</li>
 *   <li>{@link #endResponseBody()} - no action</li>
 *   <li>{@link #pushPromise(PushPromise)} - rejected</li>
 *   <li>{@link #close()} - no action</li>
 *   <li>{@link #failed(Exception)} - logged at WARNING level</li>
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>
 * request.send(new DefaultHTTPResponseHandler() {
 *     &#64;Override
 *     public void ok(HTTPResponse response) {
 *         System.out.println("Success: " + response.getStatus());
 *     }
 *
 *     &#64;Override
 *     public void error(HTTPResponse response) {
 *         System.err.println("Error: " + response.getStatus());
 *     }
 * });
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPResponseHandler
 */
public class DefaultHTTPResponseHandler implements HTTPResponseHandler {

    private static final Logger logger = Logger.getLogger(DefaultHTTPResponseHandler.class.getName());

    /**
     * Creates a new default response handler.
     */
    public DefaultHTTPResponseHandler() {
    }

    /**
     * Called when a successful response (2xx) is received.
     *
     * <p>Default implementation does nothing.
     *
     * @param response the response status
     */
    @Override
    public void ok(HTTPResponse response) {
        // Override to handle success
    }

    /**
     * Called when an error response (4xx, 5xx) is received.
     *
     * <p>Default implementation does nothing.
     *
     * @param response the error response
     */
    @Override
    public void error(HTTPResponse response) {
        // Override to handle errors
    }

    /**
     * Called for each HTTP header received.
     *
     * <p>Default implementation ignores all headers.
     *
     * @param name the header name
     * @param value the header value
     */
    @Override
    public void header(String name, String value) {
        // Override to process headers
    }

    /**
     * Called when the response body begins.
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void startResponseBody() {
        // Override to prepare for body
    }

    /**
     * Called for each chunk of response body data.
     *
     * <p>Default implementation discards the data.
     *
     * @param data the body data chunk
     */
    @Override
    public void responseBodyContent(ByteBuffer data) {
        // Override to process body data
    }

    /**
     * Called when the response body is complete.
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void endResponseBody() {
        // Override to finalize body processing
    }

    /**
     * Called when an HTTP/2 server push promise is received.
     *
     * <p>Default implementation rejects all server pushes.
     *
     * @param promise the push promise
     */
    @Override
    public void pushPromise(PushPromise promise) {
        promise.reject();
    }

    /**
     * Called when the response is fully complete.
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void close() {
        // Override to finalize response processing
    }

    /**
     * Called when the request fails.
     *
     * <p>Default implementation logs the exception at WARNING level.
     *
     * @param ex the exception describing the failure
     */
    @Override
    public void failed(Exception ex) {
        logger.log(Level.WARNING, "HTTP request failed", ex);
    }
}

