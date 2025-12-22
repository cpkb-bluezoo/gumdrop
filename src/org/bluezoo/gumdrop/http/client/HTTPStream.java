/*
 * HTTPStream.java
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
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.http.Headers;

/**
 * Internal implementation of {@link HTTPRequest} representing an HTTP stream.
 *
 * <p>In HTTP/2, each request/response exchange occurs on a separate stream.
 * In HTTP/1.1, there is logically one stream per request on the connection.
 * This class encapsulates the request state and delegates actual I/O to
 * the owning {@link HTTPClientConnection}.
 *
 * <p>Instances are created internally by {@link HTTPClientConnection} via
 * factory methods like {@link HTTPClient#get(String)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HTTPStream implements HTTPRequest {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");

    private final HTTPClientConnection connection;
    private final String method;
    private final String path;
    private final Headers headers;
    
    // HTTP/2 stream ID (assigned when sent)
    int streamId;
    
    // HTTP/2 priority settings
    private int priority = 16;  // Default weight
    private HTTPRequest dependency;
    private boolean exclusive;
    
    // State
    private HTTPResponseHandler handler;
    private boolean headersSent;
    private boolean bodySent;
    private boolean cancelled;

    /**
     * Creates a new HTTP stream.
     *
     * @param connection the connection this stream belongs to
     * @param method the HTTP method
     * @param path the request path
     */
    HTTPStream(HTTPClientConnection connection, String method, String path) {
        this.connection = connection;
        this.method = method;
        this.path = path;
        this.headers = new Headers();
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method
     */
    String getMethod() {
        return method;
    }

    /**
     * Returns the request path.
     *
     * @return the path
     */
    String getPath() {
        return path;
    }

    /**
     * Returns the request headers.
     *
     * @return the headers
     */
    Headers getHeaders() {
        return headers;
    }

    /**
     * Returns the response handler.
     *
     * @return the handler
     */
    HTTPResponseHandler getHandler() {
        return handler;
    }

    /**
     * Returns the priority weight.
     *
     * @return the priority (1-256)
     */
    int getPriorityWeight() {
        return priority;
    }

    /**
     * Returns the dependency request.
     *
     * @return the dependency, or null
     */
    HTTPRequest getDependency() {
        return dependency;
    }

    /**
     * Returns whether this is an exclusive dependency.
     *
     * @return true if exclusive
     */
    boolean isExclusive() {
        return exclusive;
    }

    @Override
    public void header(String name, String value) {
        if (headersSent) {
            throw new IllegalStateException(L10N.getString("err.headers_already_sent"));
        }
        headers.add(name, value);
    }

    @Override
    public void priority(int weight) {
        if (weight < 1 || weight > 256) {
            throw new IllegalArgumentException("Priority weight must be 1-256");
        }
        this.priority = weight;
    }

    @Override
    public void dependency(HTTPRequest parent) {
        this.dependency = parent;
    }

    @Override
    public void exclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    @Override
    public void send(HTTPResponseHandler handler) {
        if (this.handler != null) {
            throw new IllegalStateException(L10N.getString("err.request_already_sent"));
        }
        if (cancelled) {
            throw new IllegalStateException(L10N.getString("err.request_cancelled"));
        }
        this.handler = handler;
        this.headersSent = true;
        this.bodySent = true;
        connection.sendRequest(this, false);
    }

    @Override
    public void startRequestBody(HTTPResponseHandler handler) {
        if (this.handler != null) {
            throw new IllegalStateException(L10N.getString("err.request_already_sent"));
        }
        if (cancelled) {
            throw new IllegalStateException(L10N.getString("err.request_cancelled"));
        }
        this.handler = handler;
        this.headersSent = true;
        connection.sendRequest(this, true);
    }

    @Override
    public int requestBodyContent(ByteBuffer data) {
        if (!headersSent) {
            throw new IllegalStateException(L10N.getString("err.must_start_body"));
        }
        if (bodySent) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        if (cancelled) {
            return 0;
        }
        return connection.sendRequestBody(this, data);
    }

    @Override
    public void endRequestBody() {
        if (!headersSent) {
            throw new IllegalStateException(L10N.getString("err.must_start_body"));
        }
        if (bodySent) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        this.bodySent = true;
        connection.endRequestBody(this);
    }

    @Override
    public void cancel() {
        if (!cancelled) {
            cancelled = true;
            connection.cancelRequest(this);
        }
    }

    @Override
    public String toString() {
        return method + " " + path;
    }
}
