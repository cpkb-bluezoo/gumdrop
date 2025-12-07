/*
 * HTTPResponse.java
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

import org.bluezoo.gumdrop.http.HTTPConstants;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an HTTP response received by an HTTP client.
 * 
 * <p>This immutable class encapsulates all the information in an HTTP response,
 * including the status code, status message, headers, and protocol version.
 * It provides convenient methods for checking response types and accessing headers.
 * 
 * <p>Instances are typically created by the HTTP client framework when parsing
 * responses from the server, but can also be constructed manually for testing:
 * <pre>
 * HTTPResponse response = new HTTPResponse(200, "OK");
 * 
 * // With headers
 * Map&lt;String, String&gt; headers = new HashMap&lt;&gt;();
 * headers.put("Content-Type", "application/json");
 * headers.put("Content-Length", "123");
 * HTTPResponse response = new HTTPResponse(200, "OK", headers);
 * </pre>
 * 
 * <p><strong>Thread Safety:</strong> This class is immutable and thread-safe.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler#onStreamResponse(HTTPClientStream, HTTPResponse)
 */
public final class HTTPResponse {

    private final int statusCode;
    private final String statusMessage;
    private final String version;
    private final Map<String, String> headers;

    /**
     * Creates an HTTP response with the specified status code.
     * Uses the standard status message for the code and HTTP/1.1 version with no headers.
     * 
     * @param statusCode the HTTP status code (e.g., 200, 404)
     */
    public HTTPResponse(int statusCode) {
        this(statusCode, HTTPConstants.getMessage(statusCode), "HTTP/1.1", Collections.<String, String>emptyMap());
    }

    /**
     * Creates an HTTP response with the specified status code and message.
     * Uses HTTP/1.1 version with no headers.
     * 
     * @param statusCode the HTTP status code (e.g., 200, 404)
     * @param statusMessage the status message (e.g., "OK", "Not Found")
     */
    public HTTPResponse(int statusCode, String statusMessage) {
        this(statusCode, statusMessage, "HTTP/1.1", Collections.<String, String>emptyMap());
    }

    /**
     * Creates an HTTP response with the specified status code and headers.
     * Uses the standard status message for the code and HTTP/1.1 version.
     * 
     * @param statusCode the HTTP status code (e.g., 200, 404)
     * @param headers the response headers (case-insensitive map)
     */
    public HTTPResponse(int statusCode, Map<String, String> headers) {
        this(statusCode, HTTPConstants.getMessage(statusCode), "HTTP/1.1", headers);
    }

    /**
     * Creates an HTTP response with the specified status code, message, and headers.
     * Uses HTTP/1.1 version.
     * 
     * @param statusCode the HTTP status code (e.g., 200, 404)
     * @param statusMessage the status message (e.g., "OK", "Not Found")
     * @param headers the response headers (case-insensitive map)
     */
    public HTTPResponse(int statusCode, String statusMessage, Map<String, String> headers) {
        this(statusCode, statusMessage, "HTTP/1.1", headers);
    }

    /**
     * Creates an HTTP response with the specified parameters.
     * 
     * @param statusCode the HTTP status code (e.g., 200, 404)
     * @param statusMessage the status message (e.g., "OK", "Not Found")
     * @param version the HTTP version ("HTTP/1.1" or "HTTP/2.0")
     * @param headers the response headers (case-insensitive map)
     */
    public HTTPResponse(int statusCode, String statusMessage, String version, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
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
     * Returns the HTTP status code for this response.
     * 
     * @return the status code (e.g., 200, 404, 500)
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the HTTP status message for this response.
     * 
     * @return the status message (e.g., "OK", "Not Found", "Internal Server Error")
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Returns the HTTP version for this response.
     * 
     * @return the HTTP version (e.g., "HTTP/1.1" or "HTTP/2.0")
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns all headers for this response.
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
     * Checks if this response has a specific header.
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
     * Checks if this response indicates success (2xx status codes).
     * 
     * @return true if status code is 200-299, false otherwise
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Checks if this response is a redirection (3xx status codes).
     * 
     * @return true if status code is 300-399, false otherwise
     */
    public boolean isRedirection() {
        return statusCode >= 300 && statusCode < 400;
    }

    /**
     * Checks if this response indicates a client error (4xx status codes).
     * 
     * @return true if status code is 400-499, false otherwise
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Checks if this response indicates a server error (5xx status codes).
     * 
     * @return true if status code is 500-599, false otherwise
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Checks if this response indicates an error (4xx or 5xx status codes).
     * 
     * @return true if status code is 400-599, false otherwise
     */
    public boolean isError() {
        return statusCode >= 400;
    }

    /**
     * Returns the Content-Length header value as a long.
     * 
     * @return the content length, or -1 if header not present or invalid
     */
    public long getContentLength() {
        String contentLength = getHeader("content-length");
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                // Invalid content-length header
            }
        }
        return -1;
    }

    /**
     * Returns the Content-Type header value.
     * 
     * @return the content type, or null if not present
     */
    public String getContentType() {
        return getHeader("content-type");
    }

    @Override
    public String toString() {
        return version + " " + statusCode + " " + statusMessage;
    }
}
