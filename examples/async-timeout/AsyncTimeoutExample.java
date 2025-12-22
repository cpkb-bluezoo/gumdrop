/*
 * AsyncTimeoutExample.java
 * Servlet Async Context Timeout Example for Gumdrop Server
 * 
 * This example demonstrates how to use async servlet timeouts
 * with StreamAsyncContext timeout functionality.
 */

package examples.asynctimeout;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Example servlet demonstrating async context timeout handling.
 * 
 * <p>This servlet shows:
 * <ul>
 * <li>How to configure async context timeouts</li>
 * <li>How to handle timeout events with AsyncListener</li>
 * <li>Different timeout scenarios (handled vs. unhandled)</li>
 * <li>Proper async context lifecycle management</li>
 * </ul>
 */
public class AsyncTimeoutExample extends HttpServlet {
    
    private static final Logger LOGGER = Logger.getLogger(AsyncTimeoutExample.class.getName());
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String action = request.getParameter("action");
        if (action == null) {
            action = "demo";
        }
        
        switch (action) {
            case "timeout-handled":
                handleTimeoutWithListener(request, response);
                break;
            case "timeout-unhandled":
                handleTimeoutWithoutListener(request, response);
                break;
            case "timeout-custom":
                handleCustomTimeout(request, response);
                break;
            case "no-timeout":
                handleNoTimeout(request, response);
                break;
            default:
                serveDemoPage(request, response);
                break;
        }
    }
    
    /**
     * Demonstrates timeout handling with AsyncListener.
     * The listener will handle the timeout and complete the request gracefully.
     */
    private void handleTimeoutWithListener(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        LOGGER.info("Starting async operation with timeout listener");
        
        // Start async processing
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(5000); // 5 second timeout
        
        // Add listener to handle timeout
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                LOGGER.info("Async operation completed normally");
            }
            
            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                LOGGER.info("Async operation timed out - handling gracefully");
                
                HttpServletResponse resp = (HttpServletResponse) event.getAsyncContext().getResponse();
                resp.setContentType("text/html");
                
                try (PrintWriter out = resp.getWriter()) {
                    out.println("<html><body>");
                    out.println("<h2>⏰ Async Operation Timed Out</h2>");
                    out.println("<p>The async operation took too long and was timed out after 5 seconds.</p>");
                    out.println("<p>This timeout was handled gracefully by an AsyncListener.</p>");
                    out.println("<p><a href='?'>Back to Demo</a></p>");
                    out.println("</body></html>");
                }
                
                // Complete the async context to finish the response
                event.getAsyncContext().complete();
            }
            
            @Override
            public void onError(AsyncEvent event) throws IOException {
                LOGGER.severe("Async operation error: " + event.getThrowable());
                event.getAsyncContext().complete();
            }
            
            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
                LOGGER.info("Async operation started");
            }
        });
        
        // Simulate a long-running operation that will timeout
        // (In real code, you'd typically dispatch work to another thread)
        LOGGER.info("Long operation started - will timeout in 5 seconds");
    }
    
    /**
     * Demonstrates timeout without a listener.
     * The container will complete the request with an error response.
     */
    private void handleTimeoutWithoutListener(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        LOGGER.info("Starting async operation without timeout listener");
        
        // Start async processing with short timeout and no listener
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(3000); // 3 second timeout
        
        // No listener added - container will handle timeout automatically
        LOGGER.info("Long operation started - will timeout in 3 seconds (no listener)");
    }
    
    /**
     * Demonstrates custom timeout configuration.
     */
    private void handleCustomTimeout(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        String timeoutParam = request.getParameter("timeout");
        long timeout = 2000; // Default 2 seconds
        
        try {
            if (timeoutParam != null) {
                timeout = Long.parseLong(timeoutParam);
            }
        } catch (NumberFormatException e) {
            // Use default
        }
        
        LOGGER.info("Starting async operation with custom timeout: " + timeout + "ms");
        
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(timeout);
        
        // Add listener for custom timeout handling
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                HttpServletResponse resp = (HttpServletResponse) event.getAsyncContext().getResponse();
                resp.setContentType("text/html");
                
                try (PrintWriter out = resp.getWriter()) {
                    out.println("<html><body>");
                    out.println("<h2>🕐 Custom Timeout: " + timeout + "ms</h2>");
                    out.println("<p>Your custom timeout of " + timeout + "ms has been reached.</p>");
                    out.println("<p><a href='?'>Back to Demo</a></p>");
                    out.println("</body></html>");
                }
                
                event.getAsyncContext().complete();
            }
            
            @Override
            public void onComplete(AsyncEvent event) throws IOException {}
            @Override
            public void onError(AsyncEvent event) throws IOException {}
            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {}
        });
    }
    
    /**
     * Demonstrates async operation that completes before timeout.
     */
    private void handleNoTimeout(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        LOGGER.info("Starting async operation that completes before timeout");
        
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(10000); // 10 second timeout - won't be reached
        
        // Simulate quick operation and complete immediately
        response.setContentType("text/html");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<html><body>");
            out.println("<h2>✅ Async Operation Completed</h2>");
            out.println("<p>This async operation completed successfully before the 10-second timeout.</p>");
            out.println("<p>Timeout was cancelled when complete() was called.</p>");
            out.println("<p><a href='?'>Back to Demo</a></p>");
            out.println("</body></html>");
        }
        
        asyncContext.complete();
        LOGGER.info("Async operation completed successfully");
    }
    
    /**
     * Serves the main demo page with links to test different scenarios.
     */
    private void serveDemoPage(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>Async Context Timeout Demo</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; }");
            out.println("        .scenario { margin: 20px 0; padding: 15px; border: 1px solid #ddd; border-radius: 5px; }");
            out.println("        .timeout { background-color: #fff3cd; }");
            out.println("        .success { background-color: #d4edda; }");
            out.println("        .custom { background-color: #cce5ff; }");
            out.println("        a { display: inline-block; margin: 5px 10px 5px 0; padding: 8px 16px; ");
            out.println("            background-color: #007bff; color: white; text-decoration: none; border-radius: 4px; }");
            out.println("        a:hover { background-color: #0056b3; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🔄 Async Context Timeout Demo</h1>");
            out.println("    <p>This demo shows different async servlet timeout scenarios using Gumdrop's StreamAsyncContext.</p>");
            out.println();
            
            out.println("    <div class='scenario timeout'>");
            out.println("        <h3>⏰ Timeout with Listener (5 seconds)</h3>");
            out.println("        <p>Async operation that times out after 5 seconds. An AsyncListener handles the timeout gracefully.</p>");
            out.println("        <a href='?action=timeout-handled'>Test Timeout with Listener</a>");
            out.println("    </div>");
            
            out.println("    <div class='scenario timeout'>");
            out.println("        <h3>💥 Timeout without Listener (3 seconds)</h3>");
            out.println("        <p>Async operation that times out after 3 seconds. No listener is registered, so the container sends a 500 error.</p>");
            out.println("        <a href='?action=timeout-unhandled'>Test Unhandled Timeout</a>");
            out.println("    </div>");
            
            out.println("    <div class='scenario custom'>");
            out.println("        <h3>🕐 Custom Timeout</h3>");
            out.println("        <p>Configure your own timeout value and see the behaviour.</p>");
            out.println("        <a href='?action=timeout-custom&timeout=1000'>1 Second Timeout</a>");
            out.println("        <a href='?action=timeout-custom&timeout=2500'>2.5 Second Timeout</a>");
            out.println("        <a href='?action=timeout-custom&timeout=7000'>7 Second Timeout</a>");
            out.println("    </div>");
            
            out.println("    <div class='scenario success'>");
            out.println("        <h3>✅ No Timeout (Completes Immediately)</h3>");
            out.println("        <p>Async operation that completes before the timeout is reached. Timeout is cancelled automatically.</p>");
            out.println("        <a href='?action=no-timeout'>Test Successful Completion</a>");
            out.println("    </div>");
            
            out.println("    <h2>📋 Implementation Details</h2>");
            out.println("    <ul>");
            out.println("        <li><strong>Default timeout</strong>: 30 seconds (configurable)</li>");
            out.println("        <li><strong>Timeout executor</strong>: Dedicated 2-thread ScheduledExecutorService</li>");
            out.println("        <li><strong>Thread safety</strong>: All operations are synchronized</li>");
            out.println("        <li><strong>Listener support</strong>: Full AsyncListener.onTimeout() event handling</li>");
            out.println("        <li><strong>Automatic cleanup</strong>: Timeout tasks are cancelled on completion</li>");
            out.println("        <li><strong>Error handling</strong>: 500 error sent if no listener handles timeout</li>");
            out.println("    </ul>");
            
            out.println("    <h2>🔧 Technical Features</h2>");
            out.println("    <ul>");
            out.println("        <li>Uses <code>ScheduledExecutorService</code> for precise timeout control</li>");
            out.println("        <li>Daemon threads prevent JVM shutdown issues</li>");
            out.println("        <li>Proper cancellation prevents resource leaks</li>");
            out.println("        <li>Servlet 3.0+ AsyncContext API compliance</li>");
            out.println("        <li>Comprehensive logging for debugging</li>");
            out.println("        <li>Dynamic timeout reconfiguration support</li>");
            out.println("    </ul>");
            
            out.println("</body>");
            out.println("</html>");
        }
    }
}
