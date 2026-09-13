/*
 * DefaultHttpRequestHandler.java
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

package org.bluezoo.gumdrop.http.server;


import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;

import java.nio.ByteBuffer;

/**
 * Default implementation of {@link HttpRequestHandler} with empty methods.
 *
 * <p>Extend this class to implement only the methods you need, rather than
 * having to implement all methods of the interface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public class HelloHandler extends DefaultHttpRequestHandler {
 *     
 *     @Override
 *     public void headers(HttpResponseState state, Headers headers) {
 *         if ("GET".equals(headers.getMethod())) {
 *             Headers response = new Headers();
 *             response.status(HttpStatus.OK);
 *             response.add("content-type", "text/plain");
 *             state.headers(response);
 *             state.startResponseBody();
 *             state.responseBodyContent(ByteBuffer.wrap("Hello, World!".getBytes()));
 *             state.endResponseBody();
 *             state.complete();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequestHandler
 */
public class DefaultHttpRequestHandler implements HttpRequestHandler {

    @Override
    public void headers(HttpResponseState state, Headers headers) {
        // Default: do nothing
    }

    @Override
    public void startRequestBody(HttpResponseState state) {
        // Default: do nothing
    }

    @Override
    public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
        // Default: do nothing
    }

    @Override
    public void endRequestBody(HttpResponseState state) {
        // Default: do nothing
    }

    @Override
    public void requestComplete(HttpResponseState state) {
        // Default: do nothing
    }

    @Override
    public void failed(HttpResponseState state, Exception cause) {
        // Default: do nothing
    }

}

