/*
 * package-info.java
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

/**
 * Non-blocking HTTP client with an event-driven handler pattern.
 *
 * <p>This package provides an asynchronous HTTP client that integrates with
 * Gumdrop's event-driven architecture. It supports HTTP/1.1 and HTTP/2,
 * connection multiplexing, server push, streaming bodies, and trailer headers.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.client.HTTPClient} - Interface for
 *       making HTTP requests to a server</li>
 *   <li>{@link org.bluezoo.gumdrop.http.client.HTTPRequest} - Represents a
 *       request to be sent</li>
 *   <li>{@link org.bluezoo.gumdrop.http.client.HTTPResponseHandler} - Callback
 *       interface for receiving response events</li>
 *   <li>{@link org.bluezoo.gumdrop.http.client.HTTPResponse} - Response status
 *       and redirect information</li>
 *   <li>{@link org.bluezoo.gumdrop.http.HTTPStatus} - Symbolic HTTP
 *       status codes</li>
 *   <li>{@link org.bluezoo.gumdrop.http.client.PushPromise} - HTTP/2 server
 *       push promise</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>HTTP/1.1 with keep-alive support</li>
 *   <li>HTTP/2 with stream multiplexing and server push</li>
 *   <li>Event-driven header delivery (including trailers)</li>
 *   <li>Streaming request and response bodies</li>
 *   <li>Backpressure support for large uploads</li>
 *   <li>SSL/TLS with ALPN protocol negotiation</li>
 *   <li>Automatic redirect following</li>
 *   <li>Request cancellation</li>
 *   <li>Authentication (Basic, Bearer, Digest, OAuth)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Simple GET request
 * HTTPRequest request = client.get("/api/users");
 * request.header("Accept", "application/json");
 * request.send(new DefaultHTTPResponseHandler() {
 *     @Override
 *     public void ok(HTTPResponse response) {
 *         System.out.println("Success: " + response.getStatus());
 *     }
 *
 *     @Override
 *     public void header(String name, String value) {
 *         System.out.println(name + ": " + value);
 *     }
 *
 *     @Override
 *     public void responseBodyContent(ByteBuffer data) {
 *         // Process body chunk
 *     }
 *
 *     @Override
 *     public void close() {
 *         System.out.println("Response complete");
 *     }
 * });
 *
 * // POST with body
 * HTTPRequest post = client.post("/api/users");
 * post.header("Content-Type", "application/json");
 * post.startRequestBody(handler);
 * post.requestBodyContent(ByteBuffer.wrap(jsonBytes));
 * post.endRequestBody();
 *
 * // Multiple concurrent requests (HTTP/2)
 * if (client.getVersion() != null && client.getVersion().supportsMultiplexing()) {
 *     for (String path : paths) {
 *         client.get(path).send(handler);
 *     }
 * }
 * }</pre>
 *
 * <h2>Event Flow</h2>
 *
 * <p>For a successful response with body and trailers:
 * <ol>
 *   <li>{@code ok(HTTPResponse)} - status received</li>
 *   <li>{@code header(name, value)} - for each response header</li>
 *   <li>{@code startResponseBody()} - body begins</li>
 *   <li>{@code responseBodyContent(ByteBuffer)} - for each body chunk</li>
 *   <li>{@code endResponseBody()} - body complete</li>
 *   <li>{@code header(name, value)} - for each trailer header</li>
 *   <li>{@code close()} - response complete</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.client.HTTPClient
 * @see org.bluezoo.gumdrop.http.client.HTTPResponseHandler
 */
package org.bluezoo.gumdrop.http.client;
