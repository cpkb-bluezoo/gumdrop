/*
 * EchoWebSocketHandler.java
 * WebSocket Servlet Example for Gumdrop Server
 * 
 * This example demonstrates how to create a WebSocket handler using 
 * the Servlet 4.0 HttpUpgradeHandler API with Gumdrop's WebSocket support.
 */

package examples.websocket;

import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;
import java.io.*;
import java.util.logging.Logger;

/**
 * Example WebSocket handler that echoes received messages back to the client.
 * Demonstrates the basic WebSocket upgrade pattern using Servlet 4.0 APIs.
 * 
 * <p>This handler:
 * <ul>
 * <li>Accepts WebSocket connections via servlet upgrade</li>
 * <li>Reads incoming WebSocket data through ServletInputStream</li>
 * <li>Echoes messages back through ServletOutputStream</li>
 * <li>Handles connection lifecycle (init, destroy)</li>
 * </ul>
 */
public class EchoWebSocketHandler implements HttpUpgradeHandler {
    
    private static final Logger LOGGER = Logger.getLogger(EchoWebSocketHandler.class.getName());
    
    private WebConnection webConnection;
    private volatile boolean active = false;
    
    /**
     * Called when the HTTP connection is upgraded to WebSocket.
     * Starts a background thread to handle WebSocket communication.
     *
     * @param webConnection the upgraded web connection
     */
    @Override
    public void init(WebConnection webConnection) {
        this.webConnection = webConnection;
        this.active = true;
        
        LOGGER.info("WebSocket connection established, starting echo handler");
        
        // Start background thread to handle WebSocket I/O
        Thread handlerThread = new Thread(this::handleWebSocketCommunication, "WebSocket-Echo-Handler");
        handlerThread.setDaemon(true);
        handlerThread.start();
    }
    
    /**
     * Called when the WebSocket connection is being closed.
     * Performs cleanup and resource release.
     */
    @Override
    public void destroy() {
        active = false;
        
        try {
            if (webConnection != null) {
                webConnection.close();
            }
        } catch (IOException e) {
            LOGGER.warning("Error closing WebSocket connection: " + e.getMessage());
        }
        
        LOGGER.info("WebSocket connection closed and resources cleaned up");
    }
    
    /**
     * Main WebSocket communication loop.
     * Reads data from the WebSocket and echoes it back to the client.
     */
    private void handleWebSocketCommunication() {
        try (InputStream input = webConnection.getInputStream();
             OutputStream output = webConnection.getOutputStream()) {
            
            byte[] buffer = new byte[4096];
            
            while (active) {
                try {
                    // Read WebSocket data 
                    int bytesRead = input.read(buffer);
                    
                    if (bytesRead == -1) {
                        // End of stream - client closed connection
                        LOGGER.info("Client closed WebSocket connection");
                        break;
                    }
                    
                    if (bytesRead > 0) {
                        // Echo the data back to client
                        String received = new String(buffer, 0, bytesRead, "UTF-8");
                        String response = "Echo: " + received;
                        
                        LOGGER.fine("Received: " + received.trim() + ", echoing back");
                        
                        output.write(response.getBytes("UTF-8"));
                        output.flush();
                    }
                    
                } catch (IOException e) {
                    if (active) {
                        LOGGER.warning("WebSocket I/O error: " + e.getMessage());
                    }
                    break;
                }
            }
            
        } catch (IOException e) {
            LOGGER.severe("Failed to establish WebSocket I/O streams: " + e.getMessage());
        } finally {
            active = false;
        }
    }
}
