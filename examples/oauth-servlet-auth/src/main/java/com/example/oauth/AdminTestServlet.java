/*
 * AdminTestServlet.java
 * Admin-only test servlet for OAuth authentication demonstration.
 */

package com.example.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Admin-only test servlet for OAuth authentication demonstration.
 * 
 * This servlet demonstrates role-based access control by providing
 * administrative functionality that requires the "admin" role.
 * Access is controlled by security constraints in web.xml.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AdminTestServlet extends HttpServlet {
    
    private static final Logger LOGGER = Logger.getLogger(AdminTestServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // At this point, both authentication and authorization have succeeded
        // The user has been authenticated via OAuth and has the "admin" role
        
        Principal principal = req.getUserPrincipal();
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"Admin endpoint access successful\",");
            writer.println("  \"endpoint\": \"admin-test\",");
            writer.println("  \"authenticated\": true,");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"admin_access\": true,");
            writer.println("  \"timestamp\": \"" + new Date() + "\",");
            
            // Admin-specific information
            writer.println("  \"admin_info\": {");
            writer.println("    \"server_info\": \"" + escapeJson(getServletContext().getServerInfo()) + "\",");
            writer.println("    \"servlet_version\": \"" + getServletContext().getMajorVersion() + "." + 
                          getServletContext().getMinorVersion() + "\",");
            writer.println("    \"context_path\": \"" + escapeJson(getServletContext().getContextPath()) + "\",");
            writer.println("    \"real_path\": \"" + escapeJson(getServletContext().getRealPath("/")) + "\"");
            writer.println("  },");
            
            // System information (admin-only)
            writer.println("  \"system_info\": {");
            writer.println("    \"java_version\": \"" + System.getProperty("java.version") + "\",");
            writer.println("    \"os_name\": \"" + System.getProperty("os.name") + "\",");
            writer.println("    \"user_name\": \"" + System.getProperty("user.name") + "\",");
            writer.println("    \"available_processors\": " + Runtime.getRuntime().availableProcessors() + ",");
            writer.println("    \"free_memory\": " + Runtime.getRuntime().freeMemory() + ",");
            writer.println("    \"total_memory\": " + Runtime.getRuntime().totalMemory() + ",");
            writer.println("    \"max_memory\": " + Runtime.getRuntime().maxMemory());
            writer.println("  },");
            
            // Role verification
            writer.println("  \"roles\": {");
            writer.println("    \"admin\": " + req.isUserInRole("admin") + ",");
            writer.println("    \"user\": " + req.isUserInRole("user") + ",");
            writer.println("    \"moderator\": " + req.isUserInRole("moderator"));
            writer.println("  }");
            
            writer.println("}");
        }
        
        LOGGER.info("Admin endpoint accessed by user: " + principal.getName());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Admin configuration update endpoint
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Principal principal = req.getUserPrincipal();
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"Admin configuration updated\",");
            writer.println("  \"operation\": \"POST\",");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"note\": \"This would perform admin configuration updates in a real application\"");
            writer.println("}");
        }
        
        LOGGER.info("Admin configuration update requested by user: " + principal.getName());
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Admin resource creation endpoint
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Principal principal = req.getUserPrincipal();
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"Admin resource created\",");
            writer.println("  \"operation\": \"PUT\",");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"resource_id\": \"admin-resource-" + System.currentTimeMillis() + "\",");
            writer.println("  \"note\": \"This would create admin resources in a real application\"");
            writer.println("}");
        }
        
        LOGGER.info("Admin resource creation requested by user: " + principal.getName());
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Admin resource deletion endpoint
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Principal principal = req.getUserPrincipal();
        String resourceId = req.getParameter("id");
        
        if (resourceId == null || resourceId.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            try (PrintWriter writer = resp.getWriter()) {
                writer.println("{");
                writer.println("  \"status\": \"error\",");
                writer.println("  \"message\": \"Resource ID parameter is required\",");
                writer.println("  \"operation\": \"DELETE\",");
                writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\"");
                writer.println("}");
            }
            return;
        }
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"Admin resource deleted\",");
            writer.println("  \"operation\": \"DELETE\",");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"resource_id\": \"" + escapeJson(resourceId) + "\",");
            writer.println("  \"note\": \"This would delete admin resources in a real application\"");
            writer.println("}");
        }
        
        LOGGER.info("Admin resource deletion requested by user: " + principal.getName() + 
                   ", resource: " + resourceId);
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
