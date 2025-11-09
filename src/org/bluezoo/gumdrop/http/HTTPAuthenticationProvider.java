/*
 * HTTPAuthenticationProvider.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Realm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ProtocolException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;

/**
 * Abstract base class for HTTP authentication providers.
 * 
 * This class provides the common authentication logic for various HTTP
 * authentication schemes such as Basic, Digest, Bearer, OAuth, etc.
 * Concrete implementations must provide the authentication method and realm name.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class HTTPAuthenticationProvider {

    private static final Logger LOGGER = Logger.getLogger(HTTPAuthenticationProvider.class.getName());
    private static final byte COLON = 0x3a;

    // Nonce management for Digest authentication
    private final Map<String, AtomicInteger> nonces = new ConcurrentHashMap<>();
    private final Set<String> cnonces = new HashSet<>();

    /**
     * Authentication result containing outcome and principal information.
     */
    public static class AuthenticationResult {
        public final boolean success;
        public final String username;
        public final String realm; 
        public final String scheme;
        public final String errorMessage;
        
        /**
         * Creates a successful authentication result.
         */
        public static AuthenticationResult success(String username, String realm, String scheme) {
            return new AuthenticationResult(true, username, realm, scheme, null);
        }
        
        /**
         * Creates a failed authentication result.
         */
        public static AuthenticationResult failure(String errorMessage) {
            return new AuthenticationResult(false, null, null, null, errorMessage);
        }
        
        /**
         * Creates a failed authentication result with specific details.
         */
        public static AuthenticationResult failure(String scheme, String realm, String errorMessage) {
            return new AuthenticationResult(false, null, realm, scheme, errorMessage);
        }
        
        private AuthenticationResult(boolean success, String username, String realm, String scheme, String errorMessage) {
            this.success = success;
            this.username = username;
            this.realm = realm;
            this.scheme = scheme;
            this.errorMessage = errorMessage;
        }
        
        @Override
        public String toString() {
            if (success) {
                return String.format("AuthenticationResult{success=true, username='%s', realm='%s', scheme='%s'}", 
                    username, realm, scheme);
            } else {
                return String.format("AuthenticationResult{success=false, errorMessage='%s'}", errorMessage);
            }
        }
    }

    /**
     * Get the authentication method configured for this provider.
     * 
     * @return the authentication method (e.g., "BASIC", "DIGEST"), or null if none configured
     */
    protected abstract String getAuthMethod();

    /**
     * Get the realm name for this provider.
     * 
     * @return the realm name, or null if none configured
     */
    protected abstract String getRealmName();

    /**
     * Verify credentials against the realm.
     * 
     * @param realm the realm name
     * @param username the username
     * @param password the password
     * @return true if credentials are valid, false otherwise
     */
    protected abstract boolean passwordMatch(String realm, String username, String password);

    /**
     * Get the digest HA1 hash for a user.
     * 
     * @param realm the realm name
     * @param username the username
     * @return the HA1 hash as hex string, or null if user doesn't exist
     */
    protected abstract String getDigestHA1(String realm, String username);

    /**
     * Validate a Bearer token.
     * 
     * @param token the bearer token to validate
     * @return token validation result, or null if Bearer auth is not supported
     */
    protected abstract Realm.TokenValidationResult validateBearerToken(String token);

    /**
     * Validate an OAuth access token.
     * 
     * @param accessToken the OAuth access token to validate
     * @return token validation result, or null if OAuth is not supported
     */
    protected abstract Realm.TokenValidationResult validateOAuthToken(String accessToken);

    /**
     * Authenticate a request using the Authorization header value.
     * 
     * @param authorizationHeader the Authorization header value from the HTTP request
     * @return authentication result indicating success or failure with details
     */
    public final AuthenticationResult authenticate(String authorizationHeader) {
        String authMethod = getAuthMethod();
        if (authMethod == null) {
            return AuthenticationResult.failure("No authentication method configured");
        }

        if (authorizationHeader == null) {
            return AuthenticationResult.failure("No Authorization header provided");
        }

        int spaceIndex = authorizationHeader.indexOf(' ');
        if (spaceIndex < 1) {
            return AuthenticationResult.failure("Invalid Authorization header format");
        }

        String scheme = authorizationHeader.substring(0, spaceIndex);
        String credentials = authorizationHeader.substring(spaceIndex + 1);

        try {
            switch (authMethod) {
                case HttpServletRequest.BASIC_AUTH:
                    if ("Basic".equals(scheme)) {
                        return authenticateBasic(credentials);
                    }
                    break;
                case HttpServletRequest.DIGEST_AUTH:
                    if ("Digest".equals(scheme)) {
                        return authenticateDigest(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.BEARER_AUTH:
                    if ("Bearer".equals(scheme)) {
                        return authenticateBearer(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.OAUTH_AUTH:
                    if ("Bearer".equals(scheme)) {
                        return authenticateOAuth(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.JWT_AUTH:
                    if ("Bearer".equals(scheme)) {
                        return authenticateJWT(credentials);
                    }
                    break;
            }

            return AuthenticationResult.failure(authMethod + " authentication required, but " + scheme + " provided");

        } catch (Exception e) {
            String message = "Authentication failed: " + e.getMessage();
            LOGGER.log(Level.WARNING, message, e);
            return AuthenticationResult.failure(message);
        }
    }
    
    /**
     * Generate a WWW-Authenticate challenge header value for 401 responses.
     * 
     * @return the WWW-Authenticate header value, or null if no authentication is configured
     */
    public final String generateChallenge() {
        String authMethod = getAuthMethod();
        String realmName = getRealmName();
        
        if (authMethod == null || realmName == null) {
            return null;
        }

        switch (authMethod) {
            case HttpServletRequest.BASIC_AUTH:
                return "Basic realm=\"" + realmName + "\"";

            case HttpServletRequest.DIGEST_AUTH:
                try {
                    String nonce = generateNonce();
                    return "Digest realm=\"" + realmName + "\", nonce=\"" + nonce + "\", qop=\"auth\"";
                } catch (NoSuchAlgorithmException e) {
                    LOGGER.log(Level.SEVERE, "Cannot generate digest challenge", e);
                    return null;
                }

            case HTTPAuthenticationMethods.BEARER_AUTH:
                return "Bearer realm=\"" + realmName + "\"";

            case HTTPAuthenticationMethods.OAUTH_AUTH:
                return "Bearer realm=\"" + realmName + "\", scope=\"read write\"";

            case HTTPAuthenticationMethods.JWT_AUTH:
                return "Bearer realm=\"" + realmName + "\", token_type=\"JWT\"";

            default:
                return null;
        }
    }
    
    /**
     * Check if this provider supports the given authentication scheme.
     * 
     * @param scheme the authentication scheme (e.g., "Basic", "Digest", "Bearer")
     * @return true if the scheme is supported, false otherwise
     */
    public final boolean supportsScheme(String scheme) {
        String authMethod = getAuthMethod();
        if (authMethod == null) {
            return false;
        }

        switch (authMethod) {
            case HttpServletRequest.BASIC_AUTH:
                return "Basic".equals(scheme);
            case HttpServletRequest.DIGEST_AUTH:
                return "Digest".equals(scheme);
            case HTTPAuthenticationMethods.BEARER_AUTH:
            case HTTPAuthenticationMethods.OAUTH_AUTH:
            case HTTPAuthenticationMethods.JWT_AUTH:
                return "Bearer".equals(scheme);
            default:
                return false;
        }
    }
    
    /**
     * Get the authentication schemes supported by this provider.
     * 
     * @return set of supported scheme names (e.g., {"Basic", "Digest"})
     */
    public final Set<String> getSupportedSchemes() {
        Set<String> schemes = new HashSet<>();
        String authMethod = getAuthMethod();
        if (authMethod != null) {
            switch (authMethod) {
                case HttpServletRequest.BASIC_AUTH:
                    schemes.add("Basic");
                    break;
                case HttpServletRequest.DIGEST_AUTH:
                    schemes.add("Digest");
                    break;
                case HTTPAuthenticationMethods.BEARER_AUTH:
                case HTTPAuthenticationMethods.OAUTH_AUTH:
                case HTTPAuthenticationMethods.JWT_AUTH:
                    schemes.add("Bearer");
                    break;
            }
        }
        return schemes;
    }
    
    /**
     * Check if authentication is required for requests.
     * 
     * @return true if authentication is required, false if authentication is optional
     */
    public boolean isAuthenticationRequired() {
        return true;
    }

    /**
     * Authenticate using Basic authentication.
     */
    private AuthenticationResult authenticateBasic(String credentials) {
        try {
            byte[] base64UserPass = credentials.getBytes("US-ASCII");
            String userPass = new String(Base64.getDecoder().decode(base64UserPass), "US-ASCII");
            int ci = userPass.indexOf(COLON);
            if (ci < 1) {
                return AuthenticationResult.failure("Invalid Basic credentials format");
            }

            String username = userPass.substring(0, ci);
            String password = userPass.substring(ci + 1);

            if (passwordMatch(getRealmName(), username, password)) {
                return AuthenticationResult.success(username, getRealmName(), "Basic");
            } else {
                LOGGER.warning("Authentication failed for user: " + username);
                return AuthenticationResult.failure("Invalid credentials for user: " + username);
            }

        } catch (Exception e) {
            return AuthenticationResult.failure("Basic authentication failed: " + e.getMessage());
        }
    }

    /**
     * Authenticate using Digest authentication.
     */
    private AuthenticationResult authenticateDigest(String credentials) {
        try {
            Map<String, String> digestResponse = parseDigestResponse(credentials);
            String username = digestResponse.get("username");
            String realm = digestResponse.get("realm");
            String ha1Hex = getDigestHA1(realm, username);

            if (ha1Hex == null) {
                return AuthenticationResult.failure("No such user: " + username);
            }

            String nonce = digestResponse.get("nonce");
            String requestDigest = digestResponse.get("response");
            String qop = digestResponse.get("qop");
            String algorithm = digestResponse.get("algorithm");
            String cnonce = digestResponse.get("cnonce");
            String nc = digestResponse.get("nc");

            if (username == null || realm == null || requestDigest == null || nonce == null || cnonce == null || nc == null) {
                return AuthenticationResult.failure("Invalid digest response format");
            }

            // Check nonce
            try {
                int clientNonceCount = Integer.parseInt(nc, 16); // hexadecimal
                int serverNonceCount = getNonceCount(nonce);
                if (clientNonceCount != serverNonceCount || serverNonceCount < 1 || seenCnonce(cnonce + nc)) {
                    return AuthenticationResult.failure("Invalid nonce or replay attack detected");
                }
            } catch (Exception e) {
                return AuthenticationResult.failure("Invalid digest response format");
            }

            if (algorithm == null) {
                algorithm = "MD5";
            }

            // Verify digest response
            if (verifyDigestResponse(ha1Hex, algorithm, nonce, qop, nc, cnonce, "GET", "/", requestDigest)) {
                return AuthenticationResult.success(username, realm, "Digest");
            } else {
                LOGGER.warning("Digest verification failed for user: " + username);
                return AuthenticationResult.failure("Digest verification failed for user: " + username);
            }

        } catch (Exception e) {
            return AuthenticationResult.failure("Digest authentication failed: " + e.getMessage());
        }
    }

    /**
     * Authenticate using Bearer token authentication.
     */
    private AuthenticationResult authenticateBearer(String token) {
        try {
            Realm.TokenValidationResult result = validateBearerToken(token);
            
            if (result == null) {
                return AuthenticationResult.failure("Bearer authentication not supported");
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure("Invalid Bearer token");
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure("Bearer token has expired");
            }
            
            return AuthenticationResult.success(result.username, getRealmName(), "Bearer");
            
        } catch (Exception e) {
            return AuthenticationResult.failure("Bearer authentication failed: " + e.getMessage());
        }
    }

    /**
     * Authenticate using OAuth access token.
     */
    private AuthenticationResult authenticateOAuth(String accessToken) {
        try {
            Realm.TokenValidationResult result = validateOAuthToken(accessToken);
            
            if (result == null) {
                return AuthenticationResult.failure("OAuth authentication not supported");
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure("Invalid OAuth access token");
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure("OAuth access token has expired");
            }
            
            // Could add scope validation here
            // if (!result.hasScope("required_scope")) {
            //     return AuthenticationResult.failure("Insufficient OAuth scope");
            // }
            
            return AuthenticationResult.success(result.username, getRealmName(), "OAuth");
            
        } catch (Exception e) {
            return AuthenticationResult.failure("OAuth authentication failed: " + e.getMessage());
        }
    }

    /**
     * Authenticate using JWT token.
     * This is essentially Bearer authentication but with JWT-specific validation.
     */
    private AuthenticationResult authenticateJWT(String jwtToken) {
        try {
            // For JWT, we can use the same Bearer token validation
            // but with additional JWT-specific checks if needed
            Realm.TokenValidationResult result = validateBearerToken(jwtToken);
            
            if (result == null) {
                return AuthenticationResult.failure("JWT authentication not supported");
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure("Invalid JWT token");
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure("JWT token has expired");
            }
            
            return AuthenticationResult.success(result.username, getRealmName(), "JWT");
            
        } catch (Exception e) {
            return AuthenticationResult.failure("JWT authentication failed: " + e.getMessage());
        }
    }

    /**
     * Parse the digest response string into key-value pairs.
     */
    private Map<String, String> parseDigestResponse(String text) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        boolean inQuote = false;
        char[] chars = text.toCharArray();
        StringBuilder buf = new StringBuilder();
        String key = null;

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == ',') {
                    // End of pair
                    String val = unquote(buf.toString());
                    buf.setLength(0);
                    if (key == null) {
                        throw new ProtocolException("Bad digest format: " + text);
                    }
                    map.put(key, val);
                    key = null;
                } else if (c == '=') {
                    // End of key
                    key = buf.toString().trim();
                    buf.setLength(0);
                } else {
                    buf.append(c);
                }
            } else {
                buf.append(c);
            }
        }

        if (inQuote || key == null) {
            throw new ProtocolException("Bad digest format: " + text);
        } else {
            // End of pair
            String val = unquote(buf.toString());
            map.put(key, val);
        }

        return map;
    }

    /**
     * Remove quotes from a quoted string.
     */
    private String unquote(String text) {
        if (text != null) {
            int len = text.length();
            if (len > 1 && text.charAt(0) == '"' && text.charAt(len - 1) == '"') {
                text = text.substring(1, len - 1);
            }
        }
        return text;
    }

    /**
     * Generate a nonce value for Digest authentication.
     */
    private String generateNonce() throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oo = new ObjectOutputStream(bo);
            oo.writeLong(System.currentTimeMillis());
            md.update(bo.toByteArray());
            bo.reset();
            oo.writeDouble(Math.random());
            md.update(bo.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e); // Should not happen
        }

        String nonce = toHexString(Base64.getEncoder().encode(md.digest()));
        newNonce(nonce);
        return nonce;
    }

    /**
     * Verify a digest response against expected values.
     */
    private boolean verifyDigestResponse(String ha1Hex, String algorithm, String nonce, String qop, 
                                       String nc, String cnonce, String method, String digestUri, 
                                       String requestDigest) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);

        // Get final H(A1)
        String finalHA1Hex;
        if ("MD5-sess".equals(algorithm)) {
            if (cnonce == null) {
                return false;
            }
            md.reset();
            byte[] ha1 = hexStringToBytes(ha1Hex);
            md.update(ha1);
            md.update(COLON);
            md.update(nonce.getBytes());
            md.update(COLON);
            md.update(cnonce.getBytes());
            byte[] sessHA1 = md.digest();
            finalHA1Hex = toHexString(sessHA1);
        } else {
            finalHA1Hex = ha1Hex;
        }

        // Compute H(A2)
        md.reset();
        md.update(method.getBytes());
        md.update(COLON);
        md.update(digestUri.getBytes());
        if ("auth-int".equals(qop)) {
            throw new UnsupportedOperationException("auth-int not supported");
        }
        byte[] ha2 = md.digest();
        String ha2Hex = toHexString(ha2);

        // Calculate response
        md.reset();
        md.update(finalHA1Hex.getBytes());
        md.update(COLON);
        md.update(nonce.getBytes());
        if ("auth".equals(qop)) {
            md.update(COLON);
            md.update(nc.getBytes());
            md.update(COLON);
            md.update(cnonce.getBytes());
            md.update(COLON);
            md.update(qop.getBytes());
        }
        md.update(COLON);
        md.update(ha2Hex.getBytes());
        String computed = toHexString(md.digest());

        return computed.equals(requestDigest);
    }

    /**
     * Associate a new nonce value for replay attack prevention.
     */
    private void newNonce(String nonce) {
        nonces.put(nonce, new AtomicInteger(0));
    }

    /**
     * Get and increment the nonce count for replay attack prevention.
     */
    private int getNonceCount(String nonce) {
        AtomicInteger nonceCount = nonces.get(nonce);
        return (nonceCount == null) ? -1 : nonceCount.incrementAndGet();
    }

    /**
     * Check if we've seen this client nonce before (replay attack prevention).
     */
    private boolean seenCnonce(String cnonce) {
        synchronized (cnonces) {
            return cnonces.add(cnonce);
        }
    }

    /**
     * Convert bytes to hexadecimal string.
     */
    private static String toHexString(byte[] bytes) {
        char[] ret = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int c = (int) bytes[i];
            if (c < 0) {
                c += 0x100;
            }
            ret[j++] = Character.forDigit(c / 0x10, 0x10);
            ret[j++] = Character.forDigit(c % 0x10, 0x10);
        }
        return new String(ret);
    }

    /**
     * Convert hex string to byte array.
     */
    private static byte[] hexStringToBytes(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                 + Character.digit(hexString.charAt(i+1), 16));
        }
        return data;
    }
}
