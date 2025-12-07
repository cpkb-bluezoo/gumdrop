/*
 * BearerAuthentication.java
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

import java.util.Map;

/**
 * HTTP Bearer Token Authentication implementation (RFC 6750).
 *
 * <p>Bearer authentication uses access tokens (typically OAuth 2.0 access tokens
 * or API keys) transmitted in the Authorization header. This is the standard
 * method for API authentication in modern web services.
 *
 * <p><strong>Security Note:</strong> Bearer tokens should be treated as passwords
 * and only transmitted over HTTPS connections. Tokens may have expiration times
 * and require refresh mechanisms.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>
 * // Simple API token authentication
 * HTTPAuthentication auth = new BearerAuthentication("eyJhbGciOiJIUzI1NiJ9...");
 * client.setAuthentication(auth);
 *
 * // With token type specification (optional)
 * HTTPAuthentication auth = new BearerAuthentication("access-token", "JWT");
 *
 * // OAuth 2.0 access token
 * String oauthToken = obtainOAuthToken();
 * HTTPAuthentication auth = new BearerAuthentication(oauthToken);
 * </pre>
 *
 * <p><strong>Wire Format:</strong>
 * <pre>Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6750">RFC 6750 - OAuth 2.0 Bearer Token Usage</a>
 */
public class BearerAuthentication implements HTTPAuthentication {

    private static final String SCHEME = "Bearer";
    
    private final String token;
    private final String tokenType;
    private volatile long expirationTime; // 0 = no expiration, > 0 = expiration timestamp
    
    /**
     * Creates a Bearer authentication instance with the specified token.
     *
     * @param token the bearer token (access token, API key, JWT, etc.)
     * @throws IllegalArgumentException if token is null or empty
     */
    public BearerAuthentication(String token) {
        this(token, null, 0);
    }
    
    /**
     * Creates a Bearer authentication instance with token and type.
     *
     * @param token the bearer token
     * @param tokenType optional token type (e.g., "JWT", "MAC") for documentation
     * @throws IllegalArgumentException if token is null or empty
     */
    public BearerAuthentication(String token, String tokenType) {
        this(token, tokenType, 0);
    }
    
    /**
     * Creates a Bearer authentication instance with token and expiration.
     *
     * @param token the bearer token
     * @param tokenType optional token type for documentation
     * @param expirationTime token expiration time in milliseconds since epoch, or 0 for no expiration
     * @throws IllegalArgumentException if token is null or empty
     */
    public BearerAuthentication(String token, String tokenType, long expirationTime) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Bearer token cannot be null or empty");
        }
        
        this.token = token.trim();
        this.tokenType = tokenType;
        this.expirationTime = expirationTime;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public void applyAuthentication(Map<String, String> headers) {
        if (isExpired()) {
            throw new IllegalStateException("Bearer token has expired");
        }
        
        headers.put("authorization", SCHEME + " " + token);
    }

    @Override
    public boolean handleChallenge(HTTPResponse response) {
        // Check if this is a token expiration or invalid token response
        if (response.getStatusCode() == 401) {
            String wwwAuth = response.getHeader("www-authenticate");
            if (wwwAuth != null && wwwAuth.toLowerCase().contains("bearer")) {
                // Parse Bearer challenge for error details
                if (wwwAuth.contains("invalid_token") || wwwAuth.contains("expired")) {
                    // Token is invalid or expired - mark for refresh
                    this.expirationTime = System.currentTimeMillis() - 1; // Mark as expired
                    return false; // Don't retry automatically - need new token
                }
            }
        }
        
        return false; // Bearer auth generally doesn't support automatic retry
    }

    @Override
    public HTTPAuthentication clone() {
        return new BearerAuthentication(token, tokenType, expirationTime);
    }

    @Override
    public boolean requiresChallenge() {
        return false; // Bearer auth can be applied proactively
    }

    @Override
    public boolean isReady() {
        return !isExpired();
    }

    @Override
    public String getDescription() {
        StringBuilder desc = new StringBuilder("Bearer token authentication");
        if (tokenType != null) {
            desc.append(" (").append(tokenType).append(")");
        }
        if (expirationTime > 0) {
            desc.append(" [expires: ").append(new java.util.Date(expirationTime)).append("]");
        }
        return desc.toString();
    }

    /**
     * Returns the bearer token (without the "Bearer " prefix).
     *
     * @return the bearer token
     */
    public String getToken() {
        return token;
    }

    /**
     * Returns the optional token type.
     *
     * @return the token type, or null if not specified
     */
    public String getTokenType() {
        return tokenType;
    }

    /**
     * Returns the token expiration time.
     *
     * @return expiration time in milliseconds since epoch, or 0 if no expiration
     */
    public long getExpirationTime() {
        return expirationTime;
    }

    /**
     * Sets the token expiration time.
     *
     * <p>This method can be used to update the expiration time when
     * refreshing tokens or receiving updated token information.
     *
     * @param expirationTime expiration time in milliseconds since epoch, or 0 for no expiration
     */
    public void setExpirationTime(long expirationTime) {
        this.expirationTime = expirationTime;
    }

    /**
     * Checks if the bearer token has expired.
     *
     * @return true if the token has expired, false otherwise
     */
    public boolean isExpired() {
        return expirationTime > 0 && System.currentTimeMillis() >= expirationTime;
    }

    /**
     * Returns the time remaining until token expiration.
     *
     * @return milliseconds until expiration, or Long.MAX_VALUE if no expiration
     */
    public long getTimeToExpiration() {
        if (expirationTime <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, expirationTime - System.currentTimeMillis());
    }

    /**
     * Creates a Bearer authentication instance from an Authorization header value.
     *
     * <p>This method parses "Bearer token" format and extracts the token.
     *
     * @param authorizationValue the Authorization header value (e.g., "Bearer eyJ...")
     * @return a new BearerAuthentication instance
     * @throws IllegalArgumentException if the header format is invalid
     */
    public static BearerAuthentication fromAuthorizationHeader(String authorizationValue) {
        if (authorizationValue == null || !authorizationValue.toLowerCase().startsWith("bearer ")) {
            throw new IllegalArgumentException("Invalid Bearer authorization header format");
        }
        
        String token = authorizationValue.substring(7).trim(); // Remove "Bearer "
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Bearer token is empty");
        }
        
        return new BearerAuthentication(token);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) return false;
        
        BearerAuthentication that = (BearerAuthentication) obj;
        return token.equals(that.token) && 
               java.util.Objects.equals(tokenType, that.tokenType) &&
               expirationTime == that.expirationTime;
    }

    @Override
    public int hashCode() {
        return token.hashCode();
    }

    @Override
    public String toString() {
        return getDescription();
    }
}
