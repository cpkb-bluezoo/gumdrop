/*
 * WebSocketServletTransport.java
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

import org.bluezoo.gumdrop.http.websocket.WebSocketConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * WebSocket transport implementation for servlet-based WebSocket connections.
 * This class bridges the WebSocket transport interface with the servlet
 * HTTP connection infrastructure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebSocketServletTransport implements WebSocketConnection.WebSocketTransport {

    private static final Logger LOGGER = Logger.getLogger(WebSocketServletTransport.class.getName());

    private final ServletStream servletStream;
    private volatile boolean closed = false;

    /**
     * Creates a new WebSocket servlet transport.
     *
     * @param servletStream the servlet stream to use for transport
     */
    public WebSocketServletTransport(ServletStream servletStream) {
        this.servletStream = servletStream;
    }

    /**
     * Sends a WebSocket frame to the client.
     * This method directly sends the frame data through the servlet's
     * underlying HTTP connection.
     *
     * @param frameData the encoded WebSocket frame data
     * @throws IOException if an I/O error occurs or the connection is closed
     */
    @Override
    public void sendFrame(ByteBuffer frameData) throws IOException {
        if (closed) {
            throw new IOException("WebSocket transport is closed");
        }

        try {
            // Convert ByteBuffer to byte array for servlet response
            byte[] data = new byte[frameData.remaining()];
            frameData.get(data);
            
            // Send directly through the HTTP connection
            // In WebSocket mode, we bypass normal HTTP response processing
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
            servletStream.sendWebSocketFrameData(buffer);
            
        } catch (Exception e) {
            LOGGER.severe("Failed to send WebSocket frame: " + e.getMessage());
            throw new IOException("Failed to send WebSocket frame", e);
        }
    }

    /**
     * Closes the WebSocket transport.
     * This marks the transport as closed and performs any necessary cleanup.
     *
     * @throws IOException if an I/O error occurs during close
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            
            try {
                // Close the underlying stream using the WebSocket close method
                servletStream.closeWebSocket();
            } catch (Exception e) {
                LOGGER.warning("Error closing servlet stream: " + e.getMessage());
                // Don't throw here - just log the error
            }
        }
    }

    /**
     * Returns true if this transport is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Gets the underlying servlet stream.
     *
     * @return the servlet stream
     */
    public ServletStream getServletStream() {
        return servletStream;
    }
}
