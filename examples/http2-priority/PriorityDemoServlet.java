/*
 * PriorityDemoServlet.java
 * HTTP/2 Stream Priority Demonstration for Gumdrop Server
 * 
 * This example demonstrates HTTP/2 stream priority handling
 * with PriorityAwareHTTPServer.
 */

package examples.http2priority;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Example servlet demonstrating HTTP/2 stream priority benefits.
 * 
 * <p>This servlet creates different response patterns to showcase
 * how stream priority affects resource allocation:
 * <ul>
 * <li>High-priority critical resources (CSS, critical JS)</li>
 * <li>Medium-priority content resources (HTML, API data)</li>
 * <li>Low-priority secondary resources (images, analytics)</li>
 * </ul>
 */
public class PriorityDemoServlet extends HttpServlet {
    
    private static final Random RANDOM = new Random();
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String resource = request.getParameter("resource");
        if (resource == null) {
            resource = "index";
        }
        
        switch (resource) {
            case "critical-css":
                serveCriticalCSS(response);
                break;
            case "critical-js":
                serveCriticalJS(response);
                break;
            case "content":
                serveContent(response);
                break;
            case "api-data":
                serveAPIData(response);
                break;
            case "image":
                serveImage(response);
                break;
            case "analytics":
                serveAnalytics(response);
                break;
            case "test":
                serveTestPage(response);
                break;
            default:
                serveIndexPage(response);
                break;
        }
    }
    
    /**
     * Critical CSS - Highest priority for render blocking resources.
     */
    private void serveCriticalCSS(HttpServletResponse response) throws IOException {
        response.setContentType("text/css");
        response.setHeader("Cache-Control", "max-age=3600");
        
        // Simulate processing time for critical CSS
        simulateProcessing(50, 100);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("/* Critical CSS - Highest Priority */");
            out.println("body { font-family: Arial, sans-serif; margin: 0; padding: 20px; }");
            out.println("h1 { color: #2c3e50; margin-bottom: 20px; }");
            out.println("h2 { color: #34495e; border-bottom: 2px solid #3498db; padding-bottom: 10px; }");
            out.println(".priority-high { background-color: #e74c3c; color: white; padding: 10px; margin: 5px 0; }");
            out.println(".priority-medium { background-color: #f39c12; color: white; padding: 10px; margin: 5px 0; }");
            out.println(".priority-low { background-color: #95a5a6; color: white; padding: 10px; margin: 5px 0; }");
            out.println(".resource-box { border: 1px solid #bdc3c7; padding: 15px; margin: 10px 0; border-radius: 5px; }");
        }
    }
    
    /**
     * Critical JavaScript - High priority for essential functionality.
     */
    private void serveCriticalJS(HttpServletResponse response) throws IOException {
        response.setContentType("application/javascript");
        response.setHeader("Cache-Control", "max-age=3600");
        
        // Simulate processing time for critical JS
        simulateProcessing(30, 80);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("/* Critical JavaScript - High Priority */");
            out.println("console.log('Critical JS loaded with high priority');");
            out.println("");
            out.println("function loadResource(type, delay) {");
            out.println("    const start = Date.now();");
            out.println("    fetch('?resource=' + type)");
            out.println("        .then(() => {");
            out.println("            const elapsed = Date.now() - start;");
            out.println("            console.log(type + ' loaded in ' + elapsed + 'ms');");
            out.println("            updateStatus(type, elapsed);");
            out.println("        });");
            out.println("}");
            out.println("");
            out.println("function updateStatus(type, elapsed) {");
            out.println("    const statusDiv = document.getElementById('status-' + type);");
            out.println("    if (statusDiv) {");
            out.println("        statusDiv.innerHTML += '<br>Loaded in ' + elapsed + 'ms';");
            out.println("        statusDiv.className += ' loaded';");
            out.println("    }");
            out.println("}");
            out.println("");
            out.println("function startPriorityTest() {");
            out.println("    console.log('Starting HTTP/2 priority test...');");
            out.println("    // Simulate concurrent requests with different priorities");
            out.println("    loadResource('content', 0);");
            out.println("    loadResource('api-data', 0);");
            out.println("    loadResource('image', 0);");
            out.println("    loadResource('analytics', 0);");
            out.println("}");
        }
    }
    
    /**
     * Content - Medium priority for main page content.
     */
    private void serveContent(HttpServletResponse response) throws IOException {
        response.setContentType("text/html");
        
        // Simulate content generation processing time
        simulateProcessing(100, 200);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<div class='resource-box'>");
            out.println("<h3>Main Content (Medium Priority)</h3>");
            out.println("<p>This content has medium priority. It's important but not render-blocking.</p>");
            out.println("<p>In HTTP/2 priority systems, this would typically be processed after critical CSS/JS but before images and analytics.</p>");
            out.println("<p>Processing time: 100-200ms simulation</p>");
            out.println("</div>");
        }
    }
    
    /**
     * API Data - Medium priority for dynamic data.
     */
    private void serveAPIData(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        // Simulate API processing time
        simulateProcessing(80, 150);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("{");
            out.println("  \"type\": \"api-data\",");
            out.println("  \"priority\": \"medium\",");
            out.println("  \"description\": \"Dynamic API data with medium priority\",");
            out.println("  \"processingTime\": \"80-150ms\",");
            out.println("  \"data\": [");
            out.println("    { \"id\": 1, \"name\": \"High Priority Resource\", \"weight\": 256 },");
            out.println("    { \"id\": 2, \"name\": \"Medium Priority Resource\", \"weight\": 128 },");
            out.println("    { \"id\": 3, \"name\": \"Low Priority Resource\", \"weight\": 64 }");
            out.println("  ],");
            out.println("  \"timestamp\": " + System.currentTimeMillis());
            out.println("}");
        }
    }
    
    /**
     * Image - Lower priority for non-critical visual content.
     */
    private void serveImage(HttpServletResponse response) throws IOException {
        response.setContentType("image/svg+xml");
        
        // Simulate image processing/generation time
        simulateProcessing(200, 400);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<?xml version='1.0' encoding='UTF-8'?>");
            out.println("<svg width='200' height='100' xmlns='http://www.w3.org/2000/svg'>");
            out.println("  <rect width='200' height='100' fill='#95a5a6'/>");
            out.println("  <text x='100' y='35' text-anchor='middle' fill='white' font-family='Arial' font-size='14'>");
            out.println("    Low Priority Image");
            out.println("  </text>");
            out.println("  <text x='100' y='55' text-anchor='middle' fill='white' font-family='Arial' font-size='12'>");
            out.println("    200-400ms processing");
            out.println("  </text>");
            out.println("  <text x='100' y='75' text-anchor='middle' fill='white' font-family='Arial' font-size='10'>");
            out.println("    Generated: " + new java.util.Date() + "");
            out.println("  </text>");
            out.println("</svg>");
        }
    }
    
    /**
     * Analytics - Lowest priority for tracking/monitoring.
     */
    private void serveAnalytics(HttpServletResponse response) throws IOException {
        response.setContentType("application/javascript");
        
        // Simulate analytics processing time (intentionally slow)
        simulateProcessing(300, 500);
        
        try (PrintWriter out = response.getWriter()) {
            out.println("/* Analytics Script - Lowest Priority */");
            out.println("console.log('Analytics loaded (lowest priority)');");
            out.println("");
            out.println("// Simulate analytics tracking");
            out.println("(function() {");
            out.println("    const analytics = {");
            out.println("        track: function(event, data) {");
            out.println("            console.log('Analytics event:', event, data);");
            out.println("        },");
            out.println("        pageView: function() {");
            out.println("            this.track('pageview', { url: window.location.href });");
            out.println("        },");
            out.println("        priority: 'lowest',");
            out.println("        processingTime: '300-500ms'");
            out.println("    };");
            out.println("    ");
            out.println("    // This would typically be processed last due to low priority");
            out.println("    analytics.pageView();");
            out.println("    ");
            out.println("    window.analytics = analytics;");
            out.println("})();");
        }
    }
    
    /**
     * Test page to demonstrate priority loading behavior.
     */
    private void serveTestPage(HttpServletResponse response) throws IOException {
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>HTTP/2 Priority Test</title>");
            out.println("    <link rel='stylesheet' href='?resource=critical-css'>");
            out.println("    <script src='?resource=critical-js'></script>");
            out.println("    <style>");
            out.println("        .loaded { border-left: 5px solid #27ae60 !important; }");
            out.println("        .status { font-family: monospace; font-size: 12px; color: #7f8c8d; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🚀 HTTP/2 Stream Priority Test</h1>");
            out.println("    <p>This page loads resources with different priorities to demonstrate HTTP/2 stream priority scheduling.</p>");
            out.println("    ");
            out.println("    <button onclick='startPriorityTest()'>Start Priority Test</button>");
            out.println("    ");
            out.println("    <div class='priority-high resource-box'>");
            out.println("        <h3>🔴 High Priority Resources</h3>");
            out.println("        <div>Critical CSS: <span class='status' id='status-critical-css'>Ready</span></div>");
            out.println("        <div>Critical JS: <span class='status' id='status-critical-js'>Ready</span></div>");
            out.println("    </div>");
            out.println("    ");
            out.println("    <div class='priority-medium resource-box'>");
            out.println("        <h3>🟡 Medium Priority Resources</h3>");
            out.println("        <div>Content: <span class='status' id='status-content'>Ready</span></div>");
            out.println("        <div>API Data: <span class='status' id='status-api-data'>Ready</span></div>");
            out.println("    </div>");
            out.println("    ");
            out.println("    <div class='priority-low resource-box'>");
            out.println("        <h3>⚪ Low Priority Resources</h3>");
            out.println("        <div>Image: <span class='status' id='status-image'>Ready</span></div>");
            out.println("        <div>Analytics: <span class='status' id='status-analytics'>Ready</span></div>");
            out.println("    </div>");
            out.println("    ");
            out.println("    <h2>Priority Benefits</h2>");
            out.println("    <ul>");
            out.println("        <li><strong>Critical resources load first</strong> - CSS and JS needed for rendering</li>");
            out.println("        <li><strong>Content loads next</strong> - Important but not render-blocking</li>");
            out.println("        <li><strong>Images and analytics load last</strong> - Nice to have but not critical</li>");
            out.println("        <li><strong>Better user experience</strong> - Page becomes interactive faster</li>");
            out.println("        <li><strong>Optimal resource usage</strong> - Server bandwidth allocated efficiently</li>");
            out.println("    </ul>");
            out.println("    ");
            out.println("    <h2>Technical Implementation</h2>");
            out.println("    <p>This demo uses <code>PriorityAwareHTTPServer</code> which:</p>");
            out.println("    <ul>");
            out.println("        <li>Maintains RFC 7540 compliant dependency trees</li>");
            out.println("        <li>Schedules streams based on calculated priority</li>");
            out.println("        <li>Prevents starvation with fairness algorithms</li>");
            out.println("        <li>Tracks resource usage for optimization</li>");
            out.println("    </ul>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Main index page with navigation.
     */
    private void serveIndexPage(HttpServletResponse response) throws IOException {
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>HTTP/2 Priority Demo</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; }");
            out.println("        .demo-link { display: block; margin: 10px 0; padding: 15px; ");
            out.println("                    background: #3498db; color: white; text-decoration: none; border-radius: 5px; }");
            out.println("        .demo-link:hover { background: #2980b9; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🎯 HTTP/2 Stream Priority Demo</h1>");
            out.println("    <p>This demonstration shows HTTP/2 stream priority handling with Gumdrop's PriorityAwareHTTPServer.</p>");
            out.println("    ");
            out.println("    <h2>Available Demonstrations</h2>");
            out.println("    ");
            out.println("    <a href='?resource=test' class='demo-link'>");
            out.println("        🚀 Interactive Priority Test");
            out.println("        <br><small>Load resources with different priorities and see the scheduling effects</small>");
            out.println("    </a>");
            out.println("    ");
            out.println("    <h2>Individual Resources</h2>");
            out.println("    <ul>");
            out.println("        <li><a href='?resource=critical-css'>Critical CSS (High Priority)</a></li>");
            out.println("        <li><a href='?resource=critical-js'>Critical JavaScript (High Priority)</a></li>");
            out.println("        <li><a href='?resource=content'>Content (Medium Priority)</a></li>");
            out.println("        <li><a href='?resource=api-data'>API Data (Medium Priority)</a></li>");
            out.println("        <li><a href='?resource=image'>Image (Low Priority)</a></li>");
            out.println("        <li><a href='?resource=analytics'>Analytics (Lowest Priority)</a></li>");
            out.println("    </ul>");
            out.println("    ");
            out.println("    <h2>How Priority Works</h2>");
            out.println("    <p>HTTP/2 stream priority uses dependency trees and weights to optimize resource allocation:</p>");
            out.println("    <ul>");
            out.println("        <li><strong>Dependencies:</strong> Streams can depend on other streams</li>");
            out.println("        <li><strong>Weights:</strong> Sibling streams share resources proportionally</li>");
            out.println("        <li><strong>Exclusive:</strong> A stream can be the exclusive child of its parent</li>");
            out.println("        <li><strong>Scheduling:</strong> Server processes streams based on calculated priority</li>");
            out.println("    </ul>");
            out.println("    ");
            out.println("    <p><em>Note: Use HTTP/2 capable browser and enable developer tools network tab to see the effects.</em></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Simulates processing time with random variation.
     */
    private void simulateProcessing(int minMs, int maxMs) {
        try {
            int delay = minMs + RANDOM.nextInt(maxMs - minMs + 1);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
