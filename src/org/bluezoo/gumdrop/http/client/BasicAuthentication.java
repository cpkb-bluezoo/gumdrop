/*
 * BasicAuthentication.java
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP Basic Authentication implementation (RFC 7617).
 *
 * <p>Basic authentication transmits credentials as base64-encoded username:password
 * pairs in the Authorization header. While simple to implement, it provides no
 * protection for credentials beyond transport-level security (HTTPS).
 *
 * <p><strong>Security Note:</strong> Basic authentication should only be used
 * over HTTPS connections, as credentials are easily decoded from base64.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>
 * // Simple username/password authentication
 * HTTPAuthentication auth = new BasicAuthentication("user", "password");
 * client.setAuthentication(auth);
 *
 * // Per-request authentication
 * Map&lt;String, String&gt; headers = new HashMap&lt;&gt;();
 * auth.applyAuthentication(headers);
 * HTTPRequest request = new HTTPRequest("GET", "/protected", headers);
 * </pre>
 *
 * <p><strong>Wire Format:</strong>
 * <pre>Authorization: Basic dXNlcjpwYXNzd29yZA==</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc7617">RFC 7617 - HTTP Basic Authentication</a>
 */
public class BasicAuthentication implements HTTPAuthentication {

    private static final String SCHEME = "Basic";
    
    private final String username;
    private final String password;
    private final String encodedCredentials;

    /**
     * Creates a Basic authentication instance with the specified credentials.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @throws IllegalArgumentException if username or password is null
     */
    public BasicAuthentication(String username, String password) {
        if (username == null) {
            throw new IllegalArgumentException("Username cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        
        this.username = username;
        this.password = password;
        
        // Pre-encode credentials for efficiency
        String credentials = username + ":" + password;
        this.encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public void applyAuthentication(Map<String, String> headers) {
        headers.put("authorization", SCHEME + " " + encodedCredentials);
    }

    @Override
    public boolean handleChallenge(HTTPResponse response) {
        // Basic authentication doesn't require challenge handling
        // If we get a 401, it typically means credentials are wrong
        return false; // Don't retry - credentials are likely invalid
    }

    @Override
    public HTTPAuthentication clone() {
        return new BasicAuthentication(username, password);
    }

    @Override
    public boolean requiresChallenge() {
        return false; // Basic auth can be applied proactively
    }

    @Override
    public boolean isReady() {
        return true; // Always ready if we have credentials
    }

    @Override
    public String getDescription() {
        return "Basic authentication for user: " + username;
    }

    /**
     * Returns the username used for authentication.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Creates a Basic authentication instance from an encoded credential string.
     *
     * <p>This method is useful for parsing existing Authorization headers
     * or loading credentials from configuration.
     *
     * @param encodedCredentials the base64-encoded "username:password" string
     * @return a new BasicAuthentication instance
     * @throws IllegalArgumentException if the encoded string is invalid
     */
    public static BasicAuthentication fromEncodedCredentials(String encodedCredentials) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8);
            int colonIndex = decoded.indexOf(':');
            if (colonIndex <= 0) {
                throw new IllegalArgumentException("Invalid credential format: missing colon separator");
            }
            
            String username = decoded.substring(0, colonIndex);
            String password = decoded.substring(colonIndex + 1);
            
            return new BasicAuthentication(username, password);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base64 encoded credentials", e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) return false;
        
        BasicAuthentication that = (BasicAuthentication) obj;
        return username.equals(that.username) && password.equals(that.password);
    }

    @Override
    public int hashCode() {
        return username.hashCode() * 31 + password.hashCode();
    }

    @Override
    public String toString() {
        return getDescription();
    }
}
