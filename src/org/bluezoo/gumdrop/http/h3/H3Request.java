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
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * An HTTP/3 request that sends via {@link HTTP3ClientHandler}.
 *
 * <p>Implements the {@link HTTPRequest} interface so that application code
 * using {@link org.bluezoo.gumdrop.http.client.HTTPClient} works
 * identically regardless of whether the underlying transport is
 * HTTP/1.1, HTTP/2, or HTTP/3.
 *
 * <p>Pseudo-headers are constructed per RFC 9114 section 4.3.1:
 * {@code :method}, {@code :scheme}, {@code :authority}, {@code :path}.
 *
 * <p>{@code HTTPRequest} carries no thread-affinity contract of its own --
 * an application may call {@link #startRequestBody}/{@link #requestBodyContent}/
 * {@link #endRequestBody} from whatever thread it likes, in separate calls
 * with real time between them. The underlying {@link org.bluezoo.gumdrop.quic.QuicConnection}
 * has the opposite contract (touched only from its own {@code SelectorLoop}
 * thread), so every method here that actually sends anything does its
 * QUIC-connection-touching work inside a task handed to
 * {@link HTTP3ClientHandler#execute}, snapshotting any caller-owned mutable
 * state (header lists, the body {@link ByteBuffer}'s remaining bytes)
 * synchronously first so the caller is free to reuse/refill its buffer the
 * moment the call returns, before the snapshot has necessarily been sent.
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
    private final Trace traceContext;

    private final List<Header> headers = new ArrayList<Header>();
    // Written and read only from tasks run via h3Handler.execute() (always
    // the QuicConnection's own SelectorLoop thread), so ordinary field
    // access is safe between them -- see the class documentation.
    private long streamId = -1;
    // True from the moment send()/startRequestBody() itself gets deferred
    // (h3Handler.isSafeToSendNow(method) was false) until the deferred send
    // actually runs. requestBodyContent()/endRequestBody() consult this to
    // avoid racing ahead of a send that hasn't happened yet: without it,
    // streamId would still read -1 and body data would be silently dropped
    // rather than queued behind the deferred send. Same thread-safety
    // contract as streamId.
    private boolean sendDeferred;
    // The H3ClientStream for this request, so body data can be buffered
    // if the QUIC open is still queued behind MAX_STREAMS credit.
    private H3ClientStream h3Stream;
    // volatile: set from the application's calling thread in send()/
    // startRequestBody(), read from cancel() which may be called from a
    // different thread (e.g. a timeout watchdog).
    private volatile HTTPResponseHandler responseHandler;
    // Unlike streamId, checked from whatever thread the application calls
    // send/startRequestBody/requestBodyContent/endRequestBody/cancel from,
    // so this one does need cross-thread visibility.
    private volatile boolean cancelled;

    public H3Request(HTTP3ClientHandler h3Handler, String method,
                     String path, String authority, String scheme,
                     Trace traceContext) {
        this.h3Handler = h3Handler;
        this.method = method;
        this.path = path;
        this.authority = authority;
        this.scheme = scheme;
        this.traceContext = traceContext;
    }

    @Override
    public void header(String name, String value) {
        headers.add(new Header(name, value));
    }

    /**
     * RFC 9218 section 4: sets the urgency parameter in the Priority
     * header field. The weight (0-255) is mapped to urgency (0-7)
     * where 0 is highest and 7 is lowest priority.
     */
    @Override
    public void priority(int weight) {
        int urgency = 7 - Math.min(7, weight * 7 / 255);
        headers.add(new Header("priority", "u=" + urgency));
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
    public void send(final HTTPResponseHandler handler) {
        if (cancelled) {
            handler.failed(new CancellationException("Request cancelled"));
            return;
        }

        responseHandler = handler;
        final Headers h3Headers = buildHeaders();
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable sendTask = new Runnable() {
                    @Override
                    public void run() {
                        sendDeferred = false;
                        h3Stream = h3Handler.startRequest(h3Headers, handler, true);
                        streamId = h3Stream.getStreamId();
                    }
                };
                if (h3Handler.isSafeToSendNow(method)) {
                    sendTask.run();
                } else {
                    sendDeferred = true;
                    h3Handler.deferUntilEstablished(sendTask);
                }
            }
        });
    }

    @Override
    public void startRequestBody(final HTTPResponseHandler handler) {
        if (cancelled) {
            handler.failed(new CancellationException("Request cancelled"));
            return;
        }

        responseHandler = handler;
        final Headers h3Headers = buildHeaders();
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable sendTask = new Runnable() {
                    @Override
                    public void run() {
                        sendDeferred = false;
                        h3Stream = h3Handler.startRequest(h3Headers, handler, false);
                        streamId = h3Stream.getStreamId();
                    }
                };
                if (h3Handler.isSafeToSendNow(method)) {
                    sendTask.run();
                } else {
                    sendDeferred = true;
                    h3Handler.deferUntilEstablished(sendTask);
                }
            }
        });
    }

    @Override
    public int requestBodyContent(ByteBuffer data) {
        if (cancelled) {
            return 0;
        }
        // Snapshot the remaining bytes synchronously, on the caller's own
        // thread, before returning -- the actual send is deferred to the
        // connection's own thread (see the class documentation), and the
        // caller is free to reuse/refill data the moment this call returns.
        int remaining = data.remaining();
        final byte[] snapshot = new byte[remaining];
        data.get(snapshot);
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable bodyTask = new Runnable() {
                    @Override
                    public void run() {
                        if (h3Stream == null) {
                            return;
                        }
                        h3Handler.sendRequestBody(h3Stream, ByteBuffer.wrap(snapshot), false);
                    }
                };
                if (sendDeferred) {
                    // The request itself hasn't been sent yet -- queue behind
                    // it rather than running now, or streamId would still
                    // read -1 and this data would be silently dropped.
                    h3Handler.deferUntilEstablished(bodyTask);
                } else {
                    bodyTask.run();
                }
            }
        });
        return remaining;
    }

    @Override
    public void endRequestBody() {
        if (cancelled) {
            return;
        }
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable endTask = new Runnable() {
                    @Override
                    public void run() {
                        if (h3Stream == null) {
                            return;
                        }
                        h3Handler.sendRequestBody(h3Stream, ByteBuffer.allocate(0), true);
                    }
                };
                if (sendDeferred) {
                    h3Handler.deferUntilEstablished(endTask);
                } else {
                    endTask.run();
                }
            }
        });
    }

    @Override
    public void cancel() {
        cancelled = true;
        final HTTPResponseHandler handler = responseHandler;
        if (handler != null) {
            h3Handler.execute(new Runnable() {
                @Override
                public void run() {
                    handler.failed(new CancellationException("Request cancelled"));
                }
            });
        }
    }

    /**
     * Builds the full h3 header list including pseudo-headers per
     * RFC 9114 section 4.3.1. Pseudo-headers are emitted first in
     * the order :method, :scheme, :authority, :path, followed by
     * regular headers.
     */
    private Headers buildHeaders() {
        Headers result = new Headers();
        result.add(new Header(":method", method));
        result.add(new Header(":scheme", scheme));
        result.add(new Header(":authority", authority));
        result.add(new Header(":path", path));
        if (traceContext != null && !containsHeader(headers, "traceparent")) {
            String traceparent = traceContext.getTraceparent();
            if (traceparent != null) {
                result.add(new Header("traceparent", traceparent));
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            result.add(headers.get(i));
        }
        return result;
    }

    private static boolean containsHeader(List<Header> headers, String name) {
        String lower = name.toLowerCase();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).getName().toLowerCase().equals(lower)) {
                return true;
            }
        }
        return false;
    }
}
