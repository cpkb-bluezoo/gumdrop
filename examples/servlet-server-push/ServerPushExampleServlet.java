/*
 * ServerPushExampleServlet.java
 * HTTP/2 Server Push Demonstration for Gumdrop Server
 * 
 * This example demonstrates HTTP/2 server push functionality in Servlet 4.0
 * using Request.newPushBuilder() and PushBuilder methods.
 */

package examples.serverpush;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.PushBuilder;

/**
 * Example servlet demonstrating HTTP/2 server push functionality.
 * 
 * <p>This servlet shows how to use the Servlet 4.0 server push feature
 * to proactively send resources that the client will likely request.
 * Server push reduces round-trip latency by eliminating the need for
 * the client to discover and request dependent resources.
 * 
 * <p>Server push is supported in:
 * <ul>
 * <li>HTTP/2 connections (required)</li>
 * <li>Browsers that support HTTP/2 server push</li>
 * </ul>
 * 
 * <p>Use cases for server push:
 * <ul>
 * <li>CSS stylesheets referenced in HTML pages</li>
 * <li>JavaScript files needed for page functionality</li>
 * <li>Images that are always displayed (logos, critical content)</li>
 * <li>Fonts required for page rendering</li>
 * </ul>
 */
public class ServerPushExampleServlet extends HttpServlet {
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String demo = request.getParameter("demo");
        if (demo == null) {
            demo = "basic";
        }
        
        switch (demo) {
            case "basic":
                demonstrateBasicPush(request, response);
                break;
            case "conditional":
                demonstrateConditionalPush(request, response);
                break;
            case "multiple":
                demonstrateMultiplePush(request, response);
                break;
            case "css":
                serveCSS(response);
                break;
            case "js":
                serveJavaScript(response);
                break;
            case "image":
                serveImage(response);
                break;
            default:
                serveIndexPage(request, response);
                break;
        }
    }
    
    /**
     * Basic server push demonstration.
     */
    private void demonstrateBasicPush(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        // Create a PushBuilder for server push
        PushBuilder pushBuilder = request.newPushBuilder();
        
        if (pushBuilder != null) {
            // Push CSS stylesheet
            pushBuilder.path("/servlet/serverpush?demo=css")
                      .addHeader("Content-Type", "text/css")
                      .push();
            
            // Push JavaScript file  
            pushBuilder.path("/servlet/serverpush?demo=js")
                      .addHeader("Content-Type", "application/javascript")
                      .push();
            
            // Push an image
            pushBuilder.path("/servlet/serverpush?demo=image")
                      .addHeader("Content-Type", "image/png")
                      .push();
        }
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>Basic Server Push Demo</title>");
            out.println("    <link rel='stylesheet' href='?demo=css'>");
            out.println("    <script src='?demo=js'></script>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🚀 Basic HTTP/2 Server Push</h1>");
            if (pushBuilder != null) {
                out.println("    <div class='success'>✅ Server push is supported on this connection!</div>");
                out.println("    <p>This page's resources were pushed by the server:</p>");
                out.println("    <ul>");
                out.println("        <li><strong>CSS stylesheet</strong> - Pushed before HTML parsing</li>");
                out.println("        <li><strong>JavaScript file</strong> - Pushed before HTML parsing</li>");
                out.println("        <li><strong>Image</strong> - Pushed before HTML parsing</li>");
                out.println("    </ul>");
            } else {
                out.println("    <div class='warning'>⚠️ Server push is not supported on this connection (requires HTTP/2)</div>");
                out.println("    <p>Resources will be loaded normally via HTTP requests.</p>");
            }
            out.println("    <h2>Performance Benefits</h2>");
            out.println("    <p>With server push:</p>");
            out.println("    <ol>");
            out.println("        <li>Server immediately pushes critical resources</li>");
            out.println("        <li>Browser receives resources before parsing HTML</li>");
            out.println("        <li>Reduced round-trip latency</li>");
            out.println("        <li>Faster page load times</li>");
            out.println("    </ol>");
            out.println("    <img src='?demo=image' alt='Pushed Image' class='demo-image'>");
            out.println("    <p><a href='?demo=conditional'>Next: Conditional Push →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Conditional server push demonstration.
     */
    private void demonstrateConditionalPush(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        PushBuilder pushBuilder = request.newPushBuilder();
        
        if (pushBuilder != null) {
            // Only push if client doesn't have cached version
            String ifModifiedSince = request.getHeader("If-Modified-Since");
            boolean hasCache = (ifModifiedSince != null);
            
            if (!hasCache) {
                // Push CSS only if client doesn't have it cached
                pushBuilder.path("/servlet/serverpush?demo=css")
                          .addHeader("Cache-Control", "max-age=3600")
                          .push();
            }
            
            // Always push critical JavaScript (small file)
            pushBuilder.path("/servlet/serverpush?demo=js")
                      .addHeader("Content-Type", "application/javascript")
                      .addHeader("Cache-Control", "max-age=1800")
                      .push();
        }
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>Conditional Server Push Demo</title>");
            out.println("    <link rel='stylesheet' href='?demo=css'>");
            out.println("    <script src='?demo=js'></script>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🎯 Conditional HTTP/2 Server Push</h1>");
            
            if (pushBuilder != null) {
                String ifModifiedSince = request.getHeader("If-Modified-Since");
                boolean hasCache = (ifModifiedSince != null);
                
                out.println("    <div class='info'>");
                out.println("        <h3>Push Strategy Applied:</h3>");
                if (hasCache) {
                    out.println("        <p>✅ Client has cached CSS - <strong>CSS not pushed</strong></p>");
                } else {
                    out.println("        <p>🚀 Fresh client - <strong>CSS pushed</strong></p>");
                }
                out.println("        <p>🚀 Critical JavaScript - <strong>Always pushed</strong></p>");
                out.println("    </div>");
            } else {
                out.println("    <div class='warning'>⚠️ Server push not supported</div>");
            }
            
            out.println("    <h2>Conditional Push Logic</h2>");
            out.println("    <pre>");
            out.println("if (pushBuilder != null) {");
            out.println("    String ifModifiedSince = request.getHeader(\"If-Modified-Since\");");
            out.println("    boolean hasCache = (ifModifiedSince != null);");
            out.println("    ");
            out.println("    if (!hasCache) {");
            out.println("        // Push CSS only if client doesn't have it cached");
            out.println("        pushBuilder.path(\"/css/styles.css\").push();");
            out.println("    }");
            out.println("    ");
            out.println("    // Always push critical resources");
            out.println("    pushBuilder.path(\"/js/critical.js\").push();");
            out.println("}");
            out.println("    </pre>");
            
            out.println("    <p>Refresh this page to see caching behavior!</p>");
            out.println("    <p><a href='?demo=multiple'>Next: Multiple Resources →</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Multiple resource push demonstration.
     */
    private void demonstrateMultiplePush(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        PushBuilder pushBuilder = request.newPushBuilder();
        
        if (pushBuilder != null) {
            // Define resources to push
            List<String> criticalResources = Arrays.asList(
                "/servlet/serverpush?demo=css",
                "/servlet/serverpush?demo=js"
            );
            
            // Push all critical resources
            for (String resource : criticalResources) {
                pushBuilder.path(resource)
                          .addHeader("Cache-Control", "max-age=3600")
                          .push();
            }
            
            // Reuse pushBuilder for additional resources
            pushBuilder.path("/servlet/serverpush?demo=image")
                      .removeHeader("Cache-Control")  // Different caching for images
                      .addHeader("Cache-Control", "max-age=86400")  // 24 hours
                      .push();
        }
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>Multiple Resource Push Demo</title>");
            out.println("    <link rel='stylesheet' href='?demo=css'>");
            out.println("    <script src='?demo=js'></script>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>📦 Multiple Resource Server Push</h1>");
            
            if (pushBuilder != null) {
                out.println("    <div class='success'>");
                out.println("        <h3>🚀 Resources Pushed:</h3>");
                out.println("        <ul>");
                out.println("            <li>✅ CSS stylesheet (cached 1 hour)</li>");
                out.println("            <li>✅ JavaScript file (cached 1 hour)</li>");
                out.println("            <li>✅ Hero image (cached 24 hours)</li>");
                out.println("        </ul>");
                out.println("    </div>");
            } else {
                out.println("    <div class='warning'>⚠️ Server push not supported</div>");
            }
            
            out.println("    <h2>PushBuilder Reuse Pattern</h2>");
            out.println("    <p>The same PushBuilder instance can be reused for multiple resources:</p>");
            out.println("    <pre>");
            out.println("PushBuilder pushBuilder = request.newPushBuilder();");
            out.println("");
            out.println("// Push CSS");
            out.println("pushBuilder.path(\"/css/styles.css\")");
            out.println("          .addHeader(\"Cache-Control\", \"max-age=3600\")");
            out.println("          .push();");
            out.println("");
            out.println("// Reuse for JavaScript (path resets automatically)");
            out.println("pushBuilder.path(\"/js/app.js\")");
            out.println("          .push();  // Headers from previous push are inherited");
            out.println("");
            out.println("// Modify headers for different resource type");
            out.println("pushBuilder.path(\"/images/hero.jpg\")");
            out.println("          .removeHeader(\"Cache-Control\")");
            out.println("          .addHeader(\"Cache-Control\", \"max-age=86400\")");
            out.println("          .push();");
            out.println("    </pre>");
            
            out.println("    <div class='image-section'>");
            out.println("        <h3>Pushed Image Content</h3>");
            out.println("        <img src='?demo=image' alt='Server Pushed Image' class='demo-image'>");
            out.println("        <p><em>This image was delivered via server push before the HTML was fully parsed!</em></p>");
            out.println("    </div>");
            
            out.println("    <p><a href='?'>← Back to Index</a></p>");
            out.println("</body>");
            out.println("</html>");
        }
    }
    
    /**
     * Serve CSS stylesheet (can be pushed resource).
     */
    private void serveCSS(HttpServletResponse response) throws IOException {
        response.setContentType("text/css; charset=UTF-8");
        response.setHeader("Cache-Control", "max-age=3600"); // Cache for 1 hour
        
        try (PrintWriter out = response.getWriter()) {
            out.println("/* Server Push Demo CSS */");
            out.println("body {");
            out.println("    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;");
            out.println("    margin: 40px;");
            out.println("    line-height: 1.6;");
            out.println("    background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);");
            out.println("    min-height: 100vh;");
            out.println("}");
            out.println("");
            out.println("h1 { color: #2c3e50; text-shadow: 1px 1px 2px rgba(0,0,0,0.1); }");
            out.println("h2 { color: #34495e; border-bottom: 2px solid #3498db; padding-bottom: 10px; }");
            out.println("h3 { color: #2980b9; }");
            out.println("");
            out.println(".success {");
            out.println("    background: #d4edda; border: 1px solid #c3e6cb; color: #155724;");
            out.println("    padding: 15px; border-radius: 8px; margin: 20px 0;");
            out.println("    box-shadow: 0 2px 4px rgba(0,0,0,0.1);");
            out.println("}");
            out.println("");
            out.println(".warning {");
            out.println("    background: #fff3cd; border: 1px solid #ffeaa7; color: #856404;");
            out.println("    padding: 15px; border-radius: 8px; margin: 20px 0;");
            out.println("    box-shadow: 0 2px 4px rgba(0,0,0,0.1);");
            out.println("}");
            out.println("");
            out.println(".info {");
            out.println("    background: #d1ecf1; border: 1px solid #bee5eb; color: #0c5460;");
            out.println("    padding: 15px; border-radius: 8px; margin: 20px 0;");
            out.println("    box-shadow: 0 2px 4px rgba(0,0,0,0.1);");
            out.println("}");
            out.println("");
            out.println("pre {");
            out.println("    background: #f8f9fa; border: 1px solid #e9ecef; color: #495057;");
            out.println("    padding: 15px; border-radius: 5px; overflow-x: auto;");
            out.println("    font-family: 'Consolas', 'Monaco', 'Courier New', monospace;");
            out.println("    font-size: 14px; line-height: 1.4;");
            out.println("}");
            out.println("");
            out.println(".demo-image {");
            out.println("    max-width: 300px; height: auto; border-radius: 8px;");
            out.println("    box-shadow: 0 4px 8px rgba(0,0,0,0.2); margin: 20px 0;");
            out.println("    display: block;");
            out.println("}");
            out.println("");
            out.println(".image-section {");
            out.println("    text-align: center; margin: 30px 0;");
            out.println("    padding: 20px; background: white; border-radius: 10px;");
            out.println("    box-shadow: 0 2px 10px rgba(0,0,0,0.1);");
            out.println("}");
            out.println("");
            out.println("a {");
            out.println("    color: #3498db; text-decoration: none; font-weight: 500;");
            out.println("    border-bottom: 2px solid transparent; transition: border-color 0.3s;");
            out.println("}");
            out.println("");
            out.println("a:hover { border-bottom-color: #3498db; }");
            out.println("");
            out.println("/* Animation for pushed content */");
            out.println("@keyframes slideIn {");
            out.println("    from { opacity: 0; transform: translateY(-10px); }");
            out.println("    to { opacity: 1; transform: translateY(0); }");
            out.println("}");
            out.println("");
            out.println("body > * { animation: slideIn 0.5s ease-out; }");
        }
    }
    
    /**
     * Serve JavaScript file (can be pushed resource).
     */
    private void serveJavaScript(HttpServletResponse response) throws IOException {
        response.setContentType("application/javascript; charset=UTF-8");
        response.setHeader("Cache-Control", "max-age=1800"); // Cache for 30 minutes
        
        try (PrintWriter out = response.getWriter()) {
            out.println("/* Server Push Demo JavaScript */");
            out.println("console.log('🚀 JavaScript loaded via HTTP/2 Server Push!');");
            out.println("");
            out.println("document.addEventListener('DOMContentLoaded', function() {");
            out.println("    console.log('📄 DOM Content Loaded');");
            out.println("    ");
            out.println("    // Add push notification");
            out.println("    const pushNotification = document.createElement('div');");
            out.println("    pushNotification.style.cssText = `");
            out.println("        position: fixed; top: 20px; right: 20px; z-index: 1000;");
            out.println("        background: #28a745; color: white; padding: 15px 20px;");
            out.println("        border-radius: 5px; box-shadow: 0 2px 10px rgba(0,0,0,0.2);");
            out.println("        font-family: inherit; font-size: 14px; max-width: 300px;");
            out.println("        transform: translateX(100%); transition: transform 0.3s ease;");
            out.println("    `;");
            out.println("    pushNotification.innerHTML = `");
            out.println("        <strong>⚡ Server Push Active!</strong><br>");
            out.println("        This JavaScript was delivered before HTML parsing completed.");
            out.println("    `;");
            out.println("    ");
            out.println("    document.body.appendChild(pushNotification);");
            out.println("    ");
            out.println("    // Animate in");
            out.println("    setTimeout(() => {");
            out.println("        pushNotification.style.transform = 'translateX(0)';");
            out.println("    }, 500);");
            out.println("    ");
            out.println("    // Auto-hide after 5 seconds");
            out.println("    setTimeout(() => {");
            out.println("        pushNotification.style.transform = 'translateX(100%)';");
            out.println("        setTimeout(() => pushNotification.remove(), 300);");
            out.println("    }, 5000);");
            out.println("    ");
            out.println("    // Log resource loading times");
            out.println("    if (window.performance && window.performance.getEntriesByType) {");
            out.println("        const resources = window.performance.getEntriesByType('resource');");
            out.println("        console.log('📊 Resource Loading Performance:');");
            out.println("        resources.forEach(resource => {");
            out.println("            if (resource.name.includes('serverpush')) {");
            out.println("                console.log(`  ${resource.name}: ${resource.duration.toFixed(2)}ms`);");
            out.println("            }");
            out.println("        });");
            out.println("    }");
            out.println("});");
            out.println("");
            out.println("// Demonstrate server push timing");
            out.println("window.serverPushDemo = {");
            out.println("    logTiming: function() {");
            out.println("        console.log('⏱️ Server Push Timing:');");
            out.println("        console.log('  - PUSH_PROMISE sent before HTML parsing');");
            out.println("        console.log('  - Resources delivered in parallel with HTML');");
            out.println("        console.log('  - Browser cache populated before resource requests');");
            out.println("        console.log('  - Reduced round-trip latency achieved');");
            out.println("    }");
            out.println("};");
            out.println("");
            out.println("// Auto-run timing demo");
            out.println("setTimeout(window.serverPushDemo.logTiming, 1000);");
        }
    }
    
    /**
     * Serve a simple image (can be pushed resource).
     */
    private void serveImage(HttpServletResponse response) throws IOException {
        response.setContentType("image/png");
        response.setHeader("Cache-Control", "max-age=86400"); // Cache for 24 hours
        
        // Simple 1x1 PNG image as a demo (in production, use actual images)
        byte[] pngBytes = {
            (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // PNG signature
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,        // IHDR chunk
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,        // 1x1 image
            0x08, 0x02, 0x00, 0x00, 0x00, (byte)0x90, 0x77, 0x53, (byte)0xDE,
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,        // IDAT chunk
            0x08, (byte)0xD7, 0x63, (byte)0xF8, (byte)0x0F, 0x00, 0x00, 0x01, 0x00, 0x01,
            (byte)0x9A, 0x60, (byte)0xE1, (byte)0xD5,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,        // IEND chunk
            (byte)0xAE, 0x42, 0x60, (byte)0x82
        };
        
        response.getOutputStream().write(pngBytes);
    }
    
    /**
     * Main index page with navigation and explanation.
     */
    private void serveIndexPage(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        response.setContentType("text/html; charset=UTF-8");
        
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("<head>");
            out.println("    <title>HTTP/2 Server Push Demo</title>");
            out.println("    <style>");
            out.println("        body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }");
            out.println("        .demo-link { display: block; margin: 15px 0; padding: 20px;");
            out.println("                    background: #3498db; color: white; text-decoration: none; border-radius: 8px; }");
            out.println("        .demo-link:hover { background: #2980b9; }");
            out.println("        .protocol-info { background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; }");
            out.println("        .code { background: #f4f4f4; padding: 15px; border-radius: 5px; margin: 15px 0; }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <h1>🚀 HTTP/2 Server Push Demonstration</h1>");
            out.println("    <p>This demonstration shows Servlet 4.0 HTTP/2 server push support in Gumdrop server.</p>");
            
            // Check if server push is supported
            PushBuilder pushBuilder = request.newPushBuilder();
            if (pushBuilder != null) {
                out.println("    <div style='background: #d4edda; border: 1px solid #c3e6cb; color: #155724; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("        ✅ <strong>Server Push is SUPPORTED</strong> on this connection!");
                out.println("        <br>You're using HTTP/2 and can see server push in action.");
                out.println("    </div>");
            } else {
                out.println("    <div style='background: #fff3cd; border: 1px solid #ffeaa7; color: #856404; padding: 15px; border-radius: 8px; margin: 20px 0;'>");
                out.println("        ⚠️ <strong>Server Push is NOT SUPPORTED</strong> on this connection.");
                out.println("        <br>You may be using HTTP/1.x. Try accessing via HTTPS with an HTTP/2-capable browser.");
                out.println("    </div>");
            }
            
            out.println("    <div class='protocol-info'>");
            out.println("        <h3>📋 Connection Information</h3>");
            out.println("        <ul>");
            out.println("            <li><strong>Protocol:</strong> " + request.getProtocol() + "</li>");
            out.println("            <li><strong>Scheme:</strong> " + request.getScheme() + "</li>");
            out.println("            <li><strong>Server Push Available:</strong> " + (pushBuilder != null ? "Yes" : "No") + "</li>");
            out.println("        </ul>");
            out.println("    </div>");
            
            out.println("    <h2>📚 Available Demonstrations</h2>");
            
            out.println("    <a href='?demo=basic' class='demo-link'>");
            out.println("        🚀 Basic Server Push");
            out.println("        <br><small>Push CSS, JavaScript, and images with HTML response</small>");
            out.println("    </a>");
            
            out.println("    <a href='?demo=conditional' class='demo-link'>");
            out.println("        🎯 Conditional Server Push");
            out.println("        <br><small>Smart pushing based on client cache status</small>");
            out.println("    </a>");
            
            out.println("    <a href='?demo=multiple' class='demo-link'>");
            out.println("        📦 Multiple Resource Push");
            out.println("        <br><small>Push multiple resources with PushBuilder reuse</small>");
            out.println("    </a>");
            
            out.println("    <h2>💡 What is HTTP/2 Server Push?</h2>");
            out.println("    <p>HTTP/2 Server Push allows the server to proactively send resources to the client ");
            out.println("    that it knows the client will need, eliminating round-trip latency.</p>");
            
            out.println("    <h3>🎯 Benefits</h3>");
            out.println("    <ul>");
            out.println("        <li><strong>Reduced Latency:</strong> Resources delivered before client requests them</li>");
            out.println("        <li><strong>Improved Performance:</strong> Critical resources available immediately</li>");
            out.println("        <li><strong>Better User Experience:</strong> Faster page load times</li>");
            out.println("        <li><strong>Network Efficiency:</strong> Fewer round trips required</li>");
            out.println("    </ul>");
            
            out.println("    <h2>🔧 Implementation</h2>");
            out.println("    <div class='code'>");
            out.println("    <strong>Servlet 4.0 Server Push API:</strong><br>");
            out.println("    PushBuilder pushBuilder = request.newPushBuilder();<br>");
            out.println("    if (pushBuilder != null) {<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;pushBuilder.path(\"/css/styles.css\")<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;.addHeader(\"Cache-Control\", \"max-age=3600\")<br>");
            out.println("    &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;.push();<br>");
            out.println("    }");
            out.println("    </div>");
            
            out.println("    <h2>🌐 Browser Support</h2>");
            out.println("    <ul>");
            out.println("        <li><strong>Requirements:</strong> HTTP/2 connection + Server Push support</li>");
            out.println("        <li><strong>Supported:</strong> Chrome, Firefox, Safari, Edge (modern versions)</li>");
            out.println("        <li><strong>Fallback:</strong> Regular HTTP requests if push not supported</li>");
            out.println("    </ul>");
            
            out.println("</body>");
            out.println("</html>");
        }
    }
}
