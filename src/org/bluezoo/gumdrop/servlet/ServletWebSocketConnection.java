/*
 * ServletWebSocketConnection.java
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

import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket connection implementation that bridges between the Gumdrop WebSocket
 * infrastructure and the Servlet 4.0 HttpUpgradeHandler API.
 * 
 * <p>This class handles the integration between:
 * <ul>
 * <li>Gumdrop's WebSocket protocol implementation</li>
 * <li>Servlet container's upgrade mechanism</li> 
 * <li>Application-provided HttpUpgradeHandler implementations</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletWebSocketConnection extends WebSocketConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletWebSocketConnection.class.getName());

    private final HttpUpgradeHandler upgradeHandler;
    private final ServletWebConnection webConnection;

    /**
     * Creates a new servlet WebSocket connection.
     *
     * @param upgradeHandler the application's upgrade handler
     * @param transport the WebSocket transport
     */
    public ServletWebSocketConnection(HttpUpgradeHandler upgradeHandler, 
                                    WebSocketServletTransport transport) {
        this.upgradeHandler = upgradeHandler;
        this.webConnection = new ServletWebConnection(transport);
        
        // Set the transport for the WebSocket connection
        setTransport(transport);
    }

    /**
     * Called when the WebSocket connection is successfully established.
     * Initializes the servlet upgrade handler.
     */
    @Override
    protected void onOpen() {
        try {
            // Initialize the servlet upgrade handler
            upgradeHandler.init(webConnection);
            LOGGER.fine("WebSocket connection opened, handler initialized");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initializing upgrade handler", e);
            onError(e);
        }
    }

    /**
     * Called when a text message is received.
     * This is handled by the WebConnection implementation which
     * provides the data to the upgrade handler through input streams.
     *
     * @param message the received text message
     */
    @Override
    protected void onMessage(String message) {
        // Text messages are handled through the WebConnection's input stream
        webConnection.deliverTextMessage(message);
    }

    /**
     * Called when a binary message is received.
     * This is handled by the WebConnection implementation which
     * provides the data to the upgrade handler through input streams.
     *
     * @param data the received binary data
     */
    @Override
    protected void onMessage(byte[] data) {
        // Binary messages are handled through the WebConnection's input stream  
        webConnection.deliverBinaryMessage(data);
    }

    /**
     * Called when the WebSocket connection is closed.
     * Performs cleanup and notifies the upgrade handler.
     *
     * @param code the close code
     * @param reason the close reason (may be null or empty)
     */
    @Override
    protected void onClose(int code, String reason) {
        try {
            // Notify the web connection of closure
            webConnection.notifyClose(code, reason);
            
            // Destroy the upgrade handler
            upgradeHandler.destroy();
            
            LOGGER.fine(String.format("WebSocket connection closed: code=%d, reason=%s", code, reason));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during WebSocket close", e);
        }
    }

    /**
     * Called when an error occurs on the WebSocket connection.
     * Notifies the upgrade handler and performs error cleanup.
     *
     * @param error the error that occurred
     */
    @Override
    protected void onError(Throwable error) {
        try {
            // Notify the web connection of the error
            webConnection.notifyError(error);
            
            LOGGER.log(Level.WARNING, "WebSocket connection error", error);
            
            // Close the connection if not already closed
            if (getState() != State.CLOSED) {
                try {
                    close(CloseCodes.INTERNAL_ERROR, "Internal error");
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing connection after error", e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in error handler", e);
        }
    }

    /**
     * Gets the servlet upgrade handler associated with this connection.
     *
     * @return the upgrade handler
     */
    public HttpUpgradeHandler getUpgradeHandler() {
        return upgradeHandler;
    }

    /**
     * Gets the servlet web connection associated with this WebSocket.
     *
     * @return the web connection
     */
    public ServletWebConnection getWebConnection() {
        return webConnection;
    }
}
