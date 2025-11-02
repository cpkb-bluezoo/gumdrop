/*
 * WebSocketServletTransport.java
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
