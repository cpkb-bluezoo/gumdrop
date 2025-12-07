/*
 * TrailerFieldsExampleServlet.java
 * HTTP Trailer Fields Demonstration for Gumdrop Server
 * 
 * This example demonstrates HTTP trailer fields support in Servlet 4.0
 * using Response.setTrailerFields() functionality.
 */

package examples.trailerfields;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Example servlet demonstrating HTTP trailer fields functionality.
 * 
 * <p>This servlet shows how to use the Servlet 4.0 trailer fields feature
 * to send metadata after the response body. Trailer fields are useful for:
 * <ul>
 * <li>Content integrity checks (checksums, signatures)</li>
 * <li>Processing statistics (timing, resource usage)</li>
 * <li>Dynamic metadata computed during response generation</li>
 * </ul>
 * 
 * <p>Trailer fields are supported in:
 * <ul>
 * <li>HTTP/2 (via HEADERS frame with END_STREAM)</li>
 * <li>HTTP/1.1 with chunked transfer encoding</li>
 * </ul>
 */
public class TrailerFieldsExampleServlet extends HttpServlet {
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String demo = request.getParameter("demo");
        if (demo == null) {
            demo = "basic";
        }
        
        switch (demo) {
            case "basic":
                demonstrateBasicTrailerFields(request, response);
                break;
            case "checksum":
                demonstrateChecksumTrailer(request, response);
                break;
            case "timing":
                demonstrateTimingTrailer(request, response);
                break;
            case "dynamic":
                demonstrateDynamicTrailer(request, response);
                break;
            default:
                serveIndexPage(request, response);
                break;
        }
    }
    
    /**
     * Basic trailer fields demonstration.
     */
    private void demonstrateBasicTrailerFields(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        // Set up trailer fields that will be sent after the response body
        response.setTrailerFields(() -> {
            Map<String, String> trailers = new HashMap<>();
            trailers.put("X-Content-Source", "Gumdrop-Server");
            trailers.put("X-Processing-Complete", "true");
            trailers.put("X-Response-ID", "basic-demo-" + System.currentTimeMillis());
            return trailers;
        });
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>Basic Trailer Fields Demo</title></head>");
            out.println("<body>");
            out.println("<h1>🎯 Basic HTTP Trailer Fields</h1>");
            out.println("<p>This response includes trailer fields sent after the body content.</p>");
            out.println("<h2>What are Trailer Fields?</h2>");
            out.println("<p>HTTP trailer fields are headers sent <em>after</em> the response body.");
            out.println("They're useful for metadata that can only be determined after processing the entire response.</p>");
            out.println("<h2>Current Response Trailers</h2>");
            out.println("<ul>");
            out.println("<li><strong>X-Content-Source:</strong> Gumdrop-Server</li>");
            out.println("<li><strong>X-Processing-Complete:</strong> true</li>");
            out.println("<li><strong>X-Response-ID:</strong> basic-demo-[timestamp]</li>");
            out.println("</ul>");
            out.println("<p><em>Note: Use browser developer tools (Network tab) to see the actual trailer fields.</em></p>");
            out.println("<p><a href='?demo=checksum'>Next: Checksum Demo →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Checksum trailer fields demonstration.
     */
    private void demonstrateChecksumTrailer(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/plain; charset=UTF-8");
        
        // We'll calculate a simple checksum of the response content
        final StringBuilder contentBuffer = new StringBuilder();
        
        response.setTrailerFields(() -> {
            String content = contentBuffer.toString();
            int checksum = content.hashCode();
            int contentLength = content.getBytes().length;
            
            Map<String, String> trailers = new HashMap<>();
            trailers.put("X-Content-Checksum", String.valueOf(Math.abs(checksum)));
            trailers.put("X-Content-Length-Computed", String.valueOf(contentLength));
            trailers.put("X-Checksum-Algorithm", "Java-HashCode");
            return trailers;
        });
        
        try (PrintWriter out = response.getWriter()) {
            String content = "HTTP Trailer Fields - Checksum Demo\n" +
                           "====================================\n\n" +
                           "This response demonstrates using trailer fields for content integrity.\n" +
                           "The server calculates a checksum of the entire response body and\n" +
                           "includes it as a trailer field.\n\n" +
                           "Response Content:\n" +
                           "- Line 1: This is the first line of content\n" +
                           "- Line 2: This is the second line of content\n" +
                           "- Line 3: This is the third line of content\n\n" +
                           "The checksum and content length will be sent as trailer fields\n" +
                           "after this content completes.\n\n" +
                           "Trailer fields sent:\n" +
                           "- X-Content-Checksum: [calculated checksum]\n" +
                           "- X-Content-Length-Computed: [calculated length]\n" +
                           "- X-Checksum-Algorithm: Java-HashCode\n\n" +
                           "Use browser developer tools to verify the trailer fields!\n";
            
            out.print(content);
            contentBuffer.append(content); // Store for checksum calculation
        }
    }
    
    /**
     * Timing trailer fields demonstration.
     */
    private void demonstrateTimingTrailer(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        final long startTime = System.currentTimeMillis();
        response.setContentType("application/json; charset=UTF-8");
        
        response.setTrailerFields(() -> {
            long endTime = System.currentTimeMillis();
            long processingTime = endTime - startTime;
            
            Map<String, String> trailers = new HashMap<>();
            trailers.put("X-Processing-Time-Ms", String.valueOf(processingTime));
            trailers.put("X-Start-Time", String.valueOf(startTime));
            trailers.put("X-End-Time", String.valueOf(endTime));
            trailers.put("X-Server-Performance", processingTime < 100 ? "fast" : "normal");
            return trailers;
        });
        
        try (PrintWriter out = response.getWriter()) {
            out.println("{");
            out.println("  \"demo\": \"timing-trailer-fields\",");
            out.println("  \"description\": \"This response includes timing information in trailer fields\",");
            out.println("  \"message\": \"Processing this response...\",");
            
            // Simulate some processing work
            try {
                Thread.sleep(50 + (int)(Math.random() * 100)); // Random 50-150ms delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            out.println("  \"processing_complete\": true,");
            out.println("  \"trailer_fields\": [");
            out.println("    \"X-Processing-Time-Ms\",");
            out.println("    \"X-Start-Time\",");  
            out.println("    \"X-End-Time\",");
            out.println("    \"X-Server-Performance\"");
            out.println("  ],");
            out.println("  \"note\": \"Check network developer tools for trailer fields with actual timing data\"");
            out.println("}");
        }
    }
    
    /**
     * Dynamic trailer fields demonstration.
     */
    private void demonstrateDynamicTrailer(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        // Create a dynamic trailer supplier that captures runtime information
        response.setTrailerFields(new Supplier<Map<String,String>>() {
            private int callCount = 0;
            
            @Override
            public Map<String,String> get() {
                callCount++;
                
                Map<String, String> trailers = new HashMap<>();
                trailers.put("X-Supplier-Call-Count", String.valueOf(callCount));
                trailers.put("X-Memory-Free", String.valueOf(Runtime.getRuntime().freeMemory()));
                trailers.put("X-Memory-Total", String.valueOf(Runtime.getRuntime().totalMemory()));
                trailers.put("X-Memory-Max", String.valueOf(Runtime.getRuntime().maxMemory()));
                trailers.put("X-Active-Threads", String.valueOf(Thread.activeCount()));
                trailers.put("X-Current-Thread", Thread.currentThread().getName());
                trailers.put("X-Generation-Time", String.valueOf(System.nanoTime()));
                return trailers;
            }
        });
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head><title>Dynamic Trailer Fields</title></head>");
            out.println("<body>");
            out.println("<h1>⚡ Dynamic Trailer Fields</h1>");
            out.println("<p>This demonstration shows trailer fields with dynamic runtime information.</p>");
            out.println("<h2>Dynamic Trailer Information</h2>");
            out.println("<p>The trailer fields for this response contain live server metrics:");
            out.println("<ul>");
            out.println("<li>Memory usage statistics</li>");
            out.println("<li>Thread count information</li>");
            out.println("<li>Supplier call tracking</li>");
            out.println("<li>High-precision timestamps</li>");
            out.println("</ul>");
            
            // Generate some content to affect memory usage
            out.println("<h2>Generated Content</h2>");
            for (int i = 1; i <= 10; i++) {
                out.printf("<p>Generated paragraph #%d with timestamp: %d</p>%n", i, System.nanoTime());
            }
            
            out.println("<h2>Trailer Fields Implementation</h2>");
            out.println("<pre>");
            out.println("response.setTrailerFields(() -> {");
            out.println("    Map&lt;String,String&gt; trailers = new HashMap&lt;&gt;();");
            out.println("    trailers.put(\"X-Memory-Free\", String.valueOf(Runtime.getRuntime().freeMemory()));");
            out.println("    trailers.put(\"X-Active-Threads\", String.valueOf(Thread.activeCount()));");
            out.println("    // ... more dynamic data");
            out.println("    return trailers;");
            out.println("});");
            out.println("</pre>");
            
            out.println("<p><em>Check browser developer tools to see the actual dynamic values!</em></p>");
            out.println("<p><a href='?'>← Back to Index</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Main index page with navigation.
     */
    private void serveIndexPage(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        // Even the index page has trailer fields!
        response.setTrailerFields(() -> {
            Map<String, String> trailers = new HashMap<>();
            trailers.put("X-Demo-Index", "true");
            trailers.put("X-Available-Demos", "basic,checksum,timing,dynamic");
            trailers.put("X-Servlet-Version", "4.0");
            return trailers;
        });
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>HTTP Trailer Fields Demo</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }");
            out.println("        .demo-link { display: block; margin: 15px 0; padding: 15px;");
            out.println("                    background: #3498db; color: white; text-decoration: none; border-radius: 5px; }");
            out.println("        .demo-link:hover { background: #2980b9; }");
            out.println("        .code { background: #f4f4f4; padding: 10px; border-radius: 3px; margin: 10px 0; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🎯 HTTP Trailer Fields Demonstration</h1>");
            out.println("    <p>This demonstration shows Servlet 4.0 HTTP trailer fields support in Gumdrop server.</p>");
            out.println("    ");
            out.println("    <h2>What are HTTP Trailer Fields?</h2>");
            out.println("    <p>HTTP trailer fields are headers sent <strong>after</strong> the response body. ");
            out.println("    They're useful for metadata that can only be determined after processing the entire response.</p>");
            out.println("    ");
            out.println("    <h2>Available Demonstrations</h2>");
            out.println("    ");
            out.println("    <a href='?demo=basic' class='demo-link'>");
            out.println("        🎯 Basic Trailer Fields");
            out.println("        <br><small>Simple trailer fields with static metadata</small>");
            out.println("    </a>");
            out.println("    ");
            out.println("    <a href='?demo=checksum' class='demo-link'>");
            out.println("        🔐 Content Checksum");
            out.println("        <br><small>Calculate and send content integrity checksums</small>");
            out.println("    </a>");
            out.println("    ");
            out.println("    <a href='?demo=timing' class='demo-link'>");
            out.println("        ⏱️ Processing Timing");
            out.println("        <br><small>Include response processing time in trailers</small>");
            out.println("    </a>");
            out.println("    ");
            out.println("    <a href='?demo=dynamic' class='demo-link'>");
            out.println("        ⚡ Dynamic Runtime Info");
            out.println("        <br><small>Live server metrics and runtime information</small>");
            out.println("    </a>");
            out.println("    ");
            out.println("    <h2>How to Use</h2>");
            out.println("    <div class='code'>");
            out.println("    <strong>Servlet 4.0 Code:</strong><br>");
            out.println("    response.setTrailerFields(() -> {<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;Map&lt;String,String&gt; trailers = new HashMap&lt;&gt;();<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;trailers.put(\"X-Custom-Header\", \"value\");<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;return trailers;<br>");
            out.println("    });");
            out.println("    </div>");
            out.println("    ");
            out.println("    <h2>Browser Support</h2>");
            out.println("    <ul>");
            out.println("        <li><strong>HTTP/2:</strong> Native trailer field support</li>");
            out.println("        <li><strong>HTTP/1.1:</strong> Requires chunked transfer encoding</li>");
            out.println("        <li><strong>Developer Tools:</strong> Check Network tab to see trailer fields</li>");
            out.println("    </ul>");
            out.println("    ");
            out.println("    <p><em>Note: This page itself includes trailer fields! Check the Network tab.</em></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
}
