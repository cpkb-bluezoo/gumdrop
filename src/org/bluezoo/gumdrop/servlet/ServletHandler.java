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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * blocking servlet API. Request body data is delivered via a pipe that
 * the servlet reads from, and response data is buffered and sent via
 * {@link HTTPResponseState}.
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

    // Pipe for delivering request body to servlet
    private PipedOutputStream pipe;
    private PipedInputStream pipeIn;

    // Servlet request/response
    private Request request;
    private Response response;

    // Request state
    private AtomicBoolean requestFinished = new AtomicBoolean(false);
    private ReadListener readListener;
    private Map<String, String> requestTrailerFields;

    // Response buffering
    private boolean closeConnection;
    private int statusCode;
    private Headers responseHeaders;
    private List<ByteBuffer> responseBody;
    private long contentLength;
    private boolean responseComplete;
    private Supplier<Map<String, String>> trailerFieldsSupplier;

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
            // Create the pipe for request body delivery
            pipe = new PipedOutputStream();
            pipeIn = new PipedInputStream(pipe, bufferSize);

            // Create Request and Response
            request = new Request(this, bufferSize, method, requestTarget, requestHeaders, pipeIn);
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
        if (pipe == null) {
            return;
        }

        try {
            byte[] buf = new byte[data.remaining()];
            data.get(buf);
            pipe.write(buf);

            // Notify ReadListener if registered
            if (readListener != null) {
                readListener.onDataAvailable();
            }
        } catch (IOException e) {
            String message = ServletService.L10N.getString("error.write_pipe");
            LOGGER.log(Level.SEVERE, message, e);
            if (readListener != null) {
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
        // Close the pipe - signals EOF to servlet's InputStream
        if (pipe != null) {
            try {
                pipe.close();
            } catch (IOException e) {
                String message = ServletService.L10N.getString("error.close_pipe");
                LOGGER.log(Level.SEVERE, message, e);
            }
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

    void writeBody(ByteBuffer buf) {
        if (responseBody == null) {
            responseBody = new ArrayList<ByteBuffer>();
        }
        // Must deep copy - duplicate() shares the backing array which gets reused
        int length = buf.remaining();
        ByteBuffer copy = ByteBuffer.allocate(length);
        copy.put(buf);
        copy.flip();
        responseBody.add(copy);
        contentLength += (long) length;
    }

    void endResponse() {
        responseComplete = true;
        sendResponse();
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
     */
    private void sendResponse() {
        SelectorLoop loop = state.getSelectorLoop();
        if (loop == null) {
            sendResponseDirect();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            public void run() {
                try {
                    sendResponseDirect();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendResponseDirect() {
        try {
            Headers headers = new Headers();
            headers.status(HTTPStatus.fromCode(statusCode));
            if (responseHeaders != null) {
                for (Header header : responseHeaders) {
                    headers.add(header);
                }
            }

            boolean hasTrailerFields = false;
            Map<String, String> trailerFields = null;
            if (trailerFieldsSupplier != null) {
                try {
                    trailerFields = trailerFieldsSupplier.get();
                    hasTrailerFields = (trailerFields != null && !trailerFields.isEmpty());
                } catch (Exception e) {
                    LOGGER.warning("Error getting trailer fields: " + e.getMessage());
                }
            }

            state.headers(headers);

            if (responseBody != null && !responseBody.isEmpty()) {
                state.startResponseBody();
                for (ByteBuffer buf : responseBody) {
                    state.responseBodyContent(buf);
                }
                state.endResponseBody();

                if (hasTrailerFields) {
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
