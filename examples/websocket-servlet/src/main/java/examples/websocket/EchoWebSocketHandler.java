/*
 * EchoWebSocketHandler.java
 * WebSocket Servlet Example for Gumdrop Server
 *
 * This example demonstrates how to create a WebSocket handler using
 * the Servlet 4.0 HttpUpgradeHandler API with Gumdrop's WebSocket support.
 */

package examples.websocket;

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Example WebSocket handler that echoes received messages back to the client.
 * Demonstrates the basic WebSocket upgrade pattern using Servlet 4.0 APIs.
 *
 * <p>Gumdrop dispatches {@link #init(WebConnection)} onto the servlet worker
 * pool (virtual threads), so this handler runs its read/write loop directly
 * without starting a separate thread.
 *
 * <p>This handler:
 * <ul>
 * <li>Accepts WebSocket connections via servlet upgrade</li>
 * <li>Reads incoming WebSocket data through {@code ServletInputStream}</li>
 * <li>Echoes messages back through {@code ServletOutputStream}</li>
 * <li>Handles connection lifecycle ({@code init}, {@code destroy})</li>
 * </ul>
 */
public class EchoWebSocketHandler implements HttpUpgradeHandler {

    private static final Logger LOGGER =
            Logger.getLogger(EchoWebSocketHandler.class.getName());

    private volatile WebConnection webConnection;
    private volatile boolean active;

    /**
     * Called when the HTTP connection is upgraded to WebSocket.
     * Runs the echo loop on the container's servlet worker thread.
     *
     * @param webConnection the upgraded web connection
     */
    @Override
    public void init(WebConnection webConnection) {
        this.webConnection = webConnection;
        this.active = true;

        LOGGER.info("WebSocket connection established, starting echo handler");

        try {
            handleWebSocketCommunication();
        } finally {
            active = false;
        }
    }

    /**
     * Called when the WebSocket connection is being closed.
     * Unblocks any read still running in {@link #init(WebConnection)}.
     */
    @Override
    public void destroy() {
        active = false;
        closeConnection();
        LOGGER.info("WebSocket connection closed and resources cleaned up");
    }

    private void handleWebSocketCommunication() {
        WebConnection connection = webConnection;
        if (connection == null) {
            return;
        }

        try (InputStream input = connection.getInputStream();
             OutputStream output = connection.getOutputStream()) {

            byte[] buffer = new byte[4096];

            while (active) {
                int bytesRead = input.read(buffer);
                if (bytesRead == -1) {
                    LOGGER.info("Client closed WebSocket connection");
                    break;
                }
                if (bytesRead <= 0) {
                    continue;
                }

                String received = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                String response = "Echo: " + received;

                LOGGER.fine("Received: " + received.trim() + ", echoing back");

                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

        } catch (IOException e) {
            if (active) {
                LOGGER.log(Level.WARNING, "WebSocket I/O error", e);
            }
        }
    }

    private void closeConnection() {
        WebConnection connection = webConnection;
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error closing WebSocket connection", e);
        }
    }
}
