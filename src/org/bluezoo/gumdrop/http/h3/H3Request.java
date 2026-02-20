/*
 * H3Request.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;

/**
 * An HTTP/3 request that sends via {@link HTTP3ClientHandler}.
 *
 * <p>Implements the {@link HTTPRequest} interface so that application code
 * using {@link org.bluezoo.gumdrop.http.client.HTTPClient} works
 * identically regardless of whether the underlying transport is
 * HTTP/1.1, HTTP/2, or HTTP/3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ClientHandler
 */
public class H3Request implements HTTPRequest {

    private final HTTP3ClientHandler h3Handler;
    private final String method;
    private final String path;
    private final String authority;
    private final String scheme;

    private final List<Header> headers = new ArrayList<Header>();
    private long streamId = -1;
    private HTTPResponseHandler responseHandler;
    private boolean cancelled;

    public H3Request(HTTP3ClientHandler h3Handler, String method,
                     String path, String authority, String scheme) {
        this.h3Handler = h3Handler;
        this.method = method;
        this.path = path;
        this.authority = authority;
        this.scheme = scheme;
    }

    @Override
    public void header(String name, String value) {
        headers.add(new Header(name, value));
    }

    @Override
    public void priority(int weight) {
        // HTTP/3 priority uses the Priority header (RFC 9218)
        // rather than HTTP/2-style stream dependencies
    }

    @Override
    public void dependency(HTTPRequest parent) {
        // Not applicable to HTTP/3
    }

    @Override
    public void exclusive(boolean exclusive) {
        // Not applicable to HTTP/3
    }

    @Override
    public void send(HTTPResponseHandler handler) {
        if (cancelled) {
            handler.failed(new CancellationException("Request cancelled"));
            return;
        }

        Headers h3Headers = buildHeaders();
        streamId = h3Handler.sendRequest(h3Headers, handler, true);
        responseHandler = handler;
    }

    @Override
    public void startRequestBody(HTTPResponseHandler handler) {
        if (cancelled) {
            handler.failed(new CancellationException("Request cancelled"));
            return;
        }

        Headers h3Headers = buildHeaders();
        streamId = h3Handler.sendRequest(h3Headers, handler, false);
        responseHandler = handler;
    }

    @Override
    public int requestBodyContent(ByteBuffer data) {
        if (streamId < 0 || cancelled) {
            return 0;
        }
        int remaining = data.remaining();
        h3Handler.sendRequestBody(streamId, data, false);
        return remaining;
    }

    @Override
    public void endRequestBody() {
        if (streamId < 0 || cancelled) {
            return;
        }
        ByteBuffer empty = ByteBuffer.allocate(0);
        h3Handler.sendRequestBody(streamId, empty, true);
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (responseHandler != null) {
            responseHandler.failed(
                    new CancellationException("Request cancelled"));
        }
    }

    /**
     * Builds the full h3 header list including pseudo-headers.
     */
    private Headers buildHeaders() {
        Headers result = new Headers();
        result.add(new Header(":method", method));
        result.add(new Header(":scheme", scheme));
        result.add(new Header(":authority", authority));
        result.add(new Header(":path", path));
        for (int i = 0; i < headers.size(); i++) {
            result.add(headers.get(i));
        }
        return result;
    }
}
