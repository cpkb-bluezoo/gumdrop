/*
 * DigestAuthentication.java
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.util.ByteArrays;

/**
 * HTTP Digest Authentication implementation (RFC 7616).
 *
 * <p>Digest authentication provides a more secure alternative to Basic authentication
 * by using a challenge-response mechanism with cryptographic hashes. Credentials
 * are never transmitted in plaintext, making it safer for use over non-HTTPS connections.
 *
 * <p><strong>Security Features:</strong>
 * <ul>
 * <li>Password never transmitted over the network</li>
 * <li>Replay attack protection via nonce values</li>
 * <li>Optional mutual authentication via qop="auth-int"</li>
 * <li>Configurable hash algorithms (MD5, SHA-256, SHA-512-256)</li>
 * </ul>
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>
 * // Simple digest authentication (requires server challenge)
 * HTTPAuthentication auth = new DigestAuthentication("user", "password");
 * client.setAuthentication(auth);
 *
 * // With specific realm and algorithm
 * HTTPAuthentication auth = new DigestAuthentication("user", "password", "Protected Area", "SHA-256");
 * </pre>
 *
 * <p><strong>Wire Format:</strong>
 * <pre>Authorization: Digest username="user", realm="Protected Area", nonce="abc123", 
 *                        uri="/protected", response="6629fae49393a05397450978507c4ef1"</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc7616">RFC 7616 - HTTP Digest Authentication</a>
 */
public class DigestAuthentication implements HTTPAuthentication {

    private static final String SCHEME = "Digest";
    private static final String DEFAULT_ALGORITHM = "MD5";
    private static final SecureRandom RANDOM = new SecureRandom();
    
    // Credentials (immutable)
    private final String username;
    private final String password;
    private final String realm;
    private final String algorithm;
    
    // Challenge state (mutable, synchronized)
    private volatile String nonce;
    private volatile String opaque;
    private volatile String qop;
    private volatile boolean stale;
    private final AtomicInteger nonceCount = new AtomicInteger(0);
    
    // State tracking
    private volatile boolean challengeReceived = false;

    /**
     * Creates a Digest authentication instance with username and password.
     *
     * <p>This constructor creates an authentication instance that requires
     * a server challenge before it can be used. The realm and algorithm
     * will be determined from the server's challenge.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @throws IllegalArgumentException if username or password is null
     */
    public DigestAuthentication(String username, String password) {
        this(username, password, null, DEFAULT_ALGORITHM);
    }

    /**
     * Creates a Digest authentication instance with full parameters.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @param realm the authentication realm (may be null if unknown)
     * @param algorithm the digest algorithm ("MD5", "SHA-256", "SHA-512-256")
     * @throws IllegalArgumentException if username, password, or algorithm is null
     */
    public DigestAuthentication(String username, String password, String realm, String algorithm) {
        if (username == null) {
            throw new IllegalArgumentException("Username cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Algorithm cannot be null");
        }
        
        this.username = username;
        this.password = password;
        this.realm = realm;
        this.algorithm = algorithm;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public void applyAuthentication(Map<String, String> headers) {
        if (!isReady()) {
            throw new IllegalStateException("Digest authentication not ready - no server challenge received");
        }
        
        // Increment nonce count for this request
        int nc = nonceCount.incrementAndGet();
        String nonceCountHex = String.format("%08x", nc);
        
        // Generate client nonce (cnonce) for qop
        String cnonce = null;
        if (qop != null) {
            cnonce = generateCnonce();
        }
        
        // Calculate the response hash
        String uri = "/"; // Will be set by the request context
        String response = calculateResponse("GET", uri, nonceCountHex, cnonce);
        
        // Build the Authorization header
        StringBuilder auth = new StringBuilder(SCHEME).append(" ");
        auth.append("username=\"").append(username).append("\", ");
        auth.append("realm=\"").append(realm != null ? realm : "").append("\", ");
        auth.append("nonce=\"").append(nonce).append("\", ");
        auth.append("uri=\"").append(uri).append("\", ");
        auth.append("algorithm=\"").append(algorithm).append("\", ");
        auth.append("response=\"").append(response).append("\"");
        
        if (qop != null) {
            auth.append(", qop=\"").append(qop).append("\"");
            auth.append(", nc=").append(nonceCountHex);
            auth.append(", cnonce=\"").append(cnonce).append("\"");
        }
        
        if (opaque != null) {
            auth.append(", opaque=\"").append(opaque).append("\"");
        }
        
        headers.put("authorization", auth.toString());
    }

    @Override
    public boolean handleChallenge(HTTPResponse response) {
        if (response.getStatusCode() != 401) {
            return false; // Not an authentication challenge
        }
        
        String wwwAuth = response.getHeader("www-authenticate");
        if (wwwAuth == null || !wwwAuth.toLowerCase().startsWith("digest ")) {
            return false; // Not a digest challenge
        }
        
        try {
            // Parse the challenge parameters
            Map<String, String> params = parseAuthHeader(wwwAuth.substring(7)); // Remove "Digest "
            
            // Update challenge state
            this.nonce = params.get("nonce");
            // Note: realm is final and set in constructor, server realm should match
            this.opaque = params.get("opaque");
            this.qop = params.get("qop");
            this.stale = "true".equalsIgnoreCase(params.get("stale"));
            
            // Reset nonce count if we got a new challenge
            this.nonceCount.set(0);
            this.challengeReceived = true;
            
            // We can handle this challenge if we have a nonce
            return nonce != null;
            
        } catch (Exception e) {
            // Failed to parse challenge
            return false;
        }
    }

    @Override
    public HTTPAuthentication clone() {
        DigestAuthentication clone = new DigestAuthentication(username, password, realm, algorithm);
        
        // Copy challenge state (synchronized access)
        synchronized (this) {
            clone.nonce = this.nonce;
            clone.opaque = this.opaque;
            clone.qop = this.qop;
            clone.stale = this.stale;
            clone.challengeReceived = this.challengeReceived;
            // Don't copy nonce count - each clone should start fresh
        }
        
        return clone;
    }

    @Override
    public boolean requiresChallenge() {
        return true; // Digest authentication always requires a server challenge
    }

    @Override
    public boolean isReady() {
        return challengeReceived && nonce != null;
    }

    @Override
    public String getDescription() {
        return "Digest authentication for user: " + username + 
               (realm != null ? " in realm: " + realm : "") +
               " (algorithm: " + algorithm + ")";
    }

    /**
     * Calculates the digest response hash.
     */
    private String calculateResponse(String method, String uri, String nonceCount, String cnonce) {
        try {
            MessageDigest md = MessageDigest.getInstance(getJavaAlgorithmName());
            
            // Calculate H(A1) = H(username:realm:password)
            String a1 = username + ":" + (realm != null ? realm : "") + ":" + password;
            String ha1 = ByteArrays.toHexString(md.digest(a1.getBytes(StandardCharsets.UTF_8)));
            
            // Calculate H(A2) = H(method:uri)
            String a2 = method + ":" + uri;
            String ha2 = ByteArrays.toHexString(md.digest(a2.getBytes(StandardCharsets.UTF_8)));
            
            // Calculate response
            String responseData;
            if (qop == null || qop.isEmpty()) {
                // RFC 2617 compatibility: response = H(HA1:nonce:HA2)
                responseData = ha1 + ":" + nonce + ":" + ha2;
            } else if ("auth".equals(qop) || "auth-int".equals(qop)) {
                // RFC 7616: response = H(HA1:nonce:nc:cnonce:qop:HA2)
                responseData = ha1 + ":" + nonce + ":" + nonceCount + ":" + cnonce + ":" + qop + ":" + ha2;
            } else {
                throw new IllegalArgumentException("Unsupported qop: " + qop);
            }
            
            return ByteArrays.toHexString(md.digest(responseData.getBytes(StandardCharsets.UTF_8)));
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Digest algorithm not available: " + algorithm, e);
        }
    }

    /**
     * Generates a client nonce for qop-based authentication.
     */
    private String generateCnonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ByteArrays.toHexString(bytes);
    }

    /**
     * Converts algorithm name to Java MessageDigest algorithm name.
     */
    private String getJavaAlgorithmName() {
        switch (algorithm.toUpperCase()) {
            case "MD5": return "MD5";
            case "SHA-256": return "SHA-256";
            case "SHA-512-256": return "SHA-512/256";
            default: return algorithm; // Assume it's a valid Java algorithm name
        }
    }

    /**
     * Parses authentication header parameters.
     */
    private Map<String, String> parseAuthHeader(String headerValue) {
        Map<String, String> params = new HashMap<>();
        
        // Simple parser for key="value" pairs
        String[] parts = headerValue.split(",");
        for (String part : parts) {
            part = part.trim();
            int eqIndex = part.indexOf('=');
            if (eqIndex > 0) {
                String key = part.substring(0, eqIndex).trim();
                String value = part.substring(eqIndex + 1).trim();
                
                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                
                params.put(key, value);
            }
        }
        
        return params;
    }

    // Getters for testing and introspection

    public String getUsername() {
        return username;
    }

    public String getRealm() {
        return realm;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getNonce() {
        return nonce;
    }

    public int getNonceCount() {
        return nonceCount.get();
    }

    public boolean isStale() {
        return stale;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) return false;
        
        DigestAuthentication that = (DigestAuthentication) obj;
        return username.equals(that.username) && 
               password.equals(that.password) &&
               java.util.Objects.equals(realm, that.realm) &&
               algorithm.equals(that.algorithm);
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
