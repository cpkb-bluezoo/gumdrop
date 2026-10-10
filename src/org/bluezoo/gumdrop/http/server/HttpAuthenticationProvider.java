/*
 * HttpAuthenticationProvider.java
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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

import java.io.IOException;
import java.net.ProtocolException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for HTTP authentication providers.
 *
 * <p>Implements the HTTP Authentication framework per RFC 9110 section 11.
 * RFC 9110 section 11.6.1: a 401 response MUST include a WWW-Authenticate
 * header with at least one applicable challenge. The {@link #generateChallenge()}
 * method produces this header value.
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
 * public class MyAuthProvider extends HttpAuthenticationProvider {
 *     private final Realm realm;
 *     
 *     protected String getAuthMethod() {
 *         return HttpAuthenticationMethods.BASIC_AUTH;
 *     }
 *     
 *     protected String getRealmName() {
 *         return "MyApp";
 *     }
 *     
 *     protected void passwordMatch(SelectorLoop loop, String realm, String username,
 *             String password, RealmCallback<Boolean> callback) {
 *         this.realm.forSelectorLoop(loop).passwordMatch(username, password, callback);
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
 * @see HttpAuthenticationMethods
 */
public abstract class HttpAuthenticationProvider {

    private TelemetryConfig telemetryConfig;

    private EventLogger events() {
        return telemetry().getLogger(HttpAuthenticationProvider.class, L10N);
    }
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /** The one Digest hash algorithm offered and accepted (RFC 7616). */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Cryptographically strong randomness for Digest nonce generation. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final byte COLON = 0x3a;

    /**
     * RFC 7616 does not mandate a specific nonce lifetime; five minutes
     * matches common server defaults (e.g. Apache httpd's
     * {@code AuthDigestNonceLifetime}). A client whose nonce has expired
     * simply gets a fresh challenge on its next request -- {@link
     * #getNonceCount} already treats an unrecognised (including
     * evicted) nonce as invalid, so eviction is just enforcing this
     * lifetime rather than a new failure mode.
     */
    private static final long NONCE_TTL_MS = 5L * 60L * 1000L;

    /**
     * Once {@link #nonces} plus {@link #cnonces} together exceed this
     * many tracked entries, a sweep removes anything past {@link
     * #NONCE_TTL_MS}. Keeps both maps bounded under sustained traffic
     * (or a client deliberately churning 401 challenges) without needing
     * a dedicated background thread: the sweep piggybacks on whatever
     * request happens to push the count over the threshold.
     */
    private static final int EVICTION_SWEEP_THRESHOLD = 10_000;

    /** A tracked nonce: its replay count plus when it was issued, for TTL eviction. */
    private static final class NonceEntry {
        final AtomicInteger count = new AtomicInteger(0);
        final long createdAt = System.currentTimeMillis();
    }

    // Nonce management for Digest authentication (RFC 7616 section 3.3).
    // Both maps are bounded by opportunistic TTL eviction -- see
    // evictExpiredIfNeeded() -- rather than left to grow without limit.
    // cnonces uses a ConcurrentHashMap keyed by cnonce+nc (the value is
    // just the insertion time, for eviction) instead of a synchronized
    // HashSet, so replay checks for different cnonces no longer
    // contend on one global lock across every connection this provider
    // instance serves.
    private final Map<String, NonceEntry> nonces = new ConcurrentHashMap<String, NonceEntry>();
    private final Map<String, Long> cnonces = new ConcurrentHashMap<String, Long>();

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
     * method constants from {@link HttpAuthenticationMethods}.</p>
     * 
     * @return the authentication method (e.g., "BASIC", "DIGEST"), or null if none configured
     */
    protected abstract String getAuthMethod();

    /**
     * Gives this provider the telemetry configuration its events go to,
     * which the listener it is set on does when it starts. A provider
     * used outside a listener has a configuration of its own, which
     * prints events through {@code java.util.logging}.
     */
    synchronized void attach(TelemetryConfig telemetryConfig) {
        if (telemetryConfig != null) {
            this.telemetryConfig = telemetryConfig;
        }
    }

    private synchronized TelemetryConfig telemetry() {
        if (telemetryConfig == null) {
            telemetryConfig = new TelemetryConfig();
        }
        return telemetryConfig;
    }

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
     * @param loop the loop of the connection being authenticated, which a
     *        realm that makes network calls is to run them on
     * @param realm the realm name for credential lookup
     * @param username the username to verify
     * @param password the password to verify
     * @param callback receives {@code true} if the credentials are valid
     */
    protected abstract void passwordMatch(SelectorLoop loop, String realm,
            String username, String password, RealmCallback<Boolean> callback);

    /**
     * Gets the precomputed H(A1) hash for Digest authentication.
     * 
     * <p>For Digest authentication, H(A1) = SHA-256(username:realm:password).
     * Implementations may store this precomputed hash for security, avoiding
     * the need to store plaintext passwords.</p>
     * 
     * @param loop the loop of the connection being authenticated
     * @param realm the realm name
     * @param username the username
     * @param callback receives the H(A1) hash as a lowercase hexadecimal
     *        string, or null if the user doesn't exist
     */
    protected abstract void getDigestHA1(SelectorLoop loop, String realm,
            String username, RealmCallback<String> callback);

    /**
     * Validates a Bearer token for token-based authentication.
     * 
     * <p>Called for Bearer authentication (RFC 6750). Implementations should
     * verify the token's signature, expiration, and associated claims.</p>
     * 
     * @param loop the loop of the connection being authenticated
     * @param token the bearer token to validate
     * @param callback receives a {@link Realm.TokenValidationResult} with
     *        validation outcome, or null if Bearer authentication is not
     *        supported
     */
    protected abstract void validateBearerToken(SelectorLoop loop, String token,
            RealmCallback<Realm.TokenValidationResult> callback);

    /**
     * Validates an OAuth 2.0 access token.
     * 
     * <p>Called for OAuth authentication (RFC 6749). Implementations should
     * verify the token against the authorization server or introspection endpoint.</p>
     * 
     * @param loop the loop of the connection being authenticated
     * @param accessToken the OAuth access token to validate
     * @param callback receives a {@link Realm.TokenValidationResult} with
     *        validation outcome, or null if OAuth authentication is not
     *        supported
     */
    protected abstract void validateOAuthToken(SelectorLoop loop, String accessToken,
            RealmCallback<Realm.TokenValidationResult> callback);

    /**
     * Checks if the underlying Realm supports HTTP Digest authentication.
     * 
     * <p>HTTP Digest authentication requires the Realm to provide the H(A1) hash
     * via {@link #getDigestHA1}. Some Realm implementations 
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

    /** Receives the outcome of {@link #authenticate}. */
    public interface AuthenticationCallback {

        /**
         * Authentication is decided. A failure to reach the realm is a failed
         * {@link AuthenticationResult}, never an exception.
         *
         * @param result the outcome
         */
        void completed(AuthenticationResult result);
    }

    /**
     * Authenticates a request using the Authorization header and request target,
     * without waiting for the realm: the callback is called when the answer is
     * known, on {@code loop} when the realm had to be asked over the network
     * and possibly before this method returns when it did not.
     *
     * <p>This method parses the Authorization header, determines the
     * authentication scheme, and delegates to the appropriate authentication
     * method based on the configured {@link #getAuthMethod()}.</p>
     *
     * <p>For Digest authentication, {@code requestMethod} and {@code digestUri}
     * must be the effective HTTP method and request URI so the response can be
     * bound to the request per RFC 7616.</p>
     *
     * @param loop the loop of the connection being authenticated
     * @param authorizationHeader the Authorization header value
     * @param requestMethod the HTTP request method, or {@code null} for non-Digest schemes
     * @param digestUri the request URI used in Digest H(A2), or {@code null} for non-Digest schemes
     * @param callback receives an {@link AuthenticationResult} indicating
     *        success or failure with details
     */
    public final void authenticate(SelectorLoop loop, String authorizationHeader,
            String requestMethod, String digestUri,
            AuthenticationCallback callback) {
        String authMethod = getAuthMethod();
        if (authMethod == null) {
            callback.completed(AuthenticationResult.failure(L10N.getString("auth.err.no_method_configured")));
            return;
        }

        if (authorizationHeader == null) {
            callback.completed(AuthenticationResult.failure(L10N.getString("auth.err.no_authorization_header")));
            return;
        }

        int spaceIndex = authorizationHeader.indexOf(' ');
        if (spaceIndex < 1) {
            callback.completed(AuthenticationResult.failure(L10N.getString("auth.err.invalid_header_format")));
            return;
        }

        String scheme = authorizationHeader.substring(0, spaceIndex);
        String credentials = authorizationHeader.substring(spaceIndex + 1);

        try {
            // HTTP authentication schemes are case-insensitive per RFC 7235
            switch (authMethod) {
                case HttpAuthenticationMethods.BASIC_AUTH:
                    if ("Basic".equalsIgnoreCase(scheme)) {
                        authenticateBasic(loop, credentials, callback);
                        return;
                    }
                    break;
                case HttpAuthenticationMethods.DIGEST_AUTH:
                    if ("Digest".equalsIgnoreCase(scheme)) {
                        authenticateDigest(loop, credentials, requestMethod, digestUri, callback);
                        return;
                    }
                    break;
                case HttpAuthenticationMethods.BEARER_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        authenticateToken(loop, credentials, "Bearer", callback);
                        return;
                    }
                    break;
                case HttpAuthenticationMethods.OAUTH_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        authenticateToken(loop, credentials, "OAuth", callback);
                        return;
                    }
                    break;
                case HttpAuthenticationMethods.JWT_AUTH:
                    if ("Bearer".equalsIgnoreCase(scheme)) {
                        authenticateToken(loop, credentials, "JWT", callback);
                        return;
                    }
                    break;
            }

            callback.completed(AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.scheme_mismatch"), authMethod, scheme)));

        } catch (Exception e) {
            String message = MessageFormat.format(L10N.getString("auth.err.authentication_failed"), e.getMessage());
            events().warn("auth.err.authentication_failed")
                    .attr("reason", e.getMessage()).thrown(e).emit();
            callback.completed(AuthenticationResult.failure(message));
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
            case HttpAuthenticationMethods.BASIC_AUTH:
                return "Basic realm=\"" + realmName + "\"";

            case HttpAuthenticationMethods.DIGEST_AUTH:
                // Check if the Realm supports Digest authentication
                if (!supportsDigestAuth()) {
                    events().error("auth.err.digest_not_supported_by_realm").emit();
                    return null; // Cannot generate challenge - realm doesn't support Digest
                }
                // RFC 7616: only SHA-256 is offered. MD5 (the RFC's default
                // when algorithm is absent) is never accepted.
                return "Digest realm=\"" + realmName + "\", nonce=\"" + generateNonce()
                        + "\", qop=\"auth\", algorithm=" + DIGEST_ALGORITHM;

            case HttpAuthenticationMethods.BEARER_AUTH:
                return "Bearer realm=\"" + realmName + "\"";

            case HttpAuthenticationMethods.OAUTH_AUTH:
                return "Bearer realm=\"" + realmName + "\", scope=\"read write\"";

            case HttpAuthenticationMethods.JWT_AUTH:
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
            case HttpAuthenticationMethods.BASIC_AUTH:
                return "Basic".equalsIgnoreCase(scheme);
            case HttpAuthenticationMethods.DIGEST_AUTH:
                return "Digest".equalsIgnoreCase(scheme);
            case HttpAuthenticationMethods.BEARER_AUTH:
            case HttpAuthenticationMethods.OAUTH_AUTH:
            case HttpAuthenticationMethods.JWT_AUTH:
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
                case HttpAuthenticationMethods.BASIC_AUTH:
                    schemes.add("Basic");
                    break;
                case HttpAuthenticationMethods.DIGEST_AUTH:
                    schemes.add("Digest");
                    break;
                case HttpAuthenticationMethods.BEARER_AUTH:
                case HttpAuthenticationMethods.OAUTH_AUTH:
                case HttpAuthenticationMethods.JWT_AUTH:
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
     */
    private void authenticateBasic(SelectorLoop loop, String credentials,
            final AuthenticationCallback callback) {
        final String username;
        String password;
        try {
            byte[] base64UserPass = credentials.getBytes("US-ASCII");
            String userPass = new String(Base64.getDecoder().decode(base64UserPass), "US-ASCII");
            int ci = userPass.indexOf(COLON);
            if (ci < 1) {
                callback.completed(AuthenticationResult.failure(L10N.getString("auth.err.invalid_basic_format")));
                return;
            }
            username = userPass.substring(0, ci);
            password = userPass.substring(ci + 1);
        } catch (Exception e) {
            callback.completed(AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.basic_failed"), e.getMessage())));
            return;
        }
        passwordMatch(loop, getRealmName(), username, password, new RealmCallback<Boolean>() {
            @Override
            public void completed(Boolean matched) {
                if (matched != null && matched.booleanValue()) {
                    callback.completed(AuthenticationResult.success(username, getRealmName(), "Basic"));
                } else {
                    events().warn("auth.warn.auth_failed_for_user").attr("username", username).emit();
                    callback.completed(AuthenticationResult.failure(
                        MessageFormat.format(L10N.getString("auth.err.invalid_credentials"), username)));
                }
            }

            @Override
            public void failed(Throwable cause) {
                callback.completed(AuthenticationResult.failure(
                    MessageFormat.format(L10N.getString("auth.err.basic_failed"), cause.getMessage())));
            }
        });
    }

    /**
     * Authenticates using HTTP Digest authentication (RFC 7616).
     *
     * @param credentials the Digest challenge response parameters
     */
    private void authenticateDigest(SelectorLoop loop, String credentials,
            final String requestMethod, final String digestUri,
            final AuthenticationCallback callback) {
        // Check if the Realm supports Digest authentication
        if (!supportsDigestAuth()) {
            events().error("auth.err.digest_not_supported_by_realm").emit();
            callback.completed(AuthenticationResult.failure("Digest", getRealmName(),
                L10N.getString("auth.err.digest_not_supported_by_realm")));
            return;
        }

        if (requestMethod == null || digestUri == null) {
            callback.completed(AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format")));
            return;
        }

        final Map<String, String> digestResponse;
        try {
            digestResponse = parseDigestResponse(credentials);
        } catch (Exception e) {
            callback.completed(AuthenticationResult.failure(
                MessageFormat.format(L10N.getString("auth.err.digest_failed"), e.getMessage())));
            return;
        }
        final String username = digestResponse.get("username");
        final String realm = digestResponse.get("realm");
        getDigestHA1(loop, realm, username, new RealmCallback<String>() {
            @Override
            public void completed(String ha1Hex) {
                try {
                    callback.completed(verifyDigest(ha1Hex, digestResponse,
                            requestMethod, digestUri));
                } catch (Exception e) {
                    callback.completed(AuthenticationResult.failure(
                        MessageFormat.format(L10N.getString("auth.err.digest_failed"), e.getMessage())));
                }
            }

            @Override
            public void failed(Throwable cause) {
                callback.completed(AuthenticationResult.failure(
                    MessageFormat.format(L10N.getString("auth.err.digest_failed"), cause.getMessage())));
            }
        });
    }

    /** The Digest checks that follow once the realm has supplied H(A1). */
    private AuthenticationResult verifyDigest(String ha1Hex,
            Map<String, String> digestResponse, String requestMethod,
            String digestUri) throws Exception {
        String username = digestResponse.get("username");
        String realm = digestResponse.get("realm");
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
        String responseUri = digestResponse.get("uri");

        if (username == null || realm == null || requestDigest == null || nonce == null || cnonce == null || nc == null) {
            return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
        }

        if (responseUri != null && !responseUri.equals(digestUri)) {
            return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
        }

        // Check nonce
        try {
            int clientNonceCount = Integer.parseInt(nc, 16); // hexadecimal
            int serverNonceCount = getNonceCount(nonce);
            if (clientNonceCount != serverNonceCount || serverNonceCount < 1
                    || !seenCnonce(cnonce + nc)) {
                return AuthenticationResult.failure(L10N.getString("auth.err.nonce_invalid"));
            }
        } catch (Exception e) {
            return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
        }

        // Only the algorithm this server offered is accepted. An absent
        // algorithm means MD5 under RFC 7616 and is refused like any other,
        // as is a response that does not use qop=auth (the RFC 2069
        // fallback has no client nonce and so no replay protection).
        if (!DIGEST_ALGORITHM.equalsIgnoreCase(algorithm) || !"auth".equals(qop)) {
            return AuthenticationResult.failure(L10N.getString("auth.err.invalid_digest_format"));
        }

        // Verify digest response
        if (verifyDigestResponse(ha1Hex, nonce, nc, cnonce,
                requestMethod, digestUri, requestDigest)) {
            return AuthenticationResult.success(username, realm, "Digest");
        }
        events().warn("auth.warn.digest_verification_failed")
                .attr("username", username).emit();
        return AuthenticationResult.failure(
            MessageFormat.format(L10N.getString("auth.err.digest_verification_failed"), username));
    }

    /**
     * Authenticates using a Bearer, OAuth or JWT token (RFC 6750, RFC 6749).
     * The three differ only in which realm hook validates the token and in
     * the wording of their failures.
     *
     * @param token the token
     * @param scheme "Bearer", "OAuth" or "JWT"
     */
    private void authenticateToken(SelectorLoop loop, String token,
            final String scheme, final AuthenticationCallback callback) {
        final String unsupported;
        final String invalid;
        final String expired;
        final String failure;
        if ("OAuth".equals(scheme)) {
            unsupported = L10N.getString("auth.err.oauth_not_supported");
            invalid = L10N.getString("auth.err.invalid_oauth_token");
            expired = L10N.getString("auth.err.oauth_expired");
            failure = L10N.getString("auth.err.oauth_failed");
        } else if ("JWT".equals(scheme)) {
            unsupported = L10N.getString("auth.err.jwt_not_supported");
            invalid = L10N.getString("auth.err.invalid_jwt_token");
            expired = L10N.getString("auth.err.jwt_expired");
            failure = L10N.getString("auth.err.jwt_failed");
        } else {
            unsupported = L10N.getString("auth.err.bearer_not_supported");
            invalid = L10N.getString("auth.err.invalid_bearer_token");
            expired = L10N.getString("auth.err.bearer_expired");
            failure = L10N.getString("auth.err.bearer_failed");
        }
        RealmCallback<Realm.TokenValidationResult> validated =
                new RealmCallback<Realm.TokenValidationResult>() {
            @Override
            public void completed(Realm.TokenValidationResult result) {
                if (result == null) {
                    callback.completed(AuthenticationResult.failure(unsupported));
                } else if (!result.valid) {
                    callback.completed(AuthenticationResult.failure(invalid));
                } else if (result.isExpired()) {
                    callback.completed(AuthenticationResult.failure(expired));
                } else {
                    callback.completed(AuthenticationResult.success(
                            result.username, getRealmName(), scheme));
                }
            }

            @Override
            public void failed(Throwable cause) {
                callback.completed(AuthenticationResult.failure(
                    MessageFormat.format(failure, cause.getMessage())));
            }
        };
        if ("OAuth".equals(scheme)) {
            validateOAuthToken(loop, token, validated);
        } else {
            // For JWT, Bearer validation with JWT-specific checks
            validateBearerToken(loop, token, validated);
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
     * Generates a nonce value for Digest authentication: 24 bytes from a
     * cryptographically strong source, so it cannot be guessed (RFC 7616
     * section 3.3).
     *
     * @return the generated nonce as a hex string
     */
    private String generateNonce() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        String nonce = ByteArrays.toHexString(randomBytes);
        newNonce(nonce);
        return nonce;
    }

    /**
     * Verifies a Digest authentication response against the expected values
     * (RFC 7616 section 3.4.1, algorithm SHA-256, qop=auth).
     * 
     * @param ha1Hex the precomputed H(A1) hash as hex string
     * @param nonce the server-provided nonce
     * @param nc the nonce count as hex string
     * @param cnonce the client nonce
     * @param method the HTTP method
     * @param digestUri the request URI
     * @param requestDigest the client-provided digest response
     * @return true if the digest matches, false otherwise
     * @throws NoSuchAlgorithmException if SHA-256 is not available
     */
    private boolean verifyDigestResponse(String ha1Hex, String nonce,
                                       String nc, String cnonce, String method, String digestUri,
                                       String requestDigest) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(DIGEST_ALGORITHM);

        // H(A2) = H(method:digest-uri)
        md.update(method.getBytes());
        md.update(COLON);
        md.update(digestUri.getBytes());
        String ha2Hex = ByteArrays.toHexString(md.digest());

        // response = H(H(A1):nonce:nc:cnonce:qop:H(A2))
        md.reset();
        md.update(ha1Hex.getBytes());
        md.update(COLON);
        md.update(nonce.getBytes());
        md.update(COLON);
        md.update(nc.getBytes());
        md.update(COLON);
        md.update(cnonce.getBytes());
        md.update(COLON);
        md.update("auth".getBytes());
        md.update(COLON);
        md.update(ha2Hex.getBytes());
        String computed = ByteArrays.toHexString(md.digest());

        return ByteArrays.equalsConstantTime(
                ByteArrays.toByteArray(computed),
                ByteArrays.toByteArray(requestDigest.toLowerCase()));
    }

    /**
     * Registers a new nonce for replay attack prevention.
     *
     * @param nonce the nonce to register
     */
    private void newNonce(String nonce) {
        nonces.put(nonce, new NonceEntry());
        evictExpiredIfNeeded();
    }

    /**
     * Gets and increments the nonce count for replay attack prevention.
     * An expired nonce is treated the same as an unknown one -- the
     * caller (see {@link #authenticateDigest}) responds with a fresh
     * challenge either way.
     *
     * @param nonce the nonce to look up
     * @return the incremented nonce count, or -1 if the nonce is unknown or expired
     */
    private int getNonceCount(String nonce) {
        NonceEntry entry = nonces.get(nonce);
        if (entry == null) {
            return -1;
        }
        if (System.currentTimeMillis() - entry.createdAt > NONCE_TTL_MS) {
            nonces.remove(nonce);
            return -1;
        }
        return entry.count.incrementAndGet();
    }

    /**
     * Checks if a client nonce has been seen before (replay attack prevention).
     *
     * @param cnonce the client nonce concatenated with nonce count
     * @return true if this is a new cnonce, false if it was already seen
     */
    private boolean seenCnonce(String cnonce) {
        boolean isNew = cnonces.putIfAbsent(cnonce, System.currentTimeMillis()) == null;
        evictExpiredIfNeeded();
        return isNew;
    }

    /**
     * Opportunistically sweeps entries older than {@link #NONCE_TTL_MS}
     * out of {@link #nonces} and {@link #cnonces} once their combined
     * size passes {@link #EVICTION_SWEEP_THRESHOLD}, so both stay
     * bounded under sustained Digest traffic without a dedicated
     * background thread.
     */
    private void evictExpiredIfNeeded() {
        if (nonces.size() + cnonces.size() < EVICTION_SWEEP_THRESHOLD) {
            return;
        }
        long cutoff = System.currentTimeMillis() - NONCE_TTL_MS;

        Iterator<Map.Entry<String, NonceEntry>> nonceIterator = nonces.entrySet().iterator();
        while (nonceIterator.hasNext()) {
            Map.Entry<String, NonceEntry> entry = nonceIterator.next();
            if (entry.getValue().createdAt < cutoff) {
                nonceIterator.remove();
            }
        }

        Iterator<Map.Entry<String, Long>> cnonceIterator = cnonces.entrySet().iterator();
        while (cnonceIterator.hasNext()) {
            Map.Entry<String, Long> entry = cnonceIterator.next();
            if (entry.getValue() < cutoff) {
                cnonceIterator.remove();
            }
        }
    }

}
