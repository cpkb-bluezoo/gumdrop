/*
 * HTTPAuthenticationProvider.java
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

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.util.ByteArrays;

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
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;

/**
 * Abstract base class for HTTP authentication providers.
 * 
 * <p>This class provides the common authentication logic for various HTTP
 * authentication schemes including:</p>
 * <ul>
 *   <li><b>Basic</b> - RFC 7617 username/password authentication</li>
 *   <li><b>Digest</b> - RFC 7616 challenge-response authentication</li>
 *   <li><b>Bearer</b> - RFC 6750 token-based authentication</li>
 *   <li><b>OAuth</b> - RFC 6749 access token authentication</li>
 *   <li><b>JWT</b> - JSON Web Token authentication</li>
 * </ul>
 * 
 * <p>Concrete implementations must provide the authentication method, realm name,
 * and credential verification logic by implementing the abstract methods.</p>
 * 
 * <h4>Usage Example</h4>
 * <pre>{@code
 * public class MyAuthProvider extends HTTPAuthenticationProvider {
 *     private final Realm realm;
 *     
 *     protected String getAuthMethod() {
 *         return HttpServletRequest.BASIC_AUTH;
 *     }
 *     
 *     protected String getRealmName() {
 *         return "MyApp";
 *     }
 *     
 *     protected boolean passwordMatch(String realm, String username, String password) {
 *         return this.realm.passwordMatch(username, password);
 *     }
 *     
 *     // ... other abstract method implementations
 * }
 * }</pre>
 * 
 * <h4>Thread Safety</h4>
 * <p>This class is thread-safe. Nonce management uses concurrent data structures.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticationResult
 * @see HTTPAuthenticationMethods
 */
public abstract class HTTPAuthenticationProvider {

    private static final Logger LOGGER = Logger.getLogger(HTTPAuthenticationProvider.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");
    private static final byte COLON = 0x3a;

    // Nonce management for Digest authentication
    private final Map<String, AtomicInteger> nonces = new ConcurrentHashMap<String, AtomicInteger>();
    private final Set<String> cnonces = new HashSet<String>();

    /**
     * Authentication result containing outcome and principal information.
     * 
     * <p>This class encapsulates the result of an authentication attempt,
     * including whether it succeeded, the authenticated user's information,
     * and any error message for failed attempts.</p>
     */
    public static class AuthenticationResult {
        /** Whether the authentication was successful. */
        public final boolean success;
        /** The authenticated username, or null if authentication failed. */
        public final String username;
        /** The realm name used for authentication. */
        public final String realm; 
        /** The authentication scheme used (e.g., "Basic", "Digest", "Bearer"). */
        public final String scheme;
        /** Error message if authentication failed, null otherwise. */
        public final String errorMessage;
        
        /**
         * Creates a successful authentication result.
         * 
         * @param username the authenticated username
         * @param realm the realm name
         * @param scheme the authentication scheme used
         * @return a successful authentication result
         */
        public static AuthenticationResult success(String username, String realm, String scheme) {
            return new AuthenticationResult(true, username, realm, scheme, null);
        }
        
        /**
         * Creates a failed authentication result with an error message.
         * 
         * @param errorMessage the reason for authentication failure
         * @return a failed authentication result
         */
        public static AuthenticationResult failure(String errorMessage) {
            return new AuthenticationResult(false, null, null, null, errorMessage);
        }
        
        /**
         * Creates a failed authentication result with specific scheme and realm details.
         * 
         * @param scheme the authentication scheme that was attempted
         * @param realm the realm name
         * @param errorMessage the reason for authentication failure
         * @return a failed authentication result
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
     * Gets the authentication method configured for this provider.
     * 
     * <p>The return value should be one of the standard authentication
     * method constants from {@link HttpServletRequest} or 
     * {@link HTTPAuthenticationMethods}.</p>
     * 
     * @return the authentication method (e.g., "BASIC", "DIGEST"), or null if none configured
     */
    protected abstract String getAuthMethod();

    /**
     * Gets the realm name for this provider.
     * 
     * <p>The realm name is included in authentication challenges and is used
     * to partition authentication spaces.</p>
     * 
     * @return the realm name, or null if none configured
     */
    protected abstract String getRealmName();

    /**
     * Verifies username and password credentials against the authentication realm.
     * 
     * <p>This method is called for Basic authentication and may also be used
     * by other authentication mechanisms that require password verification.</p>
     * 
     * @param realm the realm name for credential lookup
     * @param username the username to verify
     * @param password the password to verify
     * @return true if the credentials are valid, false otherwise
     */
    protected abstract boolean passwordMatch(String realm, String username, String password);

    /**
     * Gets the precomputed H(A1) hash for Digest authentication.
     * 
     * <p>For Digest authentication, H(A1) = MD5(username:realm:password).
     * Implementations may store this precomputed hash for security, avoiding
     * the need to store plaintext passwords.</p>
     * 
     * @param realm the realm name
     * @param username the username
     * @return the H(A1) hash as a lowercase hexadecimal string, or null if the user doesn't exist
     */
    protected abstract String getDigestHA1(String realm, String username);

    /**
     * Validates a Bearer token for token-based authentication.
     * 
     * <p>Called for Bearer authentication (RFC 6750). Implementations should
     * verify the token's signature, expiration, and associated claims.</p>
     * 
     * @param token the bearer token to validate
     * @return a {@link Realm.TokenValidationResult} with validation outcome,
     *         or null if Bearer authentication is not supported
     */
    protected abstract Realm.TokenValidationResult validateBearerToken(String token);

    /**
     * Validates an OAuth 2.0 access token.
     * 
     * <p>Called for OAuth authentication (RFC 6749). Implementations should
     * verify the token against the authorization server or introspection endpoint.</p>
     * 
     * @param accessToken the OAuth access token to validate
     * @return a {@link Realm.TokenValidationResult} with validation outcome,
     *         or null if OAuth authentication is not supported
     */
    protected abstract Realm.TokenValidationResult validateOAuthToken(String accessToken);

    /**
     * Checks if the underlying Realm supports HTTP Digest authentication.
     * 
     * <p>HTTP Digest authentication requires the Realm to provide the H(A1) hash
     * via {@link #getDigestHA1(String, String)}. Some Realm implementations 
     * (e.g., LDAP with hashed passwords) cannot support this.</p>
     * 
     * <p>The default implementation returns true, assuming Digest is supported.
     * Subclasses should override this if they can determine whether the Realm
     * actually supports Digest authentication.</p>
     * 
     * @return true if Digest authentication is supported, false otherwise
     */
    protected boolean supportsDigestAuth() {
        return true; // Default: assume supported
    }

    /**
     * Authenticates a request using the Authorization header value.
     * 
     * <p>This method parses the Authorization header, determines the
     * authentication scheme, and delegates to the appropriate authentication
     * method based on the configured {@link #getAuthMethod()}.</p>
     * 
     * @param authorizationHeader the Authorization header value from the HTTP request,
     *        in the format "Scheme credentials" (e.g., "Basic dXNlcjpwYXNz")
     * @return an {@link AuthenticationResult} indicating success or failure with details
     */
    public final AuthenticationResult authenticate(String authorizationHeader) {
        String authMethod = getAuthMethod();
        if (authMethod == null) {
            return AuthenticationResult.failure(L10N.getString("auth.err.no_method_configured"));
        }

        if (authorizationHeader == null) {
            return AuthenticationResult.failure(L10N.getString("auth.err.no_authorization_header"));
        }

        int spaceIndex = authorizationHeader.indexOf(' ');
        if (spaceIndex < 1) {
            return AuthenticationResult.failure(L10N.getString("auth.err.invalid_header_format"));
        }

        String scheme = authorizationHeader.substring(0, spaceIndex);
        String credentials = authorizationHeader.substring(spaceIndex + 1);

        try {
            // HTTP authentication schemes are case-insensitive per RFC 7235
            switch (authMethod) {
                case HttpServletRequest.BASIC_AUTH:
                    if ("Basic".equalsIgnoreCase(scheme)) {
                        return authenticateBasic(credentials);
                    }
                    break;
                case HttpServletRequest.DIGEST_AUTH:
                    if ("Digest".equalsIgnoreCase(scheme)) {
                        return authenticateDigest(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.BEARER_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        return authenticateBearer(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.OAUTH_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        return authenticateOAuth(credentials);
                    }
                    break;
                case HTTPAuthenticationMethods.JWT_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        return authenticateJWT(credentials);
                    }
                    break;
            }

            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.scheme_mismatch"), authMethod, scheme));

        } catch (Exception e) {
            String message = MessageFormat.format(L10N.getString("auth.err.authentication_failed"), e.getMessage());
            LOGGER.log(Level.WARNING, message, e);
            return AuthenticationResult.failure(message);
        }
    }
    
    /**
     * Generates a WWW-Authenticate challenge header value for 401 responses.
     * 
     * <p>This method generates the appropriate challenge based on the configured
     * authentication method. For Digest authentication, it includes a fresh nonce.</p>
     * 
     * @return the WWW-Authenticate header value (e.g., "Basic realm=\"MyApp\""),
     *         or null if no authentication is configured
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
                // Check if the Realm supports Digest authentication
                if (!supportsDigestAuth()) {
                    LOGGER.severe(L10N.getString("auth.err.digest_not_supported_by_realm"));
                    return null; // Cannot generate challenge - realm doesn't support Digest
                }
                try {
                    String nonce = generateNonce();
                    return "Digest realm=\"" + realmName + "\", nonce=\"" + nonce + "\", qop=\"auth\"";
                } catch (NoSuchAlgorithmException e) {
                    LOGGER.log(Level.SEVERE, L10N.getString("auth.err.generate_digest_challenge"), e);
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
     * Checks if this provider supports the given authentication scheme.
     * 
     * <p>Scheme matching is case-insensitive per RFC 7235.</p>
     * 
     * @param scheme the authentication scheme to check (e.g., "Basic", "Digest", "Bearer")
     * @return true if the scheme is supported by this provider, false otherwise
     */
    public final boolean supportsScheme(String scheme) {
        String authMethod = getAuthMethod();
        if (authMethod == null) {
            return false;
        }

        // HTTP authentication schemes are case-insensitive per RFC 7235
        switch (authMethod) {
            case HttpServletRequest.BASIC_AUTH:
                return "Basic".equalsIgnoreCase(scheme);
            case HttpServletRequest.DIGEST_AUTH:
                return "Digest".equalsIgnoreCase(scheme);
            case HTTPAuthenticationMethods.BEARER_AUTH:
            case HTTPAuthenticationMethods.OAUTH_AUTH:
            case HTTPAuthenticationMethods.JWT_AUTH:
                return "Bearer".equalsIgnoreCase(scheme);
            default:
                return false;
        }
    }
    
    /**
     * Gets the set of authentication schemes supported by this provider.
     * 
     * @return an unmodifiable set of supported scheme names (e.g., {"Basic"} or {"Bearer"})
     */
    public final Set<String> getSupportedSchemes() {
        Set<String> schemes = new HashSet<String>();
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
     * Checks if authentication is required for requests to this provider.
     * 
     * <p>The default implementation returns true. Subclasses may override
     * to implement optional authentication.</p>
     * 
     * @return true if authentication is required, false if authentication is optional
     */
    public boolean isAuthenticationRequired() {
        return true;
    }

    /**
     * Authenticates using HTTP Basic authentication (RFC 7617).
     * 
     * @param credentials the Base64-encoded username:password string
     * @return the authentication result
     */
    private AuthenticationResult authenticateBasic(String credentials) {
        try {
            byte[] base64UserPass = credentials.getBytes("US-ASCII");
            String userPass = new String(Base64.getDecoder().decode(base64UserPass), "US-ASCII");
            int ci = userPass.indexOf(COLON);
            if (ci < 1) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_basic_format"));
            }

            String username = userPass.substring(0, ci);
            String password = userPass.substring(ci + 1);

            if (passwordMatch(getRealmName(), username, password)) {
                return AuthenticationResult.success(username, getRealmName(), "Basic");
            } else {
                LOGGER.warning(MessageFormat.format(L10N.getString("auth.warn.auth_failed_for_user"), username));
                return AuthenticationResult.failure(
                    MessageFormat.format(L10N.getString("auth.err.invalid_credentials"), username));
            }

        } catch (Exception e) {
            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.basic_failed"), e.getMessage()));
        }
    }

    /**
     * Authenticates using HTTP Digest authentication (RFC 7616).
     * 
     * @param credentials the Digest challenge response parameters
     * @return the authentication result
     */
    private AuthenticationResult authenticateDigest(String credentials) {
        // Check if the Realm supports Digest authentication
        if (!supportsDigestAuth()) {
            LOGGER.severe(L10N.getString("auth.err.digest_not_supported_by_realm"));
            return AuthenticationResult.failure("Digest", getRealmName(),
                L10N.getString("auth.err.digest_not_supported_by_realm"));
        }

        try {
            Map<String, String> digestResponse = parseDigestResponse(credentials);
            String username = digestResponse.get("username");
            String realm = digestResponse.get("realm");
            String ha1Hex = getDigestHA1(realm, username);

            if (ha1Hex == null) {
                return AuthenticationResult.failure(
                    MessageFormat.format(L10N.getString("auth.err.no_such_user"), username));
            }

            String nonce = digestResponse.get("nonce");
            String requestDigest = digestResponse.get("response");
            String qop = digestResponse.get("qop");
            String algorithm = digestResponse.get("algorithm");
            String cnonce = digestResponse.get("cnonce");
            String nc = digestResponse.get("nc");

            if (username == null || realm == null || requestDigest == null || nonce == null || cnonce == null || nc == null) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
            }

            // Check nonce
            try {
                int clientNonceCount = Integer.parseInt(nc, 16); // hexadecimal
                int serverNonceCount = getNonceCount(nonce);
                if (clientNonceCount != serverNonceCount || serverNonceCount < 1 || seenCnonce(cnonce + nc)) {
                    return AuthenticationResult.failure(L10N.getString("auth.err.nonce_invalid"));
                }
            } catch (Exception e) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
            }

            if (algorithm == null) {
                algorithm = "MD5";
            }

            // Verify digest response
            if (verifyDigestResponse(ha1Hex, algorithm, nonce, qop, nc, cnonce, "GET", "/", requestDigest)) {
                return AuthenticationResult.success(username, realm, "Digest");
            } else {
                LOGGER.warning(MessageFormat.format(L10N.getString("auth.warn.digest_verification_failed"), username));
                return AuthenticationResult.failure(
                    MessageFormat.format(L10N.getString("auth.err.digest_verification_failed"), username));
            }

        } catch (Exception e) {
            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.digest_failed"), e.getMessage()));
        }
    }

    /**
     * Authenticates using Bearer token authentication (RFC 6750).
     * 
     * @param token the Bearer token
     * @return the authentication result
     */
    private AuthenticationResult authenticateBearer(String token) {
        try {
            Realm.TokenValidationResult result = validateBearerToken(token);
            
            if (result == null) {
                return AuthenticationResult.failure(L10N.getString("auth.err.bearer_not_supported"));
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_bearer_token"));
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure(L10N.getString("auth.err.bearer_expired"));
            }
            
            return AuthenticationResult.success(result.username, getRealmName(), "Bearer");
            
        } catch (Exception e) {
            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.bearer_failed"), e.getMessage()));
        }
    }

    /**
     * Authenticates using OAuth 2.0 access token (RFC 6749).
     * 
     * @param accessToken the OAuth access token
     * @return the authentication result
     */
    private AuthenticationResult authenticateOAuth(String accessToken) {
        try {
            Realm.TokenValidationResult result = validateOAuthToken(accessToken);
            
            if (result == null) {
                return AuthenticationResult.failure(L10N.getString("auth.err.oauth_not_supported"));
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_oauth_token"));
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure(L10N.getString("auth.err.oauth_expired"));
            }
            
            return AuthenticationResult.success(result.username, getRealmName(), "OAuth");
            
        } catch (Exception e) {
            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.oauth_failed"), e.getMessage()));
        }
    }

    /**
     * Authenticates using JWT token.
     * 
     * <p>This is essentially Bearer authentication with JWT-specific validation.</p>
     * 
     * @param jwtToken the JWT token
     * @return the authentication result
     */
    private AuthenticationResult authenticateJWT(String jwtToken) {
        try {
            // For JWT, we use Bearer token validation with JWT-specific checks
            Realm.TokenValidationResult result = validateBearerToken(jwtToken);
            
            if (result == null) {
                return AuthenticationResult.failure(L10N.getString("auth.err.jwt_not_supported"));
            }
            
            if (!result.valid) {
                return AuthenticationResult.failure(L10N.getString("auth.err.invalid_jwt_token"));
            }
            
            if (result.isExpired()) {
                return AuthenticationResult.failure(L10N.getString("auth.err.jwt_expired"));
            }
            
            return AuthenticationResult.success(result.username, getRealmName(), "JWT");
            
        } catch (Exception e) {
            return AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.jwt_failed"), e.getMessage()));
        }
    }

    /**
     * Parses a Digest authentication response into key-value pairs.
     * 
     * @param text the digest response string
     * @return a map of parameter names to values
     * @throws IOException if the format is invalid
     */
    private Map<String, String> parseDigestResponse(String text) throws IOException {
        Map<String, String> map = new LinkedHashMap<String, String>();
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
                        throw new ProtocolException(
                            MessageFormat.format(L10N.getString("auth.err.bad_digest_format"), text));
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
            throw new ProtocolException(
                MessageFormat.format(L10N.getString("auth.err.bad_digest_format"), text));
        } else {
            // End of pair
            String val = unquote(buf.toString());
            map.put(key, val);
        }

        return map;
    }

    /**
     * Removes surrounding quotes from a quoted string.
     * 
     * @param text the possibly quoted string
     * @return the unquoted string
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
     * Generates a nonce value for Digest authentication.
     * 
     * @return the generated nonce as a hex string
     * @throws NoSuchAlgorithmException if MD5 is not available
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

        String nonce = ByteArrays.toHexString(Base64.getEncoder().encode(md.digest()));
        newNonce(nonce);
        return nonce;
    }

    /**
     * Verifies a Digest authentication response against the expected values.
     * 
     * @param ha1Hex the precomputed H(A1) hash as hex string
     * @param algorithm the digest algorithm (MD5 or MD5-sess)
     * @param nonce the server-provided nonce
     * @param qop the quality of protection (auth or auth-int)
     * @param nc the nonce count as hex string
     * @param cnonce the client nonce
     * @param method the HTTP method
     * @param digestUri the request URI
     * @param requestDigest the client-provided digest response
     * @return true if the digest matches, false otherwise
     * @throws NoSuchAlgorithmException if the algorithm is not available
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
            byte[] ha1 = ByteArrays.toByteArray(ha1Hex);
            md.update(ha1);
            md.update(COLON);
            md.update(nonce.getBytes());
            md.update(COLON);
            md.update(cnonce.getBytes());
            byte[] sessHA1 = md.digest();
            finalHA1Hex = ByteArrays.toHexString(sessHA1);
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
        String ha2Hex = ByteArrays.toHexString(ha2);

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
        String computed = ByteArrays.toHexString(md.digest());

        return computed.equals(requestDigest);
    }

    /**
     * Registers a new nonce for replay attack prevention.
     * 
     * @param nonce the nonce to register
     */
    private void newNonce(String nonce) {
        nonces.put(nonce, new AtomicInteger(0));
    }

    /**
     * Gets and increments the nonce count for replay attack prevention.
     * 
     * @param nonce the nonce to look up
     * @return the incremented nonce count, or -1 if the nonce is unknown
     */
    private int getNonceCount(String nonce) {
        AtomicInteger nonceCount = nonces.get(nonce);
        return (nonceCount == null) ? -1 : nonceCount.incrementAndGet();
    }

    /**
     * Checks if a client nonce has been seen before (replay attack prevention).
     * 
     * @param cnonce the client nonce concatenated with nonce count
     * @return true if this is a new cnonce, false if it was already seen
     */
    private boolean seenCnonce(String cnonce) {
        synchronized (cnonces) {
            return cnonces.add(cnonce);
        }
    }

}
