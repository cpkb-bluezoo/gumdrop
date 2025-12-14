/*
 * TestServlet.java
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

package org.bluezoo.gumdrop.servlet;

import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-purpose test servlet for integration testing.
 * 
 * <p>Routes requests based on path info to test various servlet features:
 * <ul>
 *   <li>/test/hello - Basic hello response</li>
 *   <li>/test/echo - Echo POST data back</li>
 *   <li>/test/headers - Display request headers</li>
 *   <li>/test/session - Session management testing</li>
 *   <li>/test/upload - Multipart file upload</li>
 *   <li>/test/error - Generate errors (exception or status code)</li>
 *   <li>/test/forward - Forward to another resource</li>
 *   <li>/test/include - Include another resource</li>
 *   <li>/test/async - Async request processing</li>
 *   <li>/test/context - Servlet context attributes</li>
 *   <li>/test/json - JSON content type response</li>
 *   <li>/test/xml - XML content type response</li>
 *   <li>/test/filtered - Used for filter testing</li>
 *   <li>/test/params - Query and form parameters</li>
 *   <li>/test/info - Request information</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(TestServlet.class.getName());

    @Override
    public void init() throws ServletException {
        super.init();
        LOGGER.info("TestServlet.init() called - servlet name: " + getServletName());
        LOGGER.info("TestServlet context path: " + getServletContext().getContextPath());
        LOGGER.info("TestServlet server info: " + getServletContext().getServerInfo());
    }

    @Override
    public void destroy() {
        LOGGER.info("TestServlet.destroy() called - servlet name: " + getServletName());
        super.destroy();
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        
        String pathInfo = req.getPathInfo();
        
        LOGGER.info("TestServlet.service() called:");
        LOGGER.info("  Method: " + req.getMethod());
        LOGGER.info("  RequestURI: " + req.getRequestURI());
        LOGGER.info("  ServletPath: " + req.getServletPath());
        LOGGER.info("  PathInfo: " + pathInfo);
        LOGGER.info("  QueryString: " + req.getQueryString());
        if (pathInfo == null) {
            pathInfo = "";
        }
        
        // Route based on path
        if (pathInfo.equals("/hello") || pathInfo.isEmpty()) {
            handleHello(req, resp);
        } else if (pathInfo.equals("/echo")) {
            handleEcho(req, resp);
        } else if (pathInfo.equals("/headers")) {
            handleHeaders(req, resp);
        } else if (pathInfo.equals("/session")) {
            handleSession(req, resp);
        } else if (pathInfo.equals("/upload")) {
            handleUpload(req, resp);
        } else if (pathInfo.equals("/error")) {
            handleError(req, resp);
        } else if (pathInfo.equals("/forward")) {
            handleForward(req, resp);
        } else if (pathInfo.equals("/include")) {
            handleInclude(req, resp);
        } else if (pathInfo.equals("/async")) {
            handleAsync(req, resp);
        } else if (pathInfo.equals("/context")) {
            handleContext(req, resp);
        } else if (pathInfo.equals("/json")) {
            handleJson(req, resp);
        } else if (pathInfo.equals("/xml")) {
            handleXml(req, resp);
        } else if (pathInfo.equals("/filtered")) {
            handleFiltered(req, resp);
        } else if (pathInfo.equals("/params")) {
            handleParams(req, resp);
        } else if (pathInfo.equals("/info")) {
            handleInfo(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown path: " + pathInfo);
        }
    }

    /**
     * Simple hello response.
     */
    private void handleHello(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("Hello from TestServlet!");
        out.println("Method: " + req.getMethod());
        out.println("Time: " + System.currentTimeMillis());
    }

    /**
     * Echo POST data back to client.
     */
    private void handleEcho(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Echo Response ===");
        out.println("Method: " + req.getMethod());
        out.println("Content-Type: " + req.getContentType());
        out.println("Content-Length: " + req.getContentLength());
        out.println();
        
        // Echo parameters
        out.println("=== Parameters ===");
        Enumeration<String> paramNames = req.getParameterNames();
        while (paramNames.hasMoreElements()) {
            String name = paramNames.nextElement();
            String[] values = req.getParameterValues(name);
            for (String value : values) {
                out.println(name + "=" + value);
            }
        }
        
        // Echo body if not form-encoded
        String contentType = req.getContentType();
        if (contentType != null && !contentType.contains("application/x-www-form-urlencoded")) {
            out.println();
            out.println("=== Body ===");
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }
        }
    }

    /**
     * Display request headers.
     */
    private void handleHeaders(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Request Headers ===");
        Enumeration<String> headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            Enumeration<String> values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                out.println(name + ": " + values.nextElement());
            }
        }
        
        // Also set a response header to test
        resp.setHeader("X-Response-Time", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * Session management testing.
     */
    private void handleSession(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        String action = req.getParameter("action");
        String key = req.getParameter("key");
        if (key == null) {
            key = "testKey";
        }
        String value = req.getParameter("value");
        
        HttpSession session = req.getSession(true);
        
        out.println("=== Session Info ===");
        out.println("Session ID: " + session.getId());
        out.println("Is New: " + session.isNew());
        out.println("Creation Time: " + session.getCreationTime());
        
        if ("set".equals(action) && value != null) {
            session.setAttribute(key, value);
            out.println("Set " + key + " = " + value);
        } else if ("get".equals(action)) {
            Object stored = session.getAttribute(key);
            out.println("Get " + key + " = " + stored);
            if (stored != null) {
                out.println(stored.toString());
            }
        } else if ("invalidate".equals(action)) {
            session.invalidate();
            out.println("Session invalidated");
        } else {
            // Default: list all attributes
            out.println();
            out.println("=== Session Attributes ===");
            Enumeration<String> names = session.getAttributeNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                out.println(name + " = " + session.getAttribute(name));
            }
        }
    }

    /**
     * Multipart file upload handling.
     */
    private void handleUpload(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Upload Info ===");
        out.println("Content-Type: " + req.getContentType());
        
        try {
            Collection<Part> parts = req.getParts();
            out.println("Number of parts: " + parts.size());
            out.println();
            
            for (Part part : parts) {
                out.println("=== Part: " + part.getName() + " ===");
                out.println("Content-Type: " + part.getContentType());
                out.println("Size: " + part.getSize());
                out.println("Submitted Filename: " + part.getSubmittedFileName());
                
                // Print headers
                for (String headerName : part.getHeaderNames()) {
                    out.println("Header " + headerName + ": " + part.getHeader(headerName));
                }
                
                // Read content for small parts
                if (part.getSize() < 1024) {
                    InputStream is = part.getInputStream();
                    byte[] content = new byte[(int) part.getSize()];
                    is.read(content);
                    is.close();
                    out.println("Content: " + new String(content, "UTF-8"));
                }
                out.println();
            }
            
            out.println("Upload processed successfully");
            
        } catch (Exception e) {
            out.println("Error processing upload: " + e.getMessage());
            e.printStackTrace(out);
        }
    }

    /**
     * Error generation for testing error handling.
     */
    private void handleError(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String type = req.getParameter("type");
        String statusStr = req.getParameter("status");
        
        if ("exception".equals(type)) {
            throw new ServletException("Test exception thrown by request");
        } else if ("runtime".equals(type)) {
            throw new RuntimeException("Test runtime exception");
        } else if ("io".equals(type)) {
            throw new IOException("Test IO exception");
        } else if (statusStr != null) {
            try {
                int status = Integer.parseInt(statusStr);
                resp.sendError(status, "Error " + status + " requested");
            } catch (NumberFormatException e) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid status code");
            }
        } else {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Generic error");
        }
    }

    /**
     * Request forward to another resource.
     */
    private void handleForward(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String target = req.getParameter("target");
        if (target == null) {
            target = "/test/hello";
        }
        
        RequestDispatcher dispatcher = req.getRequestDispatcher(target);
        if (dispatcher != null) {
            dispatcher.forward(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Cannot forward to: " + target);
        }
    }

    /**
     * Request include of another resource.
     */
    private void handleInclude(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String target = req.getParameter("target");
        if (target == null) {
            target = "/test/hello";
        }
        
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Before Include ===");
        
        RequestDispatcher dispatcher = req.getRequestDispatcher(target);
        if (dispatcher != null) {
            dispatcher.include(req, resp);
        } else {
            out.println("Cannot include: " + target);
        }
        
        out.println("=== After Include ===");
    }

    /**
     * Async request processing.
     */
    private void handleAsync(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        
        String delayStr = req.getParameter("delay");
        final int delay = (delayStr != null) ? Integer.parseInt(delayStr) : 100;
        
        final AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(5000);
        
        asyncContext.start(new Runnable() {
            @Override
            public void run() {
                try {
                    // Simulate async work
                    Thread.sleep(delay);
                    
                    HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
                    asyncResp.setContentType("text/plain");
                    asyncResp.setCharacterEncoding("UTF-8");
                    PrintWriter out = asyncResp.getWriter();
                    out.println("=== Async Response ===");
                    out.println("Processed asynchronously after " + delay + "ms");
                    out.println("Thread: " + Thread.currentThread().getName());
                    out.println("Time: " + System.currentTimeMillis());
                    
                    asyncContext.complete();
                } catch (Exception e) {
                    try {
                        HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
                        asyncResp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                    } catch (IOException ex) {
                        // Ignore
                    }
                    asyncContext.complete();
                }
            }
        });
    }

    /**
     * Servlet context attribute testing.
     */
    private void handleContext(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        String action = req.getParameter("action");
        String key = req.getParameter("key");
        String value = req.getParameter("value");
        
        out.println("=== Servlet Context Info ===");
        out.println("Context Path: " + getServletContext().getContextPath());
        out.println("Server Info: " + getServletContext().getServerInfo());
        out.println("Servlet API: " + getServletContext().getMajorVersion() + "." + 
                    getServletContext().getMinorVersion());
        
        if ("set".equals(action) && key != null && value != null) {
            getServletContext().setAttribute(key, value);
            out.println("Set attribute: " + key + " = " + value);
        } else if ("get".equals(action) && key != null) {
            Object attrValue = getServletContext().getAttribute(key);
            out.println("Get attribute: " + key + " = " + attrValue);
            if (attrValue != null) {
                out.println(attrValue.toString());
            }
        } else {
            out.println();
            out.println("=== Context Attributes ===");
            Enumeration<String> names = getServletContext().getAttributeNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                out.println(name + " = " + getServletContext().getAttribute(name));
            }
        }
    }

    /**
     * JSON content type response.
     */
    private void handleJson(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("{");
        out.println("  \"status\": \"success\",");
        out.println("  \"message\": \"JSON response from TestServlet\",");
        out.println("  \"method\": \"" + req.getMethod() + "\",");
        out.println("  \"timestamp\": " + System.currentTimeMillis());
        out.println("}");
    }

    /**
     * XML content type response.
     */
    private void handleXml(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/xml");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<response>");
        out.println("  <status>success</status>");
        out.println("  <message>XML response from TestServlet</message>");
        out.println("  <method>" + req.getMethod() + "</method>");
        out.println("  <timestamp>" + System.currentTimeMillis() + "</timestamp>");
        out.println("</response>");
    }

    /**
     * Handler for filtered path (filter adds header).
     */
    private void handleFiltered(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Filtered Response ===");
        out.println("This response passed through TestFilter");
        out.println("Check X-Filter-Applied response header");
        
        // Check if filter added a request attribute
        Object filterAttr = req.getAttribute("filterApplied");
        if (filterAttr != null) {
            out.println("Filter attribute found: " + filterAttr);
        }
    }

    /**
     * Query and form parameter handling.
     */
    private void handleParams(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Request Parameters ===");
        out.println("Query String: " + req.getQueryString());
        out.println("Content-Type: " + req.getContentType());
        out.println();
        
        Enumeration<String> paramNames = req.getParameterNames();
        while (paramNames.hasMoreElements()) {
            String name = paramNames.nextElement();
            String[] values = req.getParameterValues(name);
            for (String value : values) {
                out.println(name + " = " + value);
            }
        }
    }

    /**
     * Request information display.
     */
    private void handleInfo(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        
        out.println("=== Request Info ===");
        out.println("Method: " + req.getMethod());
        out.println("Request URI: " + req.getRequestURI());
        out.println("Request URL: " + req.getRequestURL());
        out.println("Context Path: " + req.getContextPath());
        out.println("Servlet Path: " + req.getServletPath());
        out.println("Path Info: " + req.getPathInfo());
        out.println("Query String: " + req.getQueryString());
        out.println();
        out.println("Protocol: " + req.getProtocol());
        out.println("Scheme: " + req.getScheme());
        out.println("Server Name: " + req.getServerName());
        out.println("Server Port: " + req.getServerPort());
        out.println();
        out.println("Remote Addr: " + req.getRemoteAddr());
        out.println("Remote Host: " + req.getRemoteHost());
        out.println("Remote Port: " + req.getRemotePort());
        out.println();
        out.println("Local Addr: " + req.getLocalAddr());
        out.println("Local Name: " + req.getLocalName());
        out.println("Local Port: " + req.getLocalPort());
        out.println();
        out.println("Character Encoding: " + req.getCharacterEncoding());
        out.println("Content Length: " + req.getContentLength());
        out.println("Content Type: " + req.getContentType());
        out.println("Locale: " + req.getLocale());
        out.println("Is Secure: " + req.isSecure());
    }
}
