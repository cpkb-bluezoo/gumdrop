# WebSocket Servlet Example

This example demonstrates how to implement WebSocket functionality using Gumdrop's Servlet 4.0 HttpUpgradeHandler support.

## Overview

The example consists of:

- **`EchoWebSocketHandler`** - A WebSocket handler that echoes received messages back to clients
- **`WebSocketExampleServlet`** - A servlet that serves a test page and handles WebSocket upgrades  
- **HTML/JavaScript client** - A web interface for testing WebSocket communication

## Features Demonstrated

### Server-Side (Java)
- ✅ **Servlet 4.0 HttpUpgradeHandler API** integration
- ✅ **WebSocket handshake** validation and upgrade process
- ✅ **Protocol switching** from HTTP to WebSocket frames
- ✅ **ServletInputStream/OutputStream** for WebSocket data
- ✅ **Connection lifecycle** management (init/destroy)
- ✅ **Error handling** and resource cleanup

### Client-Side (JavaScript)  
- ✅ **WebSocket API** usage with proper connection management
- ✅ **Message sending/receiving** with echo demonstration
- ✅ **Connection status** tracking and UI updates
- ✅ **Error handling** for connection failures
- ✅ **Automatic reconnection** capabilities

## Usage

### 1. Build and Deploy

```bash
# Build Gumdrop with WebSocket support
cd /path/to/gumdrop
ant build

# Copy example classes to your servlet deployment directory
# (The exact process depends on your Gumdrop servlet container setup)
```

### 2. Configure Servlet

Add to your `web.xml` or use annotations:

```xml
<servlet>
    <servlet-name>WebSocketExample</servlet-name>
    <servlet-class>examples.websocket.WebSocketExampleServlet</servlet-class>
</servlet>

<servlet-mapping>
    <servlet-name>WebSocketExample</servlet-name>
    <url-pattern>/websocket-example</url-pattern>
</servlet-mapping>
```

### 3. Test the WebSocket Connection

1. **Start your Gumdrop server** with servlet support
2. **Navigate to** `http://localhost:8080/websocket-example` (adjust URL as needed)
3. **Click "Connect"** to establish WebSocket connection  
4. **Type messages** and press Enter to see them echoed back
5. **Monitor the console** for server-side logging

## Code Structure

### EchoWebSocketHandler

```java
public class EchoWebSocketHandler implements HttpUpgradeHandler {
    @Override
    public void init(WebConnection webConnection) {
        // Called when HTTP is upgraded to WebSocket
        // Start background thread for WebSocket I/O
    }
    
    @Override 
    public void destroy() {
        // Called when WebSocket connection closes
        // Cleanup resources
    }
}
```

### WebSocket Upgrade Process

```java
// In servlet doGet() method:
if (isWebSocketUpgrade(request)) {
    EchoWebSocketHandler handler = request.upgrade(EchoWebSocketHandler.class);
    // Connection is now upgraded to WebSocket protocol
}
```

### WebSocket I/O

```java
// Reading WebSocket data
InputStream input = webConnection.getInputStream();
byte[] buffer = new byte[4096];
int bytesRead = input.read(buffer);

// Writing WebSocket data  
OutputStream output = webConnection.getOutputStream();
output.write(response.getBytes("UTF-8"));
output.flush();
```

## Architecture

```
Browser                 Gumdrop Server
   |                         |
   |-- HTTP GET ------------>| WebSocketExampleServlet
   |<-- HTML page -----------|   (serves test interface)
   |                         |
   |-- WebSocket Upgrade --->| Request.upgrade()
   |<-- 101 Switching -------|   (validates & upgrades)
   |                         |
   |<-- WebSocket Frames --->| EchoWebSocketHandler  
   |    (bidirectional)      |   (handles messages)
```

## Technical Details

### Servlet 4.0 Integration
- Uses standard `HttpUpgradeHandler` interface
- Leverages `WebConnection` for low-level I/O
- Maintains compatibility with existing servlet containers

### WebSocket Protocol Compliance  
- RFC 6455 compliant handshake validation
- Proper frame parsing and generation
- Standard close codes and error handling
- Supports text and binary message types

### Performance Characteristics
- Non-blocking I/O using background threads
- Efficient frame processing with minimal allocations
- Proper connection cleanup and resource management
- Scalable for multiple concurrent WebSocket connections

## Extending the Example

### Custom Message Handling

```java
// Extend EchoWebSocketHandler for custom behaviour
public class ChatWebSocketHandler extends EchoWebSocketHandler {
    @Override
    protected void handleMessage(String message) {
        // Custom message processing
        broadcastToAllClients(message);
    }
}
```

### Protocol Extensions

```java  
// Add subprotocol support
String protocol = request.getHeader("Sec-WebSocket-Protocol");
if ("chat".equals(protocol)) {
    // Handle chat protocol
}
```

### Authentication Integration

```java
// Validate user before upgrade
HttpSession session = request.getSession();
User user = (User) session.getAttribute("user");
if (user == null) {
    response.sendError(401, "Authentication required");
    return;
}
```

## Troubleshooting

### Common Issues

1. **Connection Refused**: Check that Gumdrop server is running with servlet support
2. **Upgrade Failed**: Verify WebSocket headers are present and valid
3. **No Echo Response**: Check server logs for WebSocket handler errors
4. **Browser Compatibility**: Ensure browser supports WebSocket API

### Debug Logging

Enable verbose logging to troubleshoot issues:

```java
Logger.getLogger("examples.websocket").setLevel(Level.FINE);
Logger.getLogger("org.bluezoo.gumdrop.http.websocket").setLevel(Level.FINE);
```

This example provides a complete foundation for building WebSocket applications with Gumdrop's servlet container.
