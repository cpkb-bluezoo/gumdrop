# HTTP Trailer Fields Example

This example demonstrates the **Servlet 4.0 HTTP trailer fields feature** implemented in Gumdrop server.

## What are HTTP Trailer Fields?

HTTP trailer fields are headers sent **after** the response body. They're useful for metadata that can only be determined after processing the entire response, such as:

- Content integrity checks (checksums, digital signatures)
- Processing statistics (timing information, resource usage)  
- Dynamic metadata computed during response generation
- Final status information

## Browser Support

- **HTTP/2**: Native trailer field support
- **HTTP/1.1**: Requires chunked transfer encoding
- **Developer Tools**: Use browser Network tab to view trailer fields

## Running the Example

1. Deploy the `TrailerFieldsExampleServlet` to Gumdrop server
2. Access the servlet URL in a browser
3. Use browser developer tools (Network tab) to view trailer fields
4. Try different demo modes:
   - `?demo=basic` - Simple static trailer fields
   - `?demo=checksum` - Content integrity checksums
   - `?demo=timing` - Response processing timing
   - `?demo=dynamic` - Live server metrics

## Implementation Details

### Basic Usage
```java
response.setTrailerFields(() -> {
    Map<String,String> trailers = new HashMap<>();
    trailers.put("X-Content-Source", "Gumdrop-Server");
    trailers.put("X-Processing-Complete", "true");
    return trailers;
});
```

### Dynamic Trailers
```java
response.setTrailerFields(() -> {
    Map<String,String> trailers = new HashMap<>();
    trailers.put("X-Processing-Time", String.valueOf(System.currentTimeMillis() - startTime));
    trailers.put("X-Memory-Usage", String.valueOf(Runtime.getRuntime().freeMemory()));
    return trailers;
});
```

### Content Integrity
```java
final StringBuilder contentBuffer = new StringBuilder();
response.setTrailerFields(() -> {
    String content = contentBuffer.toString();
    int checksum = content.hashCode();
    
    Map<String,String> trailers = new HashMap<>();
    trailers.put("X-Content-Checksum", String.valueOf(Math.abs(checksum)));
    trailers.put("X-Content-Length", String.valueOf(content.length()));
    return trailers;
});
```

## Technical Notes

- Trailer fields are sent after the response body completes
- The supplier function is called when the response is being finalized
- Exceptions in the supplier are caught and logged but don't break the response
- Trailer fields work with both HTTP/2 and HTTP/1.1 chunked encoding
- Empty or null trailer maps are handled gracefully

## Servlet 4.0 Compliance

This implementation provides full **Servlet 4.0 trailer fields support**:

- ✅ `HttpServletResponse.setTrailerFields(Supplier<Map<String,String>>)`
- ✅ `HttpServletResponse.getTrailerFields()`  
- ✅ Integration with HTTP/2 HEADERS frames
- ✅ Integration with HTTP/1.1 chunked encoding
- ✅ Proper error handling and logging
- ✅ Dynamic trailer field generation
