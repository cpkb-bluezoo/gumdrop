/*
 * DefaultServlet.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Default servlet for a Web application. This serves resources in the
 * servlet context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultServlet extends HttpServlet {

    public String getServletName() {
        return "DefaultServlet";
    }

    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = getPath(request);
        // Ensure that collections terminate in "/"
        if (!path.endsWith("/") && isCollection(path)) {
            String url = request.getRequestURL() + "/";
            String queryString = request.getQueryString();
            if (queryString != null) {
                url += "?" + queryString;
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", url);
            response.setHeader("path", path);
            return;
        }
        super.service(request, response);
    }

    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = getPath(request);
        if ("*".equals(path)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setHeader("Allow", "OPTIONS, GET, HEAD");
            return;
        }
        URL url = getServletContext().getResource(path);
        if (url == null || isWebInf(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } else {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setHeader("Allow", "OPTIONS, GET, HEAD");
        }
    }

    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = getPath(request);
        ServletContext context = getServletContext();
        URL resource = getResource(request, path, context);
        if (resource == null || isWebInf(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } else {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            // Copy resource headers into response
            URLConnection connection = resource.openConnection();
            connection.connect();
            String contentType = connection.getContentType();
            int contentLength = connection.getContentLength();
            if (contentType != null) {
                response.setContentType(contentType);
            }
            if (contentLength != -1) {
                response.setContentLength(contentLength);
            }
            long lastModified = connection.getDate();
            if (lastModified != -1L) {
                response.setDateHeader("Last-Modified", lastModified);
            }
            Map<String,List<String>> headers = connection.getHeaderFields();
            for (Map.Entry<String,List<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if (name != null) {
                    for (String value : values) {
                        response.addHeader(name, value);
                    }
                }
            }
        }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = getPath(request);
        ServletContext context = getServletContext();
        URL resource = getResource(request, path, context);
        if (resource == null || isWebInf(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, path);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            // Copy resource headers into response
            URLConnection connection = resource.openConnection();
            connection.connect();
            String contentType = connection.getContentType();
            if (contentType != null) {
                response.setContentType(contentType);
            }
            int contentLength = connection.getContentLength();
            if (contentLength != -1) {
                response.setContentLength(contentLength);
            }
            long lastModified = connection.getDate();
            if (lastModified != -1L) {
                response.setDateHeader("Last-Modified", lastModified);
            }
            Map<String,List<String>> headers = connection.getHeaderFields();
            for (Map.Entry<String,List<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if (name != null) {
                    for (String value : values) {
                        response.addHeader(name, value);
                    }
                }
            }
            // Stream content
            InputStream in = connection.getInputStream();
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[Math.max(4096, in.available())];
            for (int len = in.read(buf); len != -1; len = in.read(buf)) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    /**
     * Returns the last-modified time for the given resource.
     *
    protected long getLastModified(HttpServletRequest request) {
        String path = getPath(request);
        ServletContext context = getServletContext();
        try {
            URL resource = getResource(request, path, context);
            if (resource != null) {
                URLConnection connection = resource.openConnection();
                connection.connect();
                return connection.getLastModified();
            }
        } catch (IOException e) {
            // Fall through
        }
        return -1L;
    }*/

    /**
     * Returns the resource URL for the given request.
     */
    protected URL getResource(HttpServletRequest request, String path, ServletContext context) throws IOException {
        URL resource = (URL) request.getAttribute("DefaultServlet.resource");
        if (resource == null) {
            resource = context.getResource(path);
            request.setAttribute("DefaultServlet.resource", resource);
        }
        return resource;
    }

    /**
     * Returns the complete path of the request relative to the web
     * application root, not including any query portion.
     */
    protected final String getPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            return servletPath;
        }
        if (servletPath.endsWith("/") && pathInfo.startsWith("/")) {
            pathInfo = pathInfo.substring(1);
        }
        return servletPath + pathInfo;
    }

    /**
     * Determines if the specified path refers to a static collection in the
     * web application.
     */
    protected final boolean isCollection(String path) {
        ServletContext ctx = getServletContext();
        Set<String> resourcePaths = ctx.getResourcePaths(path);
        return (resourcePaths != null && !resourcePaths.isEmpty());
    }

    /**
     * Indicates whether the specified path refers to a file in the WEB-INF
     * or META-INF resource hierarchies, which must result in a 404 (SRV.9.5,
     * SRV.9.6).
     */
    protected final boolean isWebInf(String path) {
        if (path != null && path.length() >= 8) {
            String prefix = path.substring(0, 8);
            if ("/WEB-INF".equalsIgnoreCase(prefix)) {
                return true;
            }
            if (path.length() >= 9 && path.startsWith("/META-INF")) {
                return true;
            }
        }
        return false;
    }

}
