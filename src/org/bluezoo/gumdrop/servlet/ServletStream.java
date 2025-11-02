/*
 * ServletStream.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.HTTPConnection;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.servlet.ReadListener;

/**
 * A servlet stream represents a single request/response pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletStream extends Stream {

    static final Logger LOGGER = Logger.getLogger(ServletStream.class.getName());

    final ServletConnection connection;
    int bufferSize;
    Request request;
    Response response;
    boolean explicitCloseConnection;
    
    // WebSocket support
    private ServletWebSocketConnection webSocketConnection;
    private boolean webSocketMode = false;

    private int statusCode;
    private long contentLength;
    private List<Header> headers;
    private List<ByteBuffer> responseBody;
    private boolean requestComplete, responseComplete;

    protected ServletStream(HTTPConnection connection, int streamId, int bufferSize) {
        super(connection, streamId);
        this.connection = (ServletConnection) connection;
        this.bufferSize = bufferSize;
    }

    boolean isResponseComplete() {
        return responseComplete;
    }

    /**
     * Request headers
     */
    @Override protected void endHeaders(Collection<Header> headers) {
        // System.err.println("endHeaders");
        String method = null;
        String requestTarget = null;
        // Extract method and requestTarget, create headers for request
        Collection<Header> requestHeaders = new ArrayList<>(headers.size());
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
            request = new Request(this, bufferSize, method, requestTarget, requestHeaders);
            response = new Response(this, request, bufferSize);
            connection.serviceRequest(this); // ready for servicing
        } catch (IOException e) {
            String message = ServletConnector.L10N.getString("error.create_pipe");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    /**
     * Request body chunk
     */
    @Override protected void receiveRequestBody(byte[] buf) {
        if (webSocketMode && webSocketConnection != null) {
            // In WebSocket mode, process incoming data as WebSocket frames
            try {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(buf);
                webSocketConnection.processIncomingData(buffer);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error processing WebSocket data", e);
                webSocketConnection.onError(e);
            }
            return;
        }

        if (buf.length > 0) {
            ReadListener readListener = request.in.readListener;
            try {
                request.pipe.write(buf);
                if (readListener != null) {
                    readListener.onDataAvailable();
                }
            } catch (IOException e) {
                String message = ServletConnector.L10N.getString("error.write_pipe");
                LOGGER.log(Level.SEVERE, message, e);
                if (readListener != null) {
                    readListener.onError(e);
                }
            }
            // System.err.println("handleBody "+buf.length);
        }
    }

    /**
     * End request
     */
    @Override protected void endRequest() {
        explicitCloseConnection = isCloseConnection();
        ReadListener readListener = request.in.readListener;
        try {
            request.pipe.close(); // end of stream for request handling worker thread
            request.in.finished.set(true);
            if (readListener != null) {
                readListener.onAllDataRead();
            }
        } catch (IOException e) {
            String message = ServletConnector.L10N.getString("error.close_pipe");
            LOGGER.log(Level.SEVERE, message, e);
            if (readListener != null) {
                readListener.onError(e);
            }
        }
        requestComplete = true;
        // System.err.println("endRequest");
        
        // In WebSocket mode, don't close the request - keep connection open for frames
        if (webSocketMode) {
            return;
        }
    }

    /**
     * Sends the WebSocket upgrade response (101 Switching Protocols).
     *
     * @param responseHeaders the WebSocket response headers
     * @throws IOException if an I/O error occurs
     */
    void sendWebSocketUpgradeResponse(java.util.List<org.bluezoo.gumdrop.http.Header> responseHeaders) throws IOException {
        try {
            // Send 101 Switching Protocols response through the underlying HTTP connection
            // Use the Stream's sendResponseHeaders method
            sendResponseHeaders(101, responseHeaders, false);
        } catch (Exception e) {
            throw new IOException("Failed to send WebSocket upgrade response", e);
        }
    }

    /**
     * Switches this stream to WebSocket mode.
     *
     * @param webSocketConnection the WebSocket connection to use
     */
    void switchToWebSocketMode(ServletWebSocketConnection webSocketConnection) {
        this.webSocketConnection = webSocketConnection;
        this.webSocketMode = true;
        
        // Mark response as committed to prevent further HTTP response operations
        response.committed = true;
        
        LOGGER.fine("Stream switched to WebSocket mode");
    }

    /**
     * Returns true if this stream is in WebSocket mode.
     *
     * @return true if in WebSocket mode
     */
    boolean isWebSocketMode() {
        return webSocketMode;
    }

    /**
     * Returns true if the HTTP response has been started.
     *
     * @return true if response started
     */
    boolean isResponseStarted() {
        return response != null && response.committed;
    }

    /**
     * Sends WebSocket frame data directly to the connection.
     * This is used by the WebSocket transport to send frames after upgrade.
     *
     * @param data the frame data to send
     * @throws IOException if an I/O error occurs
     */
    void sendWebSocketFrameData(ByteBuffer data) throws IOException {
        try {
            // In WebSocket mode, send data directly through the connection
            sendResponseBody(data, false);
        } catch (Exception e) {
            throw new IOException("Failed to send WebSocket frame data", e);
        }
    }

    /**
     * Closes the WebSocket stream.
     * This is used by the WebSocket transport when the connection is closed.
     */
    void closeWebSocket() {
        try {
            // Use the protected close() method from Stream
            close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing WebSocket stream", e);
        }
    }

    boolean isTrailerFieldsReady() {
        for (Header header : headers) {
            if ("Transfer-Encoding".equalsIgnoreCase(header.getName())) {
                return requestComplete;
            }
        }
        return true;
    }

    Map<String,String> getTrailerFields() {
        Map<String,String> trailerFields = new LinkedHashMap<>();
        if (trailerHeaders != null) {
            for (Header header : trailerHeaders) {
                trailerFields.put(header.getName().toLowerCase(), header.getValue());
            }
        }
        return trailerFields;
    }

    // Buffer responses until flush is called. Then notify
    // response-sending thread

    protected void sendError(int code) throws ProtocolException {
        if (response == null) {
            super.sendError(code);
            return;
        }
        response.reset();
        try {
            // This will invoke service on the error page if defined
            response.sendError(code);
        } catch (IOException e) {
            String message = ServletConnector.L10N.getString("error.send_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    // Called by worker thread
    void commit(int statusCode, List<Header> headers) {
        this.statusCode = statusCode;
        this.headers = headers;

        HitStatisticsImpl hitStatistics = request.context.hitStatistics;
        synchronized (hitStatistics) {
            hitStatistics.addHit(statusCode);
        }
    }

    // Called by worker thread
    void writeBody(ByteBuffer buf) {
        if (responseBody == null) {
            responseBody = new ArrayList<>();
        }
        responseBody.add(buf.duplicate());
        contentLength += (long) buf.remaining();
    }

    // called by worker thread
    void endResponse() {
        responseComplete = true;
        connection.responseFlushed();
        if (explicitCloseConnection) {
            sendCloseConnection();
        }
    }

    /// Called by ResponseSender thread
    void sendResponse() {
        try {
            boolean endStream = (contentLength == 0L);
            // We could add Content-Length here if the servlet didn't do it?
            sendResponseHeaders(statusCode, headers, endStream);
            if (!endStream) {
                for (Iterator<ByteBuffer> i = responseBody.iterator(); i.hasNext(); ) {
                    ByteBuffer buf = i.next();
                    sendResponseBody(buf, !i.hasNext());
                }
            }
        } catch (ProtocolException e) {
            // stream in wrong state
            String message = ServletConnector.L10N.getString("error.protocol_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

}
