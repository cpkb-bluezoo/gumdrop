/*
 * DefaultHttpResponseHandler.java
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

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link HttpResponseHandler} with no-op methods.
 *
 * <p>This class provides sensible default behaviour for all handler methods,
 * making it easy to override only the methods you care about. Every message
 * event does nothing, a push promise is rejected, and a failure is logged at
 * WARNING level.
 *
 * <p><strong>Example:</strong>
 * <pre>
 * HttpRequest request = client.get("/", new DefaultHttpResponseHandler() {
 *     &#64;Override
 *     public void status(int code) {
 *         System.out.println("Status: " + code);
 *     }
 *
 *     &#64;Override
 *     public void bodyContent(ByteBuffer data) {
 *         // use the body
 *     }
 * });
 * request.endMessage();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpResponseHandler
 */
public class DefaultHttpResponseHandler implements HttpResponseHandler {

    private static final Logger logger = Logger.getLogger(DefaultHttpResponseHandler.class.getName());

    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");

    /**
     * Creates a new default response handler.
     */
    public DefaultHttpResponseHandler() {
    }

    /**
     * Called when an HTTP/2 server push promise is received.
     *
     * <p>Default implementation rejects the push.
     *
     * @param promise the push promise
     */
    @Override
    public void pushPromise(PushPromise promise) {
        promise.reject();
    }

    /**
     * Called when the request fails.
     *
     * <p>Default implementation logs the failure at WARNING level.
     *
     * @param ex the exception describing the failure
     */
    @Override
    public void failed(Exception ex) {
        logger.log(Level.WARNING, L10N.getString("warn.http_request_failed"), ex);
    }

}
