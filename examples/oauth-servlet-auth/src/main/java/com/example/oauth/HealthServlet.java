/*
 * HealthServlet.java
 * Public health check servlet that doesn't require authentication.
 */

package com.example.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Public health check servlet that doesn't require authentication.
 * 
 * This servlet provides a simple health check endpoint that can be used
 * by load balancers, monitoring systems, and development tools to verify
 * that the application is running. It demonstrates public endpoints that
 * bypass OAuth authentication.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // This endpoint is public (no authentication required)
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        // Set cache control headers for health checks
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"healthy\",");
            writer.println("  \"message\": \"OAuth servlet authentication example is running\",");
            writer.println("  \"timestamp\": \"" + new Date() + "\",");
            writer.println("  \"application\": {");
            writer.println("    \"name\": \"oauth-servlet-auth-example\",");
            writer.println("    \"version\": \"1.0.0\",");
            writer.println("    \"description\": \"Gumdrop OAuth 2.0 servlet authentication example\"");
            writer.println("  },");
            writer.println("  \"server\": {");
            writer.println("    \"info\": \"" + escapeJson(getServletContext().getServerInfo()) + "\",");
            writer.println("    \"servlet_version\": \"" + getServletContext().getMajorVersion() + "." + 
                          getServletContext().getMinorVersion() + "\"");
            writer.println("  },");
            writer.println("  \"endpoints\": {");
            writer.println("    \"public\": [");
            writer.println("      \"/health\",");
            writer.println("      \"/version\"");
            writer.println("    ],");
            writer.println("    \"protected\": [");
            writer.println("      \"/api/test\",");
            writer.println("      \"/api/admin/test\"");
            writer.println("    ]");
            writer.println("  },");
            writer.println("  \"authentication\": {");
            writer.println("    \"required\": false,");
            writer.println("    \"method\": \"none\",");
            writer.println("    \"note\": \"This is a public health check endpoint\"");
            writer.println("  }");
            writer.println("}");
        }
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Support HEAD requests for health checks (common for load balancers)
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        
        // Set same headers as GET but no body
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Support OPTIONS requests for CORS preflight
        resp.setHeader("Allow", "GET, HEAD, OPTIONS");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(HttpServletResponse.SC_OK);
    }
    
    /**
     * Escapes a string for safe inclusion in JSON.
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "null";
        }
        
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
