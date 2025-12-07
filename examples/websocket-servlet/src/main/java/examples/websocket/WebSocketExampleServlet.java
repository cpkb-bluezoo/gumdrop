/*
 * WebSocketExampleServlet.java
 * WebSocket Servlet Example for Gumdrop Server
 * 
 * This servlet demonstrates how to upgrade HTTP connections to WebSocket
 * using the Servlet 4.0 API with Gumdrop's WebSocket implementation.
 */

package examples.websocket;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * Example servlet that provides WebSocket upgrade functionality.
 * 
 * <p>This servlet:
 * <ul>
 * <li>Serves a simple HTML page with WebSocket client code (GET)</li>
 * <li>Handles WebSocket upgrade requests (GET with upgrade headers)</li>
 * <li>Demonstrates proper WebSocket handshake validation</li>
 * <li>Shows integration with Servlet 4.0 HttpUpgradeHandler API</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong>
 * <ol>
 * <li>Deploy this servlet in a Gumdrop servlet container</li>
 * <li>Navigate to the servlet URL in a WebSocket-capable browser</li>
 * <li>The page will automatically connect via WebSocket</li>
 * <li>Type messages to see them echoed back</li>
 * </ol>
 */
public class WebSocketExampleServlet extends HttpServlet {
    
    private static final Logger LOGGER = Logger.getLogger(WebSocketExampleServlet.class.getName());
    
    /**
     * Handles GET requests - serves WebSocket test page or upgrades to WebSocket.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // Check if this is a WebSocket upgrade request
        String upgrade = request.getHeader("Upgrade");
        String connection = request.getHeader("Connection");
        
        if ("websocket".equalsIgnoreCase(upgrade) && 
            connection != null && connection.toLowerCase().contains("upgrade")) {
            
            // This is a WebSocket upgrade request
            handleWebSocketUpgrade(request, response);
            
        } else {
            // Regular HTTP request - serve the WebSocket test page
            serveTestPage(request, response);
        }
    }
    
    /**
     * Handles WebSocket upgrade requests.
     */
    private void handleWebSocketUpgrade(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        LOGGER.info("WebSocket upgrade request received from " + request.getRemoteAddr());
        
        try {
            // Upgrade the connection to WebSocket using our echo handler
            EchoWebSocketHandler handler = request.upgrade(EchoWebSocketHandler.class);
            
            LOGGER.info("WebSocket upgrade successful, handler initialized");
            
        } catch (Exception e) {
            LOGGER.severe("WebSocket upgrade failed: " + e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "WebSocket upgrade failed");
        }
    }
    
    /**
     * Serves a simple HTML page with WebSocket client test code.
     */
    private void serveTestPage(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>Gumdrop WebSocket Example</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; }");
            out.println("        .container { max-width: 800px; }");
            out.println("        .status { padding: 10px; margin: 10px 0; border-radius: 4px; }");
            out.println("        .connected { background-color: #d4edda; color: #155724; }");
            out.println("        .disconnected { background-color: #f8d7da; color: #721c24; }");
            out.println("        .messages { border: 1px solid #ddd; height: 300px; overflow-y: auto; ");
            out.println("                   padding: 10px; margin: 10px 0; background-color: #f9f9f9; }");
            out.println("        input[type=text] { width: 70%; padding: 8px; }");
            out.println("        button { padding: 8px 16px; margin-left: 5px; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <div class='container'>");
            out.println("        <h1>Gumdrop WebSocket Example</h1>");
            out.println("        <p>This page demonstrates WebSocket functionality using Gumdrop's ");
            out.println("           Servlet 4.0 HttpUpgradeHandler implementation.</p>");
            out.println("        ");
            out.println("        <div id='status' class='status disconnected'>Disconnected</div>");
            out.println("        ");
            out.println("        <div id='messages' class='messages'></div>");
            out.println("        ");
            out.println("        <div>");
            out.println("            <input type='text' id='messageInput' placeholder='Type a message...' ");
            out.println("                   onkeypress='if(event.key===\"Enter\") sendMessage()' />");
            out.println("            <button onclick='sendMessage()'>Send</button>");
            out.println("            <button onclick='connect()'>Connect</button>");
            out.println("            <button onclick='disconnect()'>Disconnect</button>");
            out.println("        </div>");
            out.println("        ");
            out.println("        <div style='margin-top: 20px;'>");
            out.println("            <h3>Instructions:</h3>");
            out.println("            <ol>");
            out.println("                <li>Click 'Connect' to establish WebSocket connection</li>");
            out.println("                <li>Type messages and press Enter or click 'Send'</li>");
            out.println("                <li>Messages will be echoed back by the server</li>");
            out.println("                <li>Click 'Disconnect' to close the connection</li>");
            out.println("            </ol>");
            out.println("        </div>");
            out.println("    </div>");
            out.println("    ");
            out.println("    <script>");
            out.println("        let websocket = null;");
            out.println("        ");
            out.println("        function connect() {");
            out.println("            if (websocket && websocket.readyState === WebSocket.OPEN) {");
            out.println("                addMessage('Already connected');");
            out.println("                return;");
            out.println("            }");
            out.println("            ");
            out.println("            const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';");
            out.println("            const wsUrl = protocol + '//' + location.host + location.pathname;");
            out.println("            ");
            out.println("            addMessage('Connecting to ' + wsUrl + '...');");
            out.println("            ");
            out.println("            websocket = new WebSocket(wsUrl);");
            out.println("            ");
            out.println("            websocket.onopen = function(event) {");
            out.println("                addMessage('✅ Connected to WebSocket server');");
            out.println("                updateStatus('Connected', true);");
            out.println("            };");
            out.println("            ");
            out.println("            websocket.onmessage = function(event) {");
            out.println("                addMessage('📨 Received: ' + event.data);");
            out.println("            };");
            out.println("            ");
            out.println("            websocket.onclose = function(event) {");
            out.println("                addMessage('❌ WebSocket connection closed (code: ' + event.code + ')');");
            out.println("                updateStatus('Disconnected', false);");
            out.println("            };");
            out.println("            ");
            out.println("            websocket.onerror = function(event) {");
            out.println("                addMessage('⚠️ WebSocket error occurred');");
            out.println("                updateStatus('Error', false);");
            out.println("            };");
            out.println("        }");
            out.println("        ");
            out.println("        function disconnect() {");
            out.println("            if (websocket) {");
            out.println("                websocket.close();");
            out.println("                websocket = null;");
            out.println("            }");
            out.println("        }");
            out.println("        ");
            out.println("        function sendMessage() {");
            out.println("            const input = document.getElementById('messageInput');");
            out.println("            const message = input.value.trim();");
            out.println("            ");
            out.println("            if (!message) return;");
            out.println("            ");
            out.println("            if (!websocket || websocket.readyState !== WebSocket.OPEN) {");
            out.println("                addMessage('⚠️ Not connected to WebSocket server');");
            out.println("                return;");
            out.println("            }");
            out.println("            ");
            out.println("            addMessage('📤 Sending: ' + message);");
            out.println("            websocket.send(message);");
            out.println("            input.value = '';");
            out.println("        }");
            out.println("        ");
            out.println("        function addMessage(message) {");
            out.println("            const messages = document.getElementById('messages');");
            out.println("            const timestamp = new Date().toLocaleTimeString();");
            out.println("            messages.innerHTML += '<div>[' + timestamp + '] ' + message + '</div>';");
            out.println("            messages.scrollTop = messages.scrollHeight;");
            out.println("        }");
            out.println("        ");
            out.println("        function updateStatus(status, connected) {");
            out.println("            const statusDiv = document.getElementById('status');");
            out.println("            statusDiv.textContent = status;");
            out.println("            statusDiv.className = 'status ' + (connected ? 'connected' : 'disconnected');");
            out.println("        }");
            out.println("        ");
            out.println("        // Auto-connect on page load");
            out.println("        window.addEventListener('load', function() {");
            out.println("            addMessage('Page loaded. Click Connect to start WebSocket communication.');");
            out.println("        });");
            out.println("    </script>");
            out.println("</body>");
            out.println("</html>");
        }
    }
}
