/*
 * HTTPAuthenticationMethods.java
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

package org.bluezoo.gumdrop.http;

import javax.servlet.http.HttpServletRequest;

/**
 * Constants for HTTP authentication methods.
 * 
 * This class extends the standard servlet authentication methods with additional
 * methods for modern token-based authentication schemes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HTTPAuthenticationMethods {

    // Standard servlet authentication methods (for convenience)
    
    /**
     * Basic Authentication (RFC 7617) - username/password with base64 encoding.
     */
    public static final String BASIC_AUTH = HttpServletRequest.BASIC_AUTH;
    
    /**
     * Digest Authentication (RFC 7616) - challenge/response with hashed credentials.
     */
    public static final String DIGEST_AUTH = HttpServletRequest.DIGEST_AUTH;
    
    /**
     * Form-based Authentication - HTML form login (servlet-specific).
     */
    public static final String FORM_AUTH = HttpServletRequest.FORM_AUTH;
    
    /**
     * Client Certificate Authentication - X.509 certificate validation.
     */
    public static final String CLIENT_CERT_AUTH = HttpServletRequest.CLIENT_CERT_AUTH;

    // Extended authentication methods for modern applications
    
    /**
     * Bearer Token Authentication (RFC 6750) - token-based authentication.
     * Used for API keys, JWT tokens, and OAuth 2.0 access tokens.
     */
    public static final String BEARER_AUTH = "BEARER";
    
    /**
     * OAuth 2.0 Authentication (RFC 6749) - comprehensive OAuth flow support.
     * Includes access token validation, scope checking, and refresh handling.
     */
    public static final String OAUTH_AUTH = "OAUTH";

    /**
     * JWT Authentication - JSON Web Token validation.
     * A specific type of Bearer token with additional claims validation.
     */
    public static final String JWT_AUTH = "JWT";

    // Private constructor to prevent instantiation
    private HTTPAuthenticationMethods() {
    }

    /**
     * Check if an authentication method is token-based.
     * 
     * @param authMethod the authentication method constant
     * @return true if the method uses tokens rather than username/password
     */
    public static boolean isTokenBased(String authMethod) {
        return BEARER_AUTH.equals(authMethod) || 
               OAUTH_AUTH.equals(authMethod) || 
               JWT_AUTH.equals(authMethod);
    }

    /**
     * Check if an authentication method is credential-based (username/password).
     * 
     * @param authMethod the authentication method constant
     * @return true if the method uses username/password credentials
     */
    public static boolean isCredentialBased(String authMethod) {
        return BASIC_AUTH.equals(authMethod) || 
               DIGEST_AUTH.equals(authMethod) || 
               FORM_AUTH.equals(authMethod);
    }

    /**
     * Get the HTTP header scheme name for an authentication method.
     * 
     * @param authMethod the authentication method constant
     * @return the scheme name used in Authorization headers, or null if not applicable
     */
    public static String getScheme(String authMethod) {
        switch (authMethod) {
            case BASIC_AUTH: return "Basic";
            case DIGEST_AUTH: return "Digest";
            case BEARER_AUTH: return "Bearer";
            case OAUTH_AUTH: return "Bearer"; // OAuth typically uses Bearer scheme
            case JWT_AUTH: return "Bearer"; // JWT typically uses Bearer scheme
            default: return null;
        }
    }
}
