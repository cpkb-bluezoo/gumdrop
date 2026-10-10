/*
 * Realm.java
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

package org.bluezoo.gumdrop.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import org.bluezoo.gumdrop.SelectorLoop;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A realm is a collection of authenticatable principals.
 * These principals have passwords, and may be organised into
 * groups or roles.
 *
 * <h3>Asynchronous by design</h3>
 * <p>Every operation that looks something up about a principal is
 * asynchronous: it takes a {@link RealmCallback} and returns immediately.
 * A realm backed by a directory server or a token service has to talk to
 * the network to answer, and a server must never make a
 * {@link org.bluezoo.gumdrop.SelectorLoop SelectorLoop} thread wait while it
 * does. A protocol handler calls the realm, records that it is waiting, and
 * carries on when the callback fires.
 *
 * <p>The callback is invoked exactly once. A realm bound to a loop with
 * {@link #forSelectorLoop(SelectorLoop)} delivers it on that loop's thread,
 * so the handler may touch its connection state directly. A realm whose
 * answer is already in memory (see {@link SynchronousRealm}) may invoke it
 * before the method returns, so a handler must record that it is waiting
 * <em>before</em> calling the realm, never after.
 *
 * <p>The application must not block a loop thread waiting for a realm. The
 * Servlet API is the one place that needs a blocking answer; the servlet
 * container bridges it on its own worker threads.
 *
 * <p>Realm implementations declare which SASL mechanisms they support
 * via {@link #getSupportedSASLMechanisms()}. Servers should query this
 * method and only advertise mechanisms that the configured realm supports.
 *
 * <h3>SelectorLoop Affinity</h3>
 * <p>Realms that make client connections (for example LDAP) run them on the
 * loop of the connection being authenticated. A server obtains a realm bound
 * to that loop with {@link #forSelectorLoop(SelectorLoop)} before using it:</p>
 *
 * <pre>{@code
 * Realm bound = configuredRealm.forSelectorLoop(endpoint.getSelectorLoop());
 * bound.passwordMatch(user, password, new RealmCallback<Boolean>() {
 *     public void completed(Boolean matched) {
 *         // on the connection's loop thread
 *     }
 *     public void failed(Throwable cause) {
 *         // the realm could not answer
 *     }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RealmCallback
 * @see SynchronousRealm
 * @see SaslMechanism
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL Framework</a>
 */
public interface Realm {

    /**
     * Returns a Realm instance bound to the specified SelectorLoop.
     *
     * <p>Callbacks of the returned realm are delivered on that loop's
     * thread, and any client connection the realm makes (to an LDAP server,
     * a token service) runs on that loop. A realm with no need of a loop may
     * return {@code this}.
     *
     * @param loop the SelectorLoop to bind to
     * @return a realm instance bound to the specified loop
     */
    Realm forSelectorLoop(SelectorLoop loop);

    /**
     * RFC 4422 — returns the set of SASL mechanisms this realm supports.
     *
     * <p>Servers should call this method when building their capability
     * response (e.g., IMAP CAPABILITY, SMTP EHLO AUTH, POP3 CAPA SASL)
     * and only advertise mechanisms that the realm supports.
     *
     * <p>The relationship between mechanisms and realm methods:
     * <ul>
     *   <li>{@link SaslMechanism#PLAIN}, {@link SaslMechanism#LOGIN} -
     *       require {@link #passwordMatch}</li>
     *   <li>{@link SaslMechanism#CRAM_MD5} -
     *       requires {@link #getCramMD5Response}</li>
     *   <li>{@link SaslMechanism#DIGEST_MD5} -
     *       requires {@link #getDigestHA1}</li>
     *   <li>{@link SaslMechanism#SCRAM_SHA_256} -
     *       requires {@link #getScramCredentials}</li>
     *   <li>{@link SaslMechanism#EXTERNAL} -
     *       requires {@link #authenticateCertificate}</li>
     *   <li>{@link SaslMechanism#OAUTHBEARER} -
     *       requires {@link #validateBearerToken}</li>
     * </ul>
     *
     * @return an unmodifiable set of supported SASL mechanisms
     */
    Set<SaslMechanism> getSupportedSASLMechanisms();

    /**
     * RFC 4616 — verifies that the given password matches the stored credentials
     * for the user (used by PLAIN and LOGIN mechanisms).
     *
     * @param username the username to authenticate
     * @param password the password to verify
     * @param callback receives {@code true} if the password matches and
     *        {@code false} if it is incorrect or the user does not exist
     */
    void passwordMatch(String username, String password,
                       RealmCallback<Boolean> callback);

    /**
     * RFC 2617 / RFC 2831 — computes the H(A1) hash for HTTP Digest / DIGEST-MD5.
     * H(A1) = MD5(username:realm:password)
     *
     * <p>This allows realm implementations to either store plaintext
     * passwords and compute H(A1) on demand, or pre-compute and store H(A1)
     * hashes directly (more secure).
     *
     * @param username the username
     * @param realmName the realm name used in the digest computation
     * @param callback receives the H(A1) hash as a lowercase hex string, or
     *        null if the user does not exist or the realm cannot supply it
     */
    void getDigestHA1(String username, String realmName,
                      RealmCallback<String> callback);

    /**
     * Indicates whether the specified user has the given role.
     *
     * <p>This is a standard security concept used for authorization decisions.
     * Role names are defined by the application (e.g., "admin", "user",
     * "ftp-read", "ftp-write").
     *
     * @param username the username to check
     * @param role the role name to check for
     * @param callback receives {@code true} if the user has the role
     */
    void isUserInRole(String username, String role,
                      RealmCallback<Boolean> callback);

    /**
     * Checks whether a user exists in this realm. This is useful for
     * authentication mechanisms that need to verify user existence without
     * checking a password (e.g., certificate-based auth).
     *
     * @param username the username to check
     * @param callback receives {@code true} if the user exists. The default
     *        implementation reports {@code false}.
     */
    default void userExists(String username, RealmCallback<Boolean> callback) {
        callback.completed(Boolean.FALSE);
    }

    /**
     * RFC 2195 — computes the expected CRAM-MD5 response for a user.
     * CRAM-MD5 uses HMAC-MD5(password, challenge) where password is the key.
     *
     * <p>Implementing this method allows realm implementations to support
     * CRAM-MD5 authentication without exposing the plaintext password.
     *
     * @param username the username
     * @param challenge the server's challenge string
     * @param callback receives the expected HMAC-MD5 digest as lowercase
     *        hex, or null if the user does not exist. The default
     *        implementation fails with {@link UnsupportedOperationException}.
     */
    default void getCramMD5Response(String username, String challenge,
                                    RealmCallback<String> callback) {
        callback.failed(new UnsupportedOperationException(
                "CRAM-MD5 not supported by this realm"));
    }

    /**
     * RFC 1939 — computes the expected APOP response for a user.
     * APOP uses MD5(timestamp + password).
     *
     * <p>Implementing this method allows realm implementations to support
     * APOP authentication without exposing the plaintext password.
     *
     * @param username the username
     * @param timestamp the server's APOP timestamp (e.g., "&lt;1234.5678@hostname&gt;")
     * @param callback receives the expected MD5 digest as lowercase hex, or
     *        null if the user does not exist. The default implementation
     *        fails with {@link UnsupportedOperationException}.
     */
    default void getApopResponse(String username, String timestamp,
                                 RealmCallback<String> callback) {
        callback.failed(new UnsupportedOperationException(
                "APOP not supported by this realm"));
    }

    /**
     * RFC 5802 / RFC 7677 — gets the SCRAM credentials for a user.
     * SCRAM uses derived keys instead of storing plaintext passwords:
     * <ul>
     *   <li>SaltedPassword = PBKDF2(password, salt, iterations)</li>
     *   <li>ClientKey = HMAC(SaltedPassword, "Client Key")</li>
     *   <li>StoredKey = H(ClientKey)</li>
     *   <li>ServerKey = HMAC(SaltedPassword, "Server Key")</li>
     * </ul>
     *
     * <p>The realm stores StoredKey and ServerKey, never the password.
     *
     * @param username the username
     * @param callback receives the SCRAM credentials, or null if the user
     *        does not exist. The default implementation fails with
     *        {@link UnsupportedOperationException}.
     */
    default void getScramCredentials(String username,
                                     RealmCallback<ScramCredentials> callback) {
        callback.failed(new UnsupportedOperationException(
                "SCRAM not supported by this realm"));
    }

    /**
     * SCRAM credentials for a user (RFC 5802).
     * These are derived from the password and can be stored without exposing it.
     */
    public static class ScramCredentials {
        /** The salt used for PBKDF2 key derivation (Base64 encoded) */
        public final String salt;
        /** The number of PBKDF2 iterations */
        public final int iterations;
        /** StoredKey = H(HMAC(SaltedPassword, "Client Key")) */
        public final byte[] storedKey;
        /** ServerKey = HMAC(SaltedPassword, "Server Key") */
        public final byte[] serverKey;

        public ScramCredentials(String salt, int iterations, byte[] storedKey, byte[] serverKey) {
            this.salt = salt;
            this.iterations = iterations;
            this.storedKey = storedKey;
            this.serverKey = serverKey;
        }

        /**
         * Computes SCRAM credentials from a plaintext password.
         * Use this when initially setting up a user's credentials.
         * 
         * @param password the plaintext password
         * @param salt the salt (raw bytes)
         * @param iterations the PBKDF2 iteration count (minimum 4096 recommended)
         * @param algorithm "SHA-256" or "SHA-1"
         * @return the derived SCRAM credentials
         */
        public static ScramCredentials derive(String password, byte[] salt, int iterations, String algorithm) {
            try {
                // Derive SaltedPassword using PBKDF2
                SecretKeyFactory factory = 
                    SecretKeyFactory.getInstance("PBKDF2WithHmac" + algorithm.replace("-", ""));
                PBEKeySpec spec = 
                    new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
                byte[] saltedPassword = factory.generateSecret(spec).getEncoded();

                // Compute ClientKey and StoredKey
                String hmacAlg = "Hmac" + algorithm.replace("-", "");
                Mac mac = Mac.getInstance(hmacAlg);
                mac.init(new SecretKeySpec(saltedPassword, hmacAlg));
                byte[] clientKey = mac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
                
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                byte[] storedKey = digest.digest(clientKey);

                // Compute ServerKey
                mac.init(new SecretKeySpec(saltedPassword, hmacAlg));
                byte[] serverKey = mac.doFinal("Server Key".getBytes(StandardCharsets.UTF_8));

                String saltBase64 = Base64.getEncoder().encodeToString(salt);
                return new ScramCredentials(saltBase64, iterations, storedKey, serverKey);
            } catch (Exception e) {
                throw new RuntimeException("Failed to derive SCRAM credentials", e);
            }
        }
    }

    /**
     * RFC 6750 §2.1 / RFC 7628 — validates a Bearer token and returns the
     * associated principal information.
     *
     * @param token the bearer token to validate
     * @param callback receives a TokenValidationResult containing the
     *        username, scopes, and validity, or null if bearer tokens are
     *        not supported. The default implementation reports null.
     */
    default void validateBearerToken(String token,
            RealmCallback<TokenValidationResult> callback) {
        callback.completed(null);
    }

    /**
     * RFC 6749 — validates an OAuth access token and returns the associated
     * principal information.
     *
     * @param accessToken the OAuth access token to validate
     * @param callback receives a TokenValidationResult containing the
     *        username, scopes, and validity, or null if OAuth tokens are
     *        not supported. The default implementation reports null.
     */
    default void validateOAuthToken(String accessToken,
            RealmCallback<TokenValidationResult> callback) {
        callback.completed(null);
    }

    /**
     * RFC 4422 Appendix A — authenticates a client certificate and returns
     * the associated principal identity (SASL EXTERNAL). The Realm implementation
     * determines how to map a certificate to a username -- for example,
     * {@link BasicRealm} matches by SHA-256 fingerprint, while an LDAP
     * realm might search by the {@code userCertificate} attribute.
     *
     * <p>Certificate chain validation (trust) is handled by the TLS
     * layer. This method performs <em>authorization</em>: determining
     * whether a trusted certificate corresponds to a known user.
     *
     * @param certificate the client's X.509 certificate
     * @param callback receives a result indicating success (with username)
     *        or failure, or null if certificate authentication is not
     *        supported. The default implementation reports null.
     */
    default void authenticateCertificate(X509Certificate certificate,
            RealmCallback<CertificateAuthenticationResult> callback) {
        callback.completed(null);
    }

    /**
     * RFC 4422 §4.2 — checks whether an authenticated user is authorized
     * to act as another identity (proxy authorization).
     *
     * <p>The default implementation only allows a user to act as
     * themselves. Realm implementations may override this to support
     * admin impersonation, aliasing, or delegation.
     *
     * @param authenticatedUser the identity established by authentication
     * @param requestedUser the identity the client wants to act as
     * @param callback receives {@code true} if the authenticated user may
     *        act as the requested user
     */
    default void authorizeAs(String authenticatedUser, String requestedUser,
                             RealmCallback<Boolean> callback) {
        callback.completed(Boolean.valueOf(
                authenticatedUser.equals(requestedUser)));
    }

    /**
     * RFC 4752 §3.1 — maps a GSS-API principal name to a local username.
     *
     * <p>After GSSAPI authentication succeeds, the GSS context provides
     * the client's principal name (e.g. "user@EXAMPLE.COM"). This method
     * maps it to a local username that the Realm recognises.
     *
     * <p>The default implementation strips the Kerberos realm portion
     * (everything after {@code @}). Realm implementations may override
     * this for custom principal-to-username mapping (e.g., LDAP lookup
     * by Kerberos principal).
     *
     * @param gssName the GSS-API principal name
     * @param callback receives the local username, or null if the
     *        principal cannot be mapped
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752#section-3.1">
     *      RFC 4752 §3.1</a>
     */
    default void mapKerberosPrincipal(String gssName,
                                      RealmCallback<String> callback) {
        if (gssName == null) {
            callback.completed(null);
            return;
        }
        int atIndex = gssName.indexOf('@');
        if (atIndex > 0) {
            callback.completed(gssName.substring(0, atIndex));
            return;
        }
        callback.completed(gssName);
    }

    /**
     * Result of certificate-based authentication.
     *
     * @see #authenticateCertificate(X509Certificate, RealmCallback)
     */
    public static class CertificateAuthenticationResult {
        /** Whether the certificate was successfully authenticated. */
        public final boolean valid;
        /** The authenticated username, or null if authentication failed. */
        public final String username;

        public static CertificateAuthenticationResult success(
                String username) {
            return new CertificateAuthenticationResult(true, username);
        }

        public static CertificateAuthenticationResult failure() {
            return new CertificateAuthenticationResult(false, null);
        }

        private CertificateAuthenticationResult(boolean valid,
                                                 String username) {
            this.valid = valid;
            this.username = username;
        }

        @Override
        public String toString() {
            if (valid) {
                return "CertificateAuthenticationResult{valid=true, "
                        + "username='" + username + "'}";
            }
            return "CertificateAuthenticationResult{valid=false}";
        }
    }

    /**
     * Result of token validation containing principal and scope information.
     */
    public static class TokenValidationResult {
        public final boolean valid;
        public final String username;
        public final String[] scopes;
        public final long expirationTime; // Unix timestamp, 0 if no expiration
        public final String tokenType; // "Bearer", "JWT", etc.

        /**
         * Creates a successful token validation result.
         */
        public static TokenValidationResult success(String username, String[] scopes, String tokenType) {
            return new TokenValidationResult(true, username, scopes, 0, tokenType);
        }

        /**
         * Creates a successful token validation result with expiration.
         */
        public static TokenValidationResult success(String username, String[] scopes, String tokenType, long expirationTime) {
            return new TokenValidationResult(true, username, scopes, expirationTime, tokenType);
        }

        /**
         * Creates a failed token validation result.
         */
        public static TokenValidationResult failure() {
            return new TokenValidationResult(false, null, null, 0, null);
        }

        private TokenValidationResult(boolean valid, String username, String[] scopes, long expirationTime, String tokenType) {
            this.valid = valid;
            this.username = username;
            this.scopes = scopes;
            this.expirationTime = expirationTime;
            this.tokenType = tokenType;
        }

        /**
         * Check if the token has expired.
         */
        public boolean isExpired() {
            return expirationTime > 0 && System.currentTimeMillis() / 1000 > expirationTime;
        }

        /**
         * Check if the token includes a specific scope.
         */
        public boolean hasScope(String scope) {
            if (scopes == null) {
                return false;
            }
            for (String s : scopes) {
                if (scope.equals(s)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            if (valid) {
                return String.format("TokenValidationResult{valid=true, username='%s', tokenType='%s', scopes=%s}", 
                    username, tokenType, Arrays.toString(scopes));
            } else {
                return "TokenValidationResult{valid=false}";
            }
        }
    }

}

