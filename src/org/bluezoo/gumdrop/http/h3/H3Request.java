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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CancellationException;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpDateFormat;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.PriorityParams;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * An HTTP/3 request that sends via {@link Http3ClientHandler}.
 *
 * <p>Implements the {@link HttpRequest} interface so that application code
 * using {@link org.bluezoo.gumdrop.http.HttpClient} works
 * identically regardless of whether the underlying transport is
 * HTTP/1.1, HTTP/2, or HTTP/3.
 *
 * <p>Pseudo-headers are constructed per RFC 9114 section 4.3.1:
 * {@code :method}, {@code :scheme}, {@code :authority}, {@code :path}.
 *
 * <p>{@code HttpRequest} carries no thread-affinity contract of its own --
 * an application may call {@link #header}/{@link #bodyContent}/
 * {@link #endMessage} from whatever thread it likes, in separate calls
 * with real time between them. The underlying {@link org.bluezoo.gumdrop.quic.QuicConnection}
 * has the opposite contract (touched only from its own {@code SelectorLoop}
 * thread), so every method here that actually sends anything does its
 * QUIC-connection-touching work inside a task handed to
 * {@link Http3ClientHandler#execute}, snapshotting any caller-owned mutable
 * state (header lists, the body {@link ByteBuffer}'s remaining bytes)
 * synchronously first so the caller is free to reuse/refill its buffer the
 * moment the call returns, before the snapshot has necessarily been sent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Http3ClientHandler
 */
public class H3Request implements HttpRequest {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");

    private final Http3ClientHandler h3Handler;
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
    // True from the moment the request itself gets deferred
    // (h3Handler.isSafeToSendNow(method) was false) until the deferred send
    // actually runs. bodyContent()/endMessage() consult this to
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
    private volatile HttpResponseHandler responseHandler;
    // Unlike streamId, checked from whatever thread the application calls
    // header/bodyContent/endMessage/cancel from,
    // so this one does need cross-thread visibility.
    private volatile boolean cancelled;

    private volatile boolean requestStarted;

    // The first piece of body, held back until it is known whether it is the
    // only one; and whether endMessage() has been called
    private byte[] firstChunk;
    private boolean ended;
    // Trailer fields: fields given once the body has begun
    private Headers trailers;
    private boolean bodyStarted;

    public H3Request(Http3ClientHandler h3Handler, String method,
                     String path, String authority, String scheme,
                     Trace traceContext, HttpResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
        this.h3Handler = h3Handler;
        this.method = method;
        this.path = path;
        this.authority = authority;
        this.scheme = scheme;
        this.traceContext = traceContext;
    }

    @Override
    public void header(String name, String value) {
        if (ended) {
            throw new IllegalStateException(L10N.getString("err.headers_already_sent"));
        }
        if (requestStarted || bodyStarted) {
            // the header section is sent, or the body has begun, so this is
            // a trailer field (RFC 9110 section 6.5)
            addTrailer(name, value);
            return;
        }
        headers.add(new Header(name, value));
    }

    private void addTrailer(String name, String value) {
        String lower = name.toLowerCase();
        if (lower.startsWith(":") || lower.equals("content-length")
                || lower.equals("transfer-encoding") || lower.equals("host")
                || lower.equals("trailer")) {
            throw new IllegalArgumentException(java.text.MessageFormat.format(
                    L10N.getString("err.trailer_not_allowed"), name));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException(L10N.getString("err.invalid_trailer_value"));
            }
        }
        if (trailers == null) {
            trailers = new Headers();
        }
        trailers.add(new Header(name, value));
    }

    @Override
    public void longHeader(String name, long value) {
        header(name, Long.toString(value));
    }

    @Override
    public void dateHeader(String name, Instant value) {
        header(name, new HttpDateFormat().format(value.toEpochMilli()));
    }

    @Override
    public void contentType(ContentType contentType) {
        header("content-type", contentType.toHeaderValue());
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) {
        header("content-disposition", contentDisposition.toHeaderValue());
    }

    /**
     * RFC 9218 section 4: sets the urgency parameter in the Priority
     * header field. The weight (0-255) is mapped to urgency (0-7)
     * where 0 is highest and 7 is lowest priority.
     */
    @Override
    public void priority(int weight) {
        int urgency = PriorityParams.urgencyFromWeight(weight);
        headers.add(new Header("priority", "u=" + urgency));
    }

    @Override
    public void dependency(HttpRequest parent) {
        // Not applicable to HTTP/3
    }

    @Override
    public void exclusive(boolean exclusive) {
        // Not applicable to HTTP/3
    }

    /**
     * Starts the request on the connection's own thread: the header section
     * is sent now, or once the connection can carry the request (see
     * {@link Http3ClientHandler#isSafeToSendNow}).
     *
     * @param endStream whether the request ends with its header section
     */
    private void startRequest(final boolean endStream) {
        final HttpResponseHandler handler = responseHandler;
        requestStarted = true;
        final Headers h3Headers = buildHeaders();
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable sendTask = new Runnable() {
                    @Override
                    public void run() {
                        sendDeferred = false;
                        h3Stream = h3Handler.startRequest(h3Headers, handler, endStream);
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
    public void endHeaders() {
        if (requestStarted) {
            throw new IllegalStateException(L10N.getString("err.headers_already_sent"));
        }
        if (cancelled) {
            return;
        }
        startRequest(false);
        if (firstChunk != null) {
            byte[] held = firstChunk;
            firstChunk = null;
            sendBody(held, false);
        }
    }

    @Override
    public int bodyContent(ByteBuffer data) {
        if (data == null) {
            return 0;
        }
        if (ended) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        if (cancelled) {
            return 0;
        }
        // Snapshot the remaining bytes synchronously, on the caller's own
        // thread, before returning -- the actual send is deferred to the
        // connection's own thread (see the class documentation), and the
        // caller is free to reuse/refill data the moment this call returns.
        int remaining = data.remaining();
        if (remaining == 0) {
            return 0;
        }
        final byte[] snapshot = new byte[remaining];
        data.get(snapshot);
        bodyStarted = true;
        if (!requestStarted && firstChunk == null) {
            // Held back until it is known whether it is also the last piece
            firstChunk = snapshot;
            return remaining;
        }
        if (!requestStarted) {
            startRequest(false);
        }
        if (firstChunk != null) {
            byte[] held = firstChunk;
            firstChunk = null;
            sendBody(held, false);
        }
        sendBody(snapshot, false);
        return remaining;
    }

    /** Sends a piece of body (and, with {@code fin}, ends the stream) on the connection's thread. */
    private void sendBody(final byte[] bytes, final boolean fin) {
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable bodyTask = new Runnable() {
                    @Override
                    public void run() {
                        if (h3Stream == null) {
                            return;
                        }
                        h3Handler.sendRequestBody(h3Stream, ByteBuffer.wrap(bytes), fin);
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
    }

    @Override
    public void endMessage() {
        if (ended) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        ended = true;
        if (cancelled) {
            return;
        }
        if (!requestStarted) {
            if (firstChunk == null) {
                startRequest(true);
                return;
            }
            byte[] only = firstChunk;
            firstChunk = null;
            if (trailers != null) {
                // trailers follow the body: it does not end the stream
                startRequest(false);
                sendBody(only, false);
                sendTrailers();
                return;
            }
            // The one piece of body is the whole body: say how long it is,
            // and send it with the end of the stream
            if (!containsHeader(headers, "content-length")
                    && !containsHeader(headers, "content-encoding")) {
                headers.add(new Header("content-length", Integer.toString(only.length)));
            }
            startRequest(false);
            sendBody(only, true);
            return;
        }
        if (trailers != null) {
            sendTrailers();
            return;
        }
        sendBody(new byte[0], true);
    }

    /** Ends the request with its trailer fields, on the connection's thread. */
    private void sendTrailers() {
        final Headers fields = trailers;
        h3Handler.execute(new Runnable() {
            @Override
            public void run() {
                Runnable trailerTask = new Runnable() {
                    @Override
                    public void run() {
                        if (h3Stream == null) {
                            return;
                        }
                        h3Handler.sendRequestTrailers(h3Stream, fields);
                    }
                };
                if (sendDeferred) {
                    h3Handler.deferUntilEstablished(trailerTask);
                } else {
                    trailerTask.run();
                }
            }
        });
    }

    @Override
    public void cancel() {
        cancelled = true;
        final HttpResponseHandler handler = responseHandler;
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
            Header h = headers.get(i);
            // RFC 9114 section 4.2: connection-specific header fields must
            // not be sent; a server treats such a request as malformed.
            String hname = h.getName();
            if (!"content-length".equalsIgnoreCase(hname)
                    && HttpVersion.isHttp1FramingHeader(hname, h.getValue())) {
                continue;
            }
            result.add(h);
        }
        h3Handler.applyDefaultAcceptEncoding(result);
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
