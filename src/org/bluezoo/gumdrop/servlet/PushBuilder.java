/*
 * PushBuilder.java
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

import java.util.*;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.PushBuilder;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;

/**
 * Implementation of Servlet 4.0 PushBuilder for HTTP/2 server push functionality.
 * 
 * <p>This implementation creates HTTP/2 PUSH_PROMISE frames to instruct compliant
 * clients to expect pushed resources. Server push allows the server to proactively
 * send resources that the client will likely request, reducing round-trip latency.
 *
 * <p>Server push is only supported on HTTP/2 connections. Attempting to use push
 * on HTTP/1.x connections will result in the push being silently ignored.
 *
 * <p>Usage example:
 * <pre>{@code
 * PushBuilder pushBuilder = request.newPushBuilder();
 * if (pushBuilder != null) {
 *     pushBuilder.path("/css/styles.css")
 *               .addHeader("Cache-Control", "max-age=3600")
 *               .push();
 *     
 *     pushBuilder.path("/js/app.js")
 *               .push();
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @since Servlet 4.0
 */
class ServletPushBuilder implements PushBuilder {
    
    private static final Logger LOGGER = Logger.getLogger(ServletPushBuilder.class.getName());
    
    // Required fields for push promise
    private final ServletStream stream;
    private final String scheme;
    private final String authority; // Host header from original request
    private final Map<String, List<String>> headers;
    
    // Current push request configuration
    private String method = "GET"; // Default method for pushed requests
    private String path;
    private String queryString;
    private Set<String> conditionalHeaders;
    private String sessionId;
    
    // HTTP/2 specific configuration
    private boolean valid = true; // Can be invalidated if push not supported
    
    /**
     * Constructs a new PushBuilder for the specified stream.
     * 
     * @param stream the current servlet stream (must support HTTP/2)
     * @param request the original request to inherit headers from
     */
    ServletPushBuilder(ServletStream stream, HttpServletRequest request) {
        this.stream = stream;
        this.scheme = request.getScheme();
        this.authority = request.getHeader("Host");
        
        // Initialize headers map with original request headers
        this.headers = new LinkedHashMap<>();
        
        // Copy specific headers from the original request
        // According to RFC 7540, certain headers should be inherited
        inheritHeaders(request);
        
        // Initialize conditional headers that should be removed for push
        initializeConditionalHeaders();
        
        // Check if push is actually supported on this connection
        validatePushSupport();
    }
    
    /**
     * Copy appropriate headers from the original request to the push request.
     * Some headers like authorization, cookie, and conditional headers are inherited.
     */
    private void inheritHeaders(HttpServletRequest request) {
        // Headers that should be inherited for server push
        String[] inheritableHeaders = {
            "Accept", "Accept-Charset", "Accept-Encoding", "Accept-Language",
            "Authorization", "Cache-Control", "Cookie", "User-Agent"
        };
        
        for (String headerName : inheritableHeaders) {
            String headerValue = request.getHeader(headerName);
            if (headerValue != null) {
                addHeader(headerName, headerValue);
            }
        }
        
        // Handle session cookie if present
        if (request.getSession(false) != null) {
            this.sessionId = request.getSession().getId();
        }
    }
    
    /**
     * Initialize the set of conditional headers that should be removed from push requests.
     */
    private void initializeConditionalHeaders() {
        this.conditionalHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        conditionalHeaders.add("If-Match");
        conditionalHeaders.add("If-None-Match");
        conditionalHeaders.add("If-Modified-Since");
        conditionalHeaders.add("If-Unmodified-Since");
        conditionalHeaders.add("If-Range");
        conditionalHeaders.add("Range");
    }
    
    /**
     * Validate that server push is supported on the current connection.
     */
    private void validatePushSupport() {
        // Check if the current stream supports HTTP/2
        if (stream == null || !stream.supportsServerPush()) {
            LOGGER.fine("Server push not supported on this connection - HTTP/2 required");
            this.valid = false;
        }
    }
    
    @Override
    public PushBuilder method(String method) {
        if (!valid) {
            return this;
        }
        
        if (method == null) {
            throw new NullPointerException("Method cannot be null");
        }
        
        // Validate that the method is cacheable (RFC 7540 Section 8.2)
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            throw new IllegalArgumentException("Only GET and HEAD methods are allowed for server push");
        }
        
        this.method = method.toUpperCase();
        return this;
    }
    
    @Override
    public PushBuilder queryString(String queryString) {
        if (!valid) {
            return this;
        }
        
        this.queryString = queryString;
        return this;
    }
    
    @Override
    public PushBuilder sessionId(String sessionId) {
        if (!valid) {
            return this;
        }
        
        this.sessionId = sessionId;
        return this;
    }
    
    @Override
    public PushBuilder setHeader(String name, String value) {
        if (!valid) {
            return this;
        }
        
        if (name == null) {
            throw new NullPointerException("Header name cannot be null");
        }
        
        // Validate header name (RFC 7540 Section 8.1.2)
        validateHeaderName(name);
        
        if (value == null) {
            removeHeader(name);
        } else {
            headers.put(name.toLowerCase(), Arrays.asList(value));
        }
        
        return this;
    }
    
    @Override
    public PushBuilder addHeader(String name, String value) {
        if (!valid) {
            return this;
        }
        
        if (name == null) {
            throw new NullPointerException("Header name cannot be null");
        }
        if (value == null) {
            throw new NullPointerException("Header value cannot be null");
        }
        
        validateHeaderName(name);
        
        String lowerName = name.toLowerCase();
        List<String> values = headers.get(lowerName);
        if (values == null) {
            values = new ArrayList<>();
            headers.put(lowerName, values);
        }
        values.add(value);
        
        return this;
    }
    
    @Override
    public PushBuilder removeHeader(String name) {
        if (!valid) {
            return this;
        }
        
        if (name != null) {
            headers.remove(name.toLowerCase());
        }
        
        return this;
    }
    
    @Override
    public PushBuilder path(String path) {
        if (!valid) {
            return this;
        }
        
        if (path == null) {
            throw new NullPointerException("Path cannot be null");
        }
        
        // Validate path format
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with '/'");
        }
        
        this.path = path;
        return this;
    }
    
    @Override
    public void push() {
        if (!valid) {
            LOGGER.fine("Push ignored - not supported on this connection");
            return;
        }
        
        if (path == null) {
            throw new IllegalStateException("Path must be set before calling push()");
        }
        
        try {
            // Remove conditional headers before pushing
            removeConditionalHeaders();
            
            // Add session cookie if session ID is set
            addSessionCookie();
            
            // Create the push request
            executePush();
            
            // Reset path for potential reuse of this PushBuilder
            this.path = null;
            this.queryString = null;
            
        } catch (Exception e) {
            LOGGER.warning("Failed to execute server push: " + e.getMessage());
            // Don't throw - server push failures should not break the main response
        }
    }
    
    @Override
    public String getMethod() {
        return method;
    }
    
    @Override
    public String getQueryString() {
        return queryString;
    }
    
    @Override
    public String getSessionId() {
        return sessionId;
    }
    
    @Override
    public Set<String> getHeaderNames() {
        return new TreeSet<>(headers.keySet());
    }
    
    @Override
    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        
        List<String> values = headers.get(name.toLowerCase());
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }
    
    @Override
    public String getPath() {
        return path;
    }
    
    /**
     * Validates HTTP/2 header name constraints (RFC 7540 Section 8.1.2).
     */
    private void validateHeaderName(String name) {
        // HTTP/2 header names must be lowercase (enforced by HPACK)
        // Connection-specific headers are forbidden
        String lowerName = name.toLowerCase();
        
        if (lowerName.startsWith(":")) {
            throw new IllegalArgumentException("Pseudo-header fields cannot be set directly: " + name);
        }
        
        if ("connection".equals(lowerName) || "upgrade".equals(lowerName) || 
            "http2-settings".equals(lowerName) || "te".equals(lowerName)) {
            throw new IllegalArgumentException("Connection-specific header not allowed: " + name);
        }
    }
    
    /**
     * Remove conditional headers that should not be present in push requests.
     */
    private void removeConditionalHeaders() {
        for (String headerName : conditionalHeaders) {
            headers.remove(headerName.toLowerCase());
        }
    }
    
    /**
     * Add session cookie if session ID is configured.
     */
    private void addSessionCookie() {
        if (sessionId != null) {
            String cookieValue = "JSESSIONID=" + sessionId;
            
            // Check if Cookie header already exists
            List<String> existingCookies = headers.get("cookie");
            if (existingCookies != null && !existingCookies.isEmpty()) {
                // Append to existing cookies
                String combined = existingCookies.get(0) + "; " + cookieValue;
                headers.put("cookie", Arrays.asList(combined));
            } else {
                // Set new cookie
                headers.put("cookie", Arrays.asList(cookieValue));
            }
        }
    }
    
    /**
     * Execute the actual server push by creating PUSH_PROMISE frame and promised stream.
     */
    private void executePush() {
        // Build the complete URI for the push request
        String pushUri = buildPushUri();
        
        // Convert headers to HTTP format
        Headers pushHeaders = buildPushHeaders(pushUri);
        
        // Execute the push via the stream
        boolean success = stream.executePush(method, pushUri, pushHeaders);
        
        if (success) {
            LOGGER.fine("Server push executed successfully for: " + pushUri);
        } else {
            LOGGER.warning("Server push failed for: " + pushUri);
        }
    }
    
    /**
     * Build the complete URI for the push request.
     */
    private String buildPushUri() {
        StringBuilder uri = new StringBuilder(path);
        
        if (queryString != null && !queryString.isEmpty()) {
            uri.append('?').append(queryString);
        }
        
        return uri.toString();
    }
    
    /**
     * Build the HTTP headers for the push request.
     */
    private Headers buildPushHeaders(String uri) {
        Headers pushHeaders = new Headers();
        
        // Add HTTP/2 pseudo-headers (required for PUSH_PROMISE)
        pushHeaders.add(":method", method);
        pushHeaders.add(":scheme", scheme);
        pushHeaders.add(":authority", authority);
        pushHeaders.add(":path", uri);
        
        // Add regular headers
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            for (String headerValue : entry.getValue()) {
                pushHeaders.add(headerName, headerValue);
            }
        }
        
        return pushHeaders;
    }
}
