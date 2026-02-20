/*
 * EchoHandlerFactory.java
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

import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Handler factory for HTTP client integration tests.
 *
 * <p>Creates echo handlers that return the uploaded request content back
 * to the client. This is used to verify that content uploaded via HTTP
 * client matches what the server receives.
 *
 * <p>Response format:
 * <ul>
 *   <li>Status 201 Created for POST/PUT with body</li>
 *   <li>Status 200 OK for GET and other requests</li>
 *   <li>Body contains echoed request information and content</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EchoHandlerFactory implements HTTPRequestHandlerFactory {

    /**
     * Creates a new echo handler factory.
     */
    public EchoHandlerFactory() {
    }

    @Override
    public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
        return new EchoHandler();
    }

    /**
     * Handler that echoes back request content.
     */
    private static class EchoHandler extends DefaultHTTPRequestHandler {

        private String method;
        private String path;
        private String contentType;
        private int contentLength;
        private ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        @Override
        public void headers(HTTPResponseState state, Headers headers) {
            this.method = headers.getMethod();
            this.path = headers.getPath();
            this.contentType = headers.getValue("content-type");

            String contentLengthStr = headers.getValue("content-length");
            if (contentLengthStr != null) {
                try {
                    this.contentLength = Integer.parseInt(contentLengthStr);
                } catch (NumberFormatException e) {
                    this.contentLength = -1;
                }
            } else {
                this.contentLength = -1;
            }
        }

        @Override
        public void startRequestBody(HTTPResponseState state) {
            // Ready to receive body
        }

        @Override
        public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            try {
                bodyBuffer.write(bytes);
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public void endRequestBody(HTTPResponseState state) {
            // Body complete
        }

        @Override
        public void requestComplete(HTTPResponseState state) {
            // Always return 200 OK for echo server - we're reflecting content, not creating resources
            HTTPStatus status = HTTPStatus.OK;

            // Build response body
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("=== Echo Response ===\n");
            responseBody.append("Method: ").append(method).append("\n");
            responseBody.append("Path: ").append(path).append("\n");
            responseBody.append("Content-Type: ").append(contentType).append("\n");
            responseBody.append("Content-Length Header: ").append(contentLength).append("\n");
            responseBody.append("Received Body Length: ").append(bodyBuffer.size()).append("\n");
            responseBody.append("\n=== Body ===\n");
            responseBody.append(new String(bodyBuffer.toByteArray(), StandardCharsets.UTF_8));

            byte[] responseBytes = responseBody.toString().getBytes(StandardCharsets.UTF_8);

            // Send response headers
            Headers responseHeaders = new Headers();
            responseHeaders.status(status);
            responseHeaders.add("content-type", "text/plain; charset=UTF-8");
            responseHeaders.add("content-length", String.valueOf(responseBytes.length));

            state.headers(responseHeaders);
            state.startResponseBody();
            state.responseBodyContent(ByteBuffer.wrap(responseBytes));
            state.endResponseBody();
            state.complete();
        }
    }
}

