/*
 * ServletHandler.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ReadListener;

/**
 * HTTP request handler for the servlet container.
 *
 * <p>This handler bridges the async, event-driven HTTP layer with the
 * blocking servlet API. Request body data is delivered via a {@link
 * RequestBodyStream} that the servlet reads from (applying backpressure —
 * {@code pauseRequestBody()}/{@code resumeRequestBody()} — rather than
 * blocking the SelectorLoop thread when the servlet reads slower than the
 * network delivers), and response body data is streamed to {@link
 * HTTPResponseState} as the servlet writes it, rather than buffered in
 * full (issue #120).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(ServletHandler.class.getName());

    private final ServletService service;
    private final Container container;
    private final int bufferSize;

    // The HTTP response state - provides connection info and response sending
    private HTTPResponseState state;

    // Non-blocking bridge for delivering request body to the servlet
    private RequestBodyStream bodyStream;

    // Servlet request/response
    private Request request;
    private Response response;

    // Request state
    private AtomicBoolean requestFinished = new AtomicBoolean(false);
    private ReadListener readListener;
    private Map<String, String> requestTrailerFields;

    // Response state
    private boolean closeConnection;
    private int statusCode;
    private Headers responseHeaders;
    private long contentLength;
    private boolean responseComplete;
    private Supplier<Map<String, String>> trailerFieldsSupplier;

    // Streaming dispatch state: headers/body-start are each sent to the
    // network at most once, lazily, on first use (see ensureHeadersSent()/
    // ensureBodyStarted()) rather than deferred to endResponse().
    private boolean headersSent;
    private boolean bodyStarted;

    ServletHandler(ServletService service, Container container, int bufferSize) {
        this.service = service;
        this.container = container;
        this.bufferSize = bufferSize;
    }

    /**
     * Returns the servlet service.
     */
    ServletService getService() {
        return service;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPRequestHandler implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void headers(HTTPResponseState state, Headers headers) {
        this.state = state;

        // Check if this is trailer headers (after body)
        if (request != null && requestFinished.get()) {
            // These are trailer headers - store them
            requestTrailerFields = new LinkedHashMap<String, String>();
            for (Header header : headers) {
                String name = header.getName();
                if (name.charAt(0) != ':') {
                    requestTrailerFields.put(name.toLowerCase(), header.getValue());
                }
            }
            return;
        }

        // Extract method and request target from pseudo-headers
        String method = null;
        String requestTarget = null;
        Headers requestHeaders = new Headers(headers.size());
        for (Header header : headers) {
            String name = header.getName();
            String value = header.getValue();
            if (":method".equals(name)) {
                method = value;
            } else if (":path".equals(name)) {
                requestTarget = value;
            } else if (name.charAt(0) != ':') {
                requestHeaders.add(header);
            }
        }

        try {
            // Non-blocking bridge for request body delivery. write() (via
            // offer()) never blocks the SelectorLoop thread; it applies
            // backpressure through pauseRequestBody()/resumeRequestBody()
            // instead.
            bodyStream = new RequestBodyStream();
            bodyStream.setResumeCallback(new Runnable() {
                @Override
                public void run() {
                    // May be called from the worker thread (inside
                    // RequestBodyStream.read()); resumeRequestBody() must
                    // run on the SelectorLoop thread.
                    ServletHandler.this.state.execute(new Runnable() {
                        @Override
                        public void run() {
                            ServletHandler.this.state.resumeRequestBody();
                        }
                    });
                }
            });

            // Create Request and Response
            request = new Request(this, bufferSize, method, requestTarget, requestHeaders, bodyStream);
            response = new Response(this, request, bufferSize);

            // Dispatch to worker thread for servlet execution
            service.serviceRequest(this);

        } catch (IOException e) {
            String message = ServletService.L10N.getString("error.create_pipe");
            LOGGER.log(Level.SEVERE, message, e);
            sendError(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
        if (bodyStream == null) {
            return;
        }

        byte[] buf = new byte[data.remaining()];
        data.get(buf);
        // Already running on the SelectorLoop thread here, so
        // pauseRequestBody() can be called directly.
        boolean shouldPause = bodyStream.offer(buf);
        if (shouldPause) {
            state.pauseRequestBody();
        }

        // Notify ReadListener if registered
        if (readListener != null) {
            try {
                readListener.onDataAvailable();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error notifying ReadListener", e);
                readListener.onError(e);
            }
        }
    }

    @Override
    public void endRequestBody(HTTPResponseState state) {
        requestFinished.set(true);
    }

    @Override
    public void requestComplete(HTTPResponseState state) {
        // Signal EOF to the servlet's InputStream
        if (bodyStream != null) {
            bodyStream.finish();
        }

        // Notify ReadListener if registered
        if (readListener != null) {
            try {
                readListener.onAllDataRead();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error notifying ReadListener", e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors for Request/Response
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the HTTPResponseState for this request.
     * This provides connection info, TLS info, and response sending.
     */
    HTTPResponseState getState() {
        return state;
    }

    Request getRequest() {
        return request;
    }

    Response getResponse() {
        return response;
    }

    Container getContainer() {
        return container;
    }

    /**
     * Returns request trailer fields, or empty map if none.
     */
    Map<String, String> getRequestTrailerFields() {
        return requestTrailerFields != null ? requestTrailerFields : Collections.emptyMap();
    }

    /**
     * Returns true if request body has finished.
     */
    boolean isRequestFinished() {
        return requestFinished.get();
    }

    void setReadListener(ReadListener listener) {
        this.readListener = listener;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response operations (called by Response on worker thread)
    // ─────────────────────────────────────────────────────────────────────────

    boolean isCloseConnection() {
        return closeConnection;
    }

    void setCloseConnection(boolean close) {
        this.closeConnection = close;
    }

    boolean isResponseStarted() {
        return response != null && response.committed;
    }

    void commit(int statusCode, Headers headers) {
        this.statusCode = statusCode;
        this.responseHeaders = headers;

        // Update hit statistics
        if (request.context != null) {
            HitStatisticsImpl hitStatistics = request.context.hitStatistics;
            synchronized (hitStatistics) {
                hitStatistics.addHit(statusCode);
            }
        }
    }

    // Above this many buffered-but-unsent bytes for the stream, writeBody()
    // blocks the calling (worker) thread until the transport drains below
    // it, rather than letting the transport's pending-data queue grow
    // without bound while a slow/unresponsive peer never opens its
    // flow-control window (issue #123).
    private static final int PENDING_RESPONSE_HIGH_WATERMARK = 4 * 1024 * 1024;

    void writeBody(ByteBuffer buf) {
        // Must deep copy - duplicate() shares the backing array which gets reused
        int length = buf.remaining();
        final ByteBuffer copy = ByteBuffer.allocate(length);
        copy.put(buf);
        copy.flip();
        contentLength += (long) length;

        ensureBodyStarted();
        if (state.pendingResponseBytes() > PENDING_RESPONSE_HIGH_WATERMARK) {
            awaitWritable();
        }
        // Fire-and-forget: state.execute() preserves submission order (it
        // is backed by the connection's SelectorLoop task queue), so this
        // chunk is guaranteed to be sent before any later writeBody() call
        // or the final endResponse() completion, without the worker
        // thread needing to wait for each individual chunk in the normal
        // case.
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.responseBodyContent(copy);
            }
        });
    }

    // How long writeBody() will block waiting for the transport to drain
    // before giving up on a stalled/dead peer and cancelling the stream.
    private static final long PENDING_RESPONSE_WAIT_TIMEOUT_MS = 30000L;

    /**
     * Blocks the calling (worker) thread until the transport signals it
     * can accept more response body data, or {@link
     * #PENDING_RESPONSE_WAIT_TIMEOUT_MS} elapses. Marshalled through
     * {@code state.execute()} since {@code onWritable()} must be called
     * on the connection's SelectorLoop thread.
     *
     * <p>A timeout means the peer has stopped acknowledging data for
     * that long (e.g. a dead connection that hasn't been detected yet) —
     * the stream is cancelled rather than leaving the worker thread
     * blocked indefinitely.
     */
    private void awaitWritable() {
        final CountDownLatch latch = new CountDownLatch(1);
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.onWritable(new Runnable() {
                    @Override
                    public void run() {
                        latch.countDown();
                    }
                });
            }
        });
        try {
            if (!latch.await(PENDING_RESPONSE_WAIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                LOGGER.log(Level.WARNING,
                        "Timed out waiting for response backpressure to clear; cancelling stream");
                state.execute(new Runnable() {
                    @Override
                    public void run() {
                        state.onWritable(null);
                        state.cancel();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends response headers to the network, at most once, lazily. */
    private synchronized void ensureHeadersSent() {
        if (headersSent) {
            return;
        }
        headersSent = true;
        final Headers headers = buildResponseHeaders();
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.headers(headers);
            }
        });
    }

    /**
     * Sends response headers (if not already sent) and signals the start
     * of the response body, at most once, lazily on the first {@link
     * #writeBody(ByteBuffer)} call.
     */
    private synchronized void ensureBodyStarted() {
        ensureHeadersSent();
        if (bodyStarted) {
            return;
        }
        bodyStarted = true;
        state.execute(new Runnable() {
            @Override
            public void run() {
                state.startResponseBody();
            }
        });
    }

    private Headers buildResponseHeaders() {
        Headers headers = new Headers();
        headers.status(HTTPStatus.fromCode(statusCode));
        if (responseHeaders != null) {
            for (Header header : responseHeaders) {
                headers.add(header);
            }
        }
        return headers;
    }

    void endResponse() {
        endResponse(null);
    }

    /**
     * Finishes the response, invoking {@code onComplete} (if non-null)
     * once the network send has actually finished, rather than blocking
     * the calling (worker pool) thread until then.
     *
     * @param onComplete callback run after the response has been sent, or
     *      null; may run on the connection's SelectorLoop thread
     */
    void endResponse(Runnable onComplete) {
        responseComplete = true;
        // Covers the empty-body case (headers were never sent because
        // writeBody() was never called).
        ensureHeadersSent();
        sendResponse(onComplete);
    }

    Supplier<Map<String, String>> getTrailerFieldsSupplier() {
        return trailerFieldsSupplier;
    }

    void setTrailerFieldsSupplier(Supplier<Map<String, String>> supplier) {
        this.trailerFieldsSupplier = supplier;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server push support
    // ─────────────────────────────────────────────────────────────────────────

    boolean supportsServerPush() {
        return true; // Let pushPromise determine actual support
    }

    boolean executePush(String method, String uri, Headers headers) {
        Headers pushHeaders = new Headers();
        pushHeaders.add(":method", method);
        pushHeaders.add(":path", uri);
        pushHeaders.add(":scheme", state.getScheme());
        for (Header h : headers) {
            pushHeaders.add(h);
        }
        return state.pushPromise(pushHeaders);
    }

    /**
     * Sheds this request with a 503 Service Unavailable response.
     *
     * <p>Called by {@link ServletService#serviceRequest} on the SelectorLoop
     * thread when the worker pool and its bounded queue are both saturated,
     * providing backpressure instead of unbounded queueing.
     */
    void serviceUnavailable() {
        sendError(HTTPStatus.SERVICE_UNAVAILABLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private methods
    // ─────────────────────────────────────────────────────────────────────────

    private void sendError(HTTPStatus status) {
        Headers headers = new Headers();
        headers.status(status);
        headers.add("Content-Length", "0");
        state.headers(headers);
        state.complete();
    }

    /**
     * Sends the buffered response via {@link HTTPResponseState}.
     *
     * <p>If the response state is owned by a SelectorLoop and we are not
     * on that thread, the actual send is marshalled onto the SelectorLoop
     * via {@link SelectorLoop#invokeLater(Runnable)} so that transport
     * implementations (e.g. HTTP/3 / QUIC) that are not thread-safe are
     * only ever called from their owning I/O thread.
     *
     * <p>Fire-and-forget: the calling (worker pool) thread is not blocked
     * waiting for the send to finish, including any TLS/socket work that
     * entails — worker threads are the scarce, bounded resource, and
     * parking one per in-flight response for the duration of a network
     * write (which depends on the client's download speed) halves usable
     * pool capacity under load. {@code onComplete}, if non-null, runs once
     * the send has actually finished, standing in for whatever
     * worker-thread-owned cleanup used to run immediately after the old
     * blocking call.
     *
     * @param onComplete callback run after the response has been sent, or
     *      null; may run on the connection's SelectorLoop thread, never on
     *      the calling thread
     */
    private void sendResponse(final Runnable onComplete) {
        SelectorLoop loop = state.getSelectorLoop();
        if (loop == null) {
            try {
                sendResponseDirect();
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
            return;
        }
        loop.invokeLater(new Runnable() {
            public void run() {
                try {
                    sendResponseDirect();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            }
        });
    }

    /**
     * Finishes the response. Headers and any body content have already
     * been streamed to the network as the servlet produced them (see
     * {@link #ensureHeadersSent()}, {@link #ensureBodyStarted()}, {@link
     * #writeBody(ByteBuffer)}) — this only needs to close out the body
     * (if one was started), send trailers, and complete the response.
     */
    private void sendResponseDirect() {
        try {
            if (bodyStarted) {
                state.endResponseBody();

                Map<String, String> trailerFields = null;
                if (trailerFieldsSupplier != null) {
                    try {
                        trailerFields = trailerFieldsSupplier.get();
                    } catch (Exception e) {
                        LOGGER.warning("Error getting trailer fields: " + e.getMessage());
                    }
                }
                if (trailerFields != null && !trailerFields.isEmpty()) {
                    Headers trailers = new Headers();
                    for (Map.Entry<String, String> entry : trailerFields.entrySet()) {
                        trailers.add(entry.getKey(), entry.getValue());
                    }
                    state.headers(trailers);
                }
            }

            state.complete();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending response", e);
            state.cancel();
        }
    }

}
