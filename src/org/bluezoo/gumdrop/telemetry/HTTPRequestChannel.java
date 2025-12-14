/*
 * HTTPRequestChannel.java
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

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.http.client.HTTPRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

/**
 * A {@link WritableByteChannel} that writes to an {@link HTTPRequest} body.
 *
 * <p>This allows protobuf serializers to stream directly to an HTTP request,
 * enabling true streaming for HTTP/2 or chunked HTTP/1.1 transfers.
 *
 * <p>Usage:
 * <pre>
 * HTTPRequest request = client.post("/v1/traces");
 * request.header("Content-Type", "application/x-protobuf");
 * request.header("Transfer-Encoding", "chunked");
 * request.startRequestBody(handler);
 *
 * HTTPRequestChannel channel = new HTTPRequestChannel(request);
 * traceSerializer.serialize(trace, channel);
 * channel.close();  // Calls endRequestBody()
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HTTPRequestChannel implements WritableByteChannel {

    private final HTTPRequest request;
    private boolean open;

    /**
     * Creates a channel that writes to the given HTTP request.
     *
     * <p>The request must have already had {@code startRequestBody()} called on it.
     *
     * @param request the HTTP request
     */
    HTTPRequestChannel(HTTPRequest request) {
        this.request = request;
        this.open = true;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }

        int remaining = src.remaining();
        if (remaining == 0) {
            return 0;
        }

        // Write to the request body, handling backpressure
        int totalWritten = 0;
        while (src.hasRemaining()) {
            int written = request.requestBodyContent(src);
            if (written > 0) {
                totalWritten += written;
            } else {
                // Backpressure - yield and retry
                Thread.yield();
            }
        }

        return totalWritten;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            request.endRequestBody();
        }
    }
}

