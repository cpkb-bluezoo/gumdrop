# HTTP Client Test Example

This directory contains a simple test program that demonstrates the Gumdrop HTTP client functionality.

## Overview

`SimpleHTTPClientTest.java` showcases the event-driven HTTP client API by making requests to `httpbin.org`, a free HTTP testing service. The test performs:

1. **GET Request**: Fetches data from `/get` endpoint with custom headers
2. **POST Request**: Sends JSON data to `/post` endpoint

## Features Demonstrated

### ✅ Event-Driven Architecture
- **Connection Events**: `onConnected()`, `onDisconnected()`, `onError()`
- **Protocol Negotiation**: `onProtocolNegotiated()` with HTTP/1.1 fallback
- **Stream Lifecycle**: `onStreamCreated()`, `onStreamComplete()`
- **Response Processing**: `onStreamResponse()`, `onStreamData()`

### ✅ HTTP/1.1 Protocol Support
- **Request Formatting**: Method, URI, headers, body
- **Response Parsing**: Status line, headers, body (Content-Length, chunked, connection-close)
- **Connection Management**: Keep-alive, sequential request processing
- **Type Safety**: HTTPVersion enum, HTTPRequest/HTTPResponse classes

### ✅ Stream-Based API
- **Request Sending**: `sendRequest()`, `sendData()`, `completeRequest()`
- **State Management**: IDLE → OPEN → HALF_CLOSED_LOCAL → CLOSED
- **Error Handling**: Stream-specific error reporting

## How to Run

1. **Build Gumdrop**:
   ```bash
   cd /path/to/gumdrop
   ant
   ```

2. **Compile the Test**:
   ```bash
   cd examples/http-client-test
   javac -cp ../../dist/server.jar SimpleHTTPClientTest.java
   ```

3. **Run the Test**:
   ```bash
   java -cp ../../dist/server.jar:. SimpleHTTPClientTest
   ```

## Expected Output

```
Starting HTTP Client Test

=== Testing Simple GET Request ===
✓ Connected to httpbin.org
✓ Protocol negotiated: HTTP/1.1
✓ Stream created: 1
✓ Request sent: GET /get
✓ Response received: 200 OK
  Content-Type: application/json
  Content-Length: 314
✓ Received 314 bytes (final)
✓ Stream 1 completed
  Response body length: 314 characters
  Response preview: {
  "args": {},
  "headers": {
    "Accept": "application/json",
    "Host": "httpbin.org",
    "User-Agent": "Gumdrop-HTTP-Client/1.0"
  },
  "origin": "...",
  "url": "http://httpbin.org/get"
}
✓ GET test completed successfully

=== Testing POST Request ===
✓ Connected to httpbin.org
✓ Protocol negotiated: HTTP/1.1
✓ Stream created: 1
✓ Request sent: POST /post (53 bytes)
✓ Response received: 200 OK
  ✓ Success status code
✓ Received 428 bytes (final)
✓ Stream 1 completed
✓ POST test completed successfully

All tests completed successfully!
```

## Key Architectural Benefits

### 🚀 **Non-Blocking I/O**
- Uses NIO2 `SocketChannel` with `Selector`
- No thread-per-connection overhead
- Efficient handling of multiple concurrent requests (HTTP/2 ready)

### 🎯 **Event-Driven Design**
- No blocking method calls
- Handler-based response processing
- Clean separation of network and application logic

### 🔧 **Protocol Abstraction**
- Same API works for HTTP/1.1 and HTTP/2 (when implemented)
- Stream-based request/response model
- Type-safe protocol version handling

### 📦 **Modular Architecture**
- `HTTPClient` extends `Client` (connection factory)
- `HTTPClientConnection` extends `Connection` (protocol handler)
- `HTTPClientStream` represents individual request/response pairs
- `HTTPClientHandler` drives application logic

## Integration with Gumdrop Server

This HTTP client uses the same architectural patterns as the Gumdrop server:

- **SelectorLoop**: Same event loop handles both client and server connections
- **Connection**: Same base class for both client and server connections  
- **SSL/TLS**: Same in-tree TLS engine (`org.bluezoo.gumdrop.tls`) for both directions
- **Executor**: Same thread pool for both client and server processing

This unified architecture allows applications to act as both HTTP clients and servers using consistent APIs and shared infrastructure.
