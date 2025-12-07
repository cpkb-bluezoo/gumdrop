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

package org.bluezoo.gumdrop;

/**
 * A realm is a collection of authenticatable principals.
 * These principals have passwords, and may be organised into
 * groups or roles.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Realm {

    /**
     * Verifies that the given password matches the stored credentials for the user.
     * This is the preferred authentication method as it allows realm implementations
     * to use password hashing without exposing plaintext passwords.
     *
     * @param username the username to authenticate
     * @param password the password to verify
     * @return true if the password matches, false if the password is incorrect or user doesn't exist
     */
    boolean passwordMatch(String username, String password);

    /**
     * Computes the H(A1) hash for HTTP Digest Authentication (RFC 2617).
     * H(A1) = MD5(username:realm:password)
     * 
     * This allows realm implementations to either:
     * - Store plaintext passwords and compute H(A1) on demand
     * - Pre-compute and store H(A1) hashes directly (more secure)
     * 
     * @param username the username
     * @param realmName the realm name used in the digest computation
     * @return the H(A1) hash as a lowercase hex string, or null if user doesn't exist
     */
    String getDigestHA1(String username, String realmName);

    /**
     * Returns the password for the given user, or null if the user does not exist.
     * 
     * @deprecated This method exposes plaintext passwords and should be avoided.
     * Use {@link #passwordMatch(String, String)} for simple authentication or
     * {@link #getDigestHA1(String, String)} for HTTP Digest Authentication.
     * 
     * @param username the username
     * @return the plaintext password, or null if the user does not exist
     * @throws UnsupportedOperationException if this realm only supports hashed passwords
     */
    @Deprecated
    String getPassword(String username) throws UnsupportedOperationException;

    /**
     * Indicates whether the specified user has the given role.
     * 
     * <p>This is a standard security concept used for authorization decisions.
     * Role names are defined by the application (e.g., "admin", "user", 
     * "ftp-read", "ftp-write").</p>
     * 
     * @param username the username to check
     * @param role the role name to check for
     * @return true if the user has the specified role, false otherwise
     */
    boolean isUserInRole(String username, String role);

    /**
     * Checks whether a user exists in this realm.
     * This is useful for authentication mechanisms that need to verify user
     * existence without checking a password (e.g., certificate-based auth).
     * 
     * @param username the username to check
     * @return true if the user exists, false otherwise
     */
    default boolean userExists(String username) {
        // Default implementation - try passwordMatch with empty string
        // Subclasses should override if they have a more efficient check
        return false;
    }

    /**
     * Computes the expected CRAM-MD5 response for a user.
     * CRAM-MD5 uses HMAC-MD5(password, challenge) where password is the key.
     * 
     * <p>Implementing this method allows realm implementations to support
     * CRAM-MD5 authentication without exposing the plaintext password.
     * 
     * @param username the username
     * @param challenge the server's challenge string
     * @return the expected HMAC-MD5 digest as lowercase hex, or null if user doesn't exist
     * @throws UnsupportedOperationException if this realm cannot compute CRAM-MD5 responses
     */
    default String getCramMD5Response(String username, String challenge) {
        throw new UnsupportedOperationException("CRAM-MD5 not supported by this realm");
    }

    /**
     * Computes the expected APOP response for a user.
     * APOP uses MD5(timestamp + password).
     * 
     * <p>Implementing this method allows realm implementations to support
     * APOP authentication without exposing the plaintext password.
     * 
     * @param username the username
     * @param timestamp the server's APOP timestamp (e.g., "&lt;1234.5678@hostname&gt;")
     * @return the expected MD5 digest as lowercase hex, or null if user doesn't exist
     * @throws UnsupportedOperationException if this realm cannot compute APOP responses
     */
    default String getApopResponse(String username, String timestamp) {
        throw new UnsupportedOperationException("APOP not supported by this realm");
    }

    /**
     * Gets the SCRAM credentials for a user.
     * 
     * <p>SCRAM (RFC 5802) uses derived keys instead of storing plaintext passwords:
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
     * @return the SCRAM credentials, or null if user doesn't exist
     * @throws UnsupportedOperationException if this realm doesn't support SCRAM
     */
    default ScramCredentials getScramCredentials(String username) {
        throw new UnsupportedOperationException("SCRAM not supported by this realm");
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
                javax.crypto.SecretKeyFactory factory = 
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmac" + algorithm.replace("-", ""));
                javax.crypto.spec.PBEKeySpec spec = 
                    new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256);
                byte[] saltedPassword = factory.generateSecret(spec).getEncoded();

                // Compute ClientKey and StoredKey
                String hmacAlg = "Hmac" + algorithm.replace("-", "");
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance(hmacAlg);
                mac.init(new javax.crypto.spec.SecretKeySpec(saltedPassword, hmacAlg));
                byte[] clientKey = mac.doFinal("Client Key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
                byte[] storedKey = digest.digest(clientKey);

                // Compute ServerKey
                mac.init(new javax.crypto.spec.SecretKeySpec(saltedPassword, hmacAlg));
                byte[] serverKey = mac.doFinal("Server Key".getBytes(java.nio.charset.StandardCharsets.UTF_8));

                String saltBase64 = java.util.Base64.getEncoder().encodeToString(salt);
                return new ScramCredentials(saltBase64, iterations, storedKey, serverKey);
            } catch (Exception e) {
                throw new RuntimeException("Failed to derive SCRAM credentials", e);
            }
        }
    }

    /**
     * Validates a Bearer token and returns the associated principal information.
     * This method is used for Bearer Token Authentication (RFC 6750).
     * 
     * @param token the bearer token to validate
     * @return a TokenValidationResult containing the username, scopes, and validity, or null if token is invalid
     */
    default TokenValidationResult validateBearerToken(String token) {
        return null; // Default implementation returns null (not supported)
    }

    /**
     * Validates an OAuth access token and returns the associated principal information.
     * This method is used for OAuth 2.0 Authentication (RFC 6749).
     * 
     * @param accessToken the OAuth access token to validate
     * @return a TokenValidationResult containing the username, scopes, and validity, or null if token is invalid
     */
    default TokenValidationResult validateOAuthToken(String accessToken) {
        return null; // Default implementation returns null (not supported)
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
                    username, tokenType, java.util.Arrays.toString(scopes));
            } else {
                return "TokenValidationResult{valid=false}";
            }
        }
    }

}
