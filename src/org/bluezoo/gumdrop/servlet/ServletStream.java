/*
 * ServletStream.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.AbstractHTTPConnection;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Stream;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
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

    private int statusCode;
    private long contentLength;
    private Collection<Header> headers;
    private List<ByteBuffer> responseBody;
    private boolean responseComplete;

    protected ServletStream(AbstractHTTPConnection connection, int streamId, int bufferSize) {
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
        // System.err.println("endHeaders");
    }

    /**
     * Request body chunk
     */
    @Override protected void receiveRequestBody(byte[] buf) {
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
        // System.err.println("endRequest");
    }

    // Buffer responses until flush is called. Then notify
    // response-sending thread

    protected void sendError(int code) throws ProtocolException {
        response.reset();
        try {
            response.sendError(code);
        } catch (IOException e) {
            String message = ServletConnector.L10N.getString("error.send_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    // Called by worker thread
    void commit(int statusCode, Collection<Header> headers) {
        this.statusCode = statusCode;
        this.headers = headers;
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
                    sendResponseBody(buf, i.hasNext());
                }
            }
        } catch (ProtocolException e) {
            // stream in wrong state
            String message = ServletConnector.L10N.getString("error.protocol_error");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

}
