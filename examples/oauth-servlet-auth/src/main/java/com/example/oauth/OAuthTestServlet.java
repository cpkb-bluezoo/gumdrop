/*
 * OAuthTestServlet.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.example.oauth;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Test servlet for OAuth authentication demonstration.
 * 
 * This servlet provides a simple endpoint to test OAuth 2.0 authentication
 * and role-based authorization. It displays authentication information and
 * role membership for the authenticated user.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuthTestServlet extends HttpServlet {
    
    private static final Logger LOGGER = Logger.getLogger(OAuthTestServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // At this point, OAuth authentication has already occurred
        // The user is authenticated if we reach this method
        
        Principal principal = req.getUserPrincipal();
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"OAuth authentication test endpoint\",");
            writer.println("  \"authenticated\": true,");
            
            if (principal != null) {
                writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
                writer.println("  \"principal_type\": \"" + escapeJson(principal.getClass().getSimpleName()) + "\",");
            } else {
                writer.println("  \"username\": null,");
                writer.println("  \"principal_type\": null,");
            }
            
            // Check role membership
            writer.println("  \"roles\": {");
            writer.println("    \"user\": " + req.isUserInRole("user") + ",");
            writer.println("    \"admin\": " + req.isUserInRole("admin") + ",");
            writer.println("    \"moderator\": " + req.isUserInRole("moderator"));
            writer.println("  },");
            
            // Request information
            writer.println("  \"request\": {");
            writer.println("    \"method\": \"" + escapeJson(req.getMethod()) + "\",");
            writer.println("    \"uri\": \"" + escapeJson(req.getRequestURI()) + "\",");
            writer.println("    \"query_string\": \"" + escapeJson(req.getQueryString()) + "\",");
            writer.println("    \"remote_addr\": \"" + escapeJson(req.getRemoteAddr()) + "\",");
            writer.println("    \"user_agent\": \"" + escapeJson(req.getHeader("User-Agent")) + "\"");
            writer.println("  },");
            
            // Authentication headers (excluding sensitive Authorization header)
            writer.println("  \"headers\": {");
            boolean first = true;
            Enumeration<String> headerNames = req.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (!"authorization".equalsIgnoreCase(headerName)) {
                    if (!first) {
                        writer.println(",");
                    }
                    writer.print("    \"" + escapeJson(headerName.toLowerCase()) + "\": \"" + 
                               escapeJson(req.getHeader(headerName)) + "\"");
                    first = false;
                }
            }
            writer.println();
            writer.println("  }");
            
            writer.println("}");
        }
        
        LOGGER.info("OAuth test endpoint accessed by user: " + 
                   (principal != null ? principal.getName() : "unknown"));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // POST endpoint for testing with different HTTP methods
        doGet(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // PUT endpoint for testing RESTful operations
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Principal principal = req.getUserPrincipal();
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"PUT operation completed\",");
            writer.println("  \"method\": \"PUT\",");
            writer.println("  \"authenticated\": true,");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"can_write\": " + req.isUserInRole("user"));
            writer.println("}");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // DELETE endpoint - typically requires admin rights
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Principal principal = req.getUserPrincipal();
        
        if (!req.isUserInRole("admin") && !req.isUserInRole("moderator")) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            try (PrintWriter writer = resp.getWriter()) {
                writer.println("{");
                writer.println("  \"status\": \"error\",");
                writer.println("  \"message\": \"Delete operations require admin or moderator role\",");
                writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
                writer.println("  \"required_roles\": [\"admin\", \"moderator\"]");
                writer.println("}");
            }
            return;
        }
        
        try (PrintWriter writer = resp.getWriter()) {
            writer.println("{");
            writer.println("  \"status\": \"success\",");
            writer.println("  \"message\": \"DELETE operation completed\",");
            writer.println("  \"method\": \"DELETE\",");
            writer.println("  \"authenticated\": true,");
            writer.println("  \"username\": \"" + escapeJson(principal.getName()) + "\",");
            writer.println("  \"is_admin\": " + req.isUserInRole("admin") + ",");
            writer.println("  \"is_moderator\": " + req.isUserInRole("moderator"));
            writer.println("}");
        }
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
