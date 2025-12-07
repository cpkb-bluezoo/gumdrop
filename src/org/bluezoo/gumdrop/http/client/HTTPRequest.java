/*
 * HTTPRequest.java
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

package org.bluezoo.gumdrop.http.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an HTTP request to be sent by an HTTP client.
 * 
 * <p>This immutable class encapsulates all the information needed to send
 * an HTTP request, including the method, URI, headers, and protocol version.
 * It provides a clean abstraction that works across HTTP versions.
 * 
 * <p>Instances are created using constructors:
 * <pre>
 * HTTPRequest request = new HTTPRequest("GET", "/api/users");
 * 
 * // With headers
 * Map&lt;String, String&gt; headers = new HashMap&lt;&gt;();
 * headers.put("Accept", "application/json");
 * headers.put("User-Agent", "Gumdrop-Client/1.0");
 * HTTPRequest request = new HTTPRequest("POST", "/api/users", headers);
 * </pre>
 * 
 * <p><strong>Thread Safety:</strong> This class is immutable and thread-safe.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientStream#sendRequest(HTTPRequest)
 */
public final class HTTPRequest {

    private final String method;
    private final String uri;
    private final String version;
    private final Map<String, String> headers;

    /**
     * Creates an HTTP request with the specified method and URI.
     * Uses HTTP/1.1 as the default version with no headers.
     * 
     * @param method the HTTP method (GET, POST, etc.)
     * @param uri the request URI (path and query string)
     */
    public HTTPRequest(String method, String uri) {
        this(method, uri, "HTTP/1.1", Collections.<String, String>emptyMap());
    }

    /**
     * Creates an HTTP request with the specified method, URI, and headers.
     * Uses HTTP/1.1 as the default version.
     * 
     * @param method the HTTP method (GET, POST, etc.)
     * @param uri the request URI (path and query string)
     * @param headers the request headers (case-insensitive map)
     */
    public HTTPRequest(String method, String uri, Map<String, String> headers) {
        this(method, uri, "HTTP/1.1", headers);
    }

    /**
     * Creates an HTTP request with the specified parameters.
     * 
     * @param method the HTTP method (GET, POST, etc.)
     * @param uri the request URI (path and query string)
     * @param version the HTTP version ("HTTP/1.1" or "HTTP/2.0")
     * @param headers the request headers (case-insensitive map)
     */
    public HTTPRequest(String method, String uri, String version, Map<String, String> headers) {
        this.method = method;
        this.uri = uri;
        this.version = version;
        
        // Normalize header names to lowercase for consistent lookup
        Map<String, String> normalizedHeaders = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                normalizedHeaders.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(normalizedHeaders);
    }

    /**
     * Returns the HTTP method for this request.
     * 
     * @return the HTTP method (e.g., "GET", "POST", "PUT")
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the request URI.
     * 
     * <p>This includes the path and query string, but not the scheme or host
     * (which are determined by the connection target).
     * 
     * @return the request URI (e.g., "/api/users?limit=10")
     */
    public String getUri() {
        return uri;
    }

    /**
     * Returns the HTTP version for this request.
     * 
     * @return the HTTP version (e.g., "HTTP/1.1" or "HTTP/2.0")
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns all headers for this request.
     * 
     * <p>The returned map is immutable and case-insensitive for header names.
     * 
     * @return an immutable map of header names to values
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns the value of a specific header.
     * 
     * <p>Header name matching is case-insensitive.
     * 
     * @param name the header name
     * @return the header value, or null if not present
     */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    /**
     * Checks if this request has a specific header.
     * 
     * <p>Header name matching is case-insensitive.
     * 
     * @param name the header name
     * @return true if the header is present, false otherwise
     */
    public boolean hasHeader(String name) {
        return headers.containsKey(name.toLowerCase());
    }

    /**
     * Checks if this request uses chunked transfer encoding.
     *
     * @return true if Transfer-Encoding is chunked, false otherwise
     */
    public boolean isChunked() {
        String transferEncoding = getHeader("transfer-encoding");
        return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
    }

    /**
     * Returns true if this request method typically has a request body.
     *
     * @return true for POST, PUT, PATCH, false for GET, HEAD, DELETE, etc.
     */
    public boolean expectsRequestBody() {
        String upperMethod = method.toUpperCase();
        return "POST".equals(upperMethod) || 
               "PUT".equals(upperMethod) || 
               "PATCH".equals(upperMethod);
    }

    @Override
    public String toString() {
        return method + " " + uri + " " + version;
    }
}
