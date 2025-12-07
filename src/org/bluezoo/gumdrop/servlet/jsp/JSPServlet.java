/*
 * JSPServlet.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bluezoo.gumdrop.servlet.Context;

/**
 * Servlet that handles JSP compilation and execution.
 * 
 * <p>This servlet is responsible for:</p>
 * <ul>
 *   <li>Detecting JSP file requests (*.jsp, *.jspx)</li>
 *   <li>Compiling JSP files to servlet classes when needed</li>
 *   <li>Loading and executing the compiled servlets</li>
 *   <li>Handling JSP lifecycle and error management</li>
 * </ul>
 * 
 * <p>The servlet integrates with Gumdrop's JSP parsing and compilation 
 * infrastructure to provide transparent JSP processing.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger("org.bluezoo.gumdrop.servlet.jsp");
    
    // Cache compiled JSP servlets to avoid recompilation on every request
    private final ConcurrentHashMap<String, Servlet> compiledServletCache = new ConcurrentHashMap<>();
    
    /**
     * Handles GET requests for JSP files.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        processJSPRequest(request, response);
    }
    
    /**
     * Handles POST requests for JSP files.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        processJSPRequest(request, response);
    }
    
    /**
     * Handles PUT requests for JSP files.
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        processJSPRequest(request, response);
    }
    
    /**
     * Handles DELETE requests for JSP files.
     */
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        processJSPRequest(request, response);
    }
    
    /**
     * Main JSP processing method that handles compilation and execution.
     * 
     * @param request the HTTP request
     * @param response the HTTP response
     * @throws ServletException if JSP processing fails
     * @throws IOException if I/O operations fail
     */
    private void processJSPRequest(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // Get the requested JSP path
        String jspPath = getJSPPath(request);
        if (jspPath == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "JSP file not found");
            return;
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Processing JSP request for: " + jspPath);
        }
        
        try {
            // Check if we have a cached compiled servlet for this JSP
            Servlet jspServlet = compiledServletCache.get(jspPath);
            
            if (jspServlet == null) {
                // Not in cache, need to compile
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Compiling JSP: " + jspPath);
                }
                
                // Get the servlet context (which should be our Context)
                Context context = getContext();
                if (context == null) {
                    throw new ServletException("Unable to access servlet context for JSP processing");
                }
                
                // Use the Context's JSP compilation to get the servlet
                jspServlet = context.parseJSPFile(jspPath);
                
                // Initialize the servlet
                jspServlet.init(getServletConfig());
                
                // Cache the compiled servlet
                compiledServletCache.put(jspPath, jspServlet);
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Cached compiled JSP servlet: " + jspPath);
                }
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Using cached JSP servlet: " + jspPath);
                }
            }
            
            // Execute the compiled JSP servlet
            jspServlet.service(request, response);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "JSP processing error for " + jspPath, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "JSP processing error: " + e.getMessage());
        }
    }
    
    /**
     * Clears the compiled JSP servlet cache.
     * This can be useful during development when JSP files are modified.
     */
    public void clearCache() {
        compiledServletCache.clear();
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Cleared JSP servlet cache");
        }
    }
    
    /**
     * Gets the current size of the compiled JSP servlet cache.
     * 
     * @return the number of cached JSP servlets
     */
    public int getCacheSize() {
        return compiledServletCache.size();
    }
    
    /**
     * Extracts the JSP file path from the request.
     * 
     * @param request the HTTP request
     * @return the JSP path, or null if not a valid JSP request
     */
    private String getJSPPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        
        // Construct the full path
        String jspPath;
        if (pathInfo != null) {
            jspPath = servletPath + pathInfo;
        } else {
            jspPath = servletPath;
        }
        
        // Validate it's a JSP file
        if (jspPath != null && (jspPath.endsWith(".jsp") || jspPath.endsWith(".jspx"))) {
            return jspPath;
        }
        
        return null;
    }
    
    /**
     * Gets the Gumdrop Context from the servlet context.
     * 
     * @return the Context instance, or null if not available
     */
    private Context getContext() {
        javax.servlet.ServletContext servletContext = getServletContext();
        if (servletContext instanceof Context) {
            return (Context) servletContext;
        }
        
        // Try to get it as an attribute (alternative approach)
        Object contextAttr = servletContext.getAttribute("org.bluezoo.gumdrop.servlet.Context");
        if (contextAttr instanceof Context) {
            return (Context) contextAttr;
        }
        
        return null;
    }
    
    /**
     * Checks if a servlet has been initialized.
     * This is a simplified check - in a production system you'd want 
     * more sophisticated lifecycle management.
     * 
     * @param servlet the servlet to check
     * @return true if the servlet is initialized
     */
    private boolean isServletInitialized(javax.servlet.Servlet servlet) {
        // For now, assume all servlets returned by parseJSPFile need initialization
        // In a more sophisticated implementation, you might track this state
        return false;
    }
    
    /**
     * Marks a servlet as initialized.
     * This is a placeholder for more sophisticated lifecycle management.
     * 
     * @param servlet the servlet that has been initialized
     */
    private void markServletInitialized(javax.servlet.Servlet servlet) {
        // Placeholder for tracking servlet initialization state
        // In a production implementation, you might store this in a registry
    }
    
    @Override
    public String getServletInfo() {
        return "Gumdrop JSP Servlet - Handles compilation and execution of JSP files";
    }
}
