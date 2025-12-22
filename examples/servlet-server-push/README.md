# HTTP/2 Server Push Example

This example demonstrates the **Servlet 4.0 HTTP/2 server push feature** implemented in Gumdrop server.

## What is HTTP/2 Server Push?

HTTP/2 Server Push allows the server to proactively send resources to the client that it knows the client will need, eliminating the round-trip latency of traditional HTTP request-response cycles.

### Key Benefits

- **Reduced Latency**: Resources delivered before client requests them
- **Improved Performance**: Critical resources available immediately  
- **Better User Experience**: Faster page load times
- **Network Efficiency**: Fewer round trips required

### How It Works

1. **Server Decision**: Server decides which resources to push based on the main request
2. **PUSH_PROMISE**: Server sends PUSH_PROMISE frame announcing upcoming push
3. **Resource Delivery**: Server immediately sends the promised resource
4. **Client Cache**: Browser caches pushed resources before they're requested
5. **Request Fulfillment**: When browser needs resource, it's already available

## Browser Support

- **Requirements**: HTTP/2 connection + Server Push support
- **Supported**: Chrome, Firefox, Safari, Edge (modern versions)
- **Fallback**: Regular HTTP requests if push not supported

## Running the Example

1. Deploy the `ServerPushExampleServlet` to Gumdrop server with HTTP/2 enabled
2. Access via HTTPS (HTTP/2 required for server push)
3. Use browser developer tools (Network tab) to observe pushed resources
4. Try different demo modes:
   - `?demo=basic` - Simple server push example
   - `?demo=conditional` - Conditional pushing based on cache
   - `?demo=multiple` - Multiple resource pushing

## Implementation Details

### Basic Usage
```java
PushBuilder pushBuilder = request.newPushBuilder();
if (pushBuilder != null) {
    pushBuilder.path("/css/styles.css")
              .addHeader("Cache-Control", "max-age=3600")
              .push();
}
```

### Conditional Pushing
```java
String ifModifiedSince = request.getHeader("If-Modified-Since");
boolean hasCache = (ifModifiedSince != null);

if (!hasCache) {
    // Only push if client doesn't have cached version
    pushBuilder.path("/css/styles.css").push();
}
```

### Multiple Resource Push
```java
PushBuilder pushBuilder = request.newPushBuilder();

// Push CSS
pushBuilder.path("/css/styles.css")
          .addHeader("Cache-Control", "max-age=3600")
          .push();

// Reuse for JavaScript (path resets automatically)
pushBuilder.path("/js/app.js")
          .push();  // Headers from previous push are inherited

// Modify headers for different resource type
pushBuilder.path("/images/hero.jpg")
          .removeHeader("Cache-Control")
          .addHeader("Cache-Control", "max-age=86400")
          .push();
```

## Best Practices

### What to Push
- **CSS stylesheets** - Needed for initial render
- **Critical JavaScript** - Required for page functionality
- **Above-the-fold images** - Visible immediately
- **Web fonts** - Needed for text rendering

### What NOT to Push
- **Large files** - Can waste bandwidth if not used
- **User-specific content** - May not be cacheable
- **Conditional resources** - Only needed in certain scenarios
- **Third-party resources** - Can't push cross-origin

### Implementation Guidelines
```java
// ✅ Good: Push critical, cacheable resources
pushBuilder.path("/css/critical.css")
          .addHeader("Cache-Control", "max-age=3600")
          .push();

// ✅ Good: Conditional pushing
if (!hasCachedVersion) {
    pushBuilder.path("/js/app.js").push();
}

// ❌ Avoid: Pushing large, optional resources
// pushBuilder.path("/video/background.mp4").push();

// ❌ Avoid: Pushing without cache headers
// pushBuilder.path("/api/data").push();
```

## Performance Considerations

### Timing
- Push resources **immediately** when handling the main request
- Push occurs **before** HTML parsing completes
- Pushed resources are **cached** before being requested

### Resource Selection
- Focus on **critical path** resources
- Consider **file size** vs **latency savings**
- Respect **client preferences** (cache headers)
- Monitor **push effectiveness** with analytics

### Error Handling
- Server push failures **do not break** the main response
- Clients can **reject pushes** they don't need
- Always provide **fallback** via regular requests

## Technical Notes

- Server push requires **HTTP/2** - returns null on HTTP/1.x
- PushBuilder **resets path** after each push() call
- Headers are **inherited** between pushes unless modified
- Push promises are sent **immediately**, resources delivered **asynchronously**
- Clients can send **RST_STREAM** to cancel unwanted pushes

## Servlet 4.0 Compliance

This implementation provides full **Servlet 4.0 server push support**:

- ✅ `HttpServletRequest.newPushBuilder()`
- ✅ `HttpServletResponse.getPushBuilder()` (delegates to request)
- ✅ Complete `PushBuilder` implementation
- ✅ HTTP/2 PUSH_PROMISE frame generation
- ✅ Proper stream management and lifecycle
- ✅ Error handling and fallback behaviour

## Architecture

The implementation maintains clean separation of concerns:

- **ServletPushBuilder**: High-level Servlet API implementation
- **ServletStream**: Servlet-level coordination and validation
- **Stream**: HTTP protocol-level server push execution
- **HTTPConnection**: Low-level HTTP/2 frame and stream management

This architecture ensures the servlet layer remains protocol-agnostic while enabling full HTTP/2 server push functionality.
