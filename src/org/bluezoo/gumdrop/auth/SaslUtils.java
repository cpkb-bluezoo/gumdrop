/*
 * SaslUtils.java
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

package org.bluezoo.gumdrop.auth;

import org.bluezoo.util.ByteArrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Subject;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm.CertificateAuthenticationResult;

/**
 * Utility methods for SASL authentication mechanisms.
 * Provides cryptographic helpers shared across POP3, IMAP, and SMTP.
 *
 * <p>The MD5-based mechanisms (CRAM-MD5, DIGEST-MD5) are deliberately not
 * supported. They allow an offline dictionary attack on a captured exchange,
 * need the server to hold the password or a password-equivalent, and have
 * no channel binding; DIGEST-MD5 was moved to Historic by RFC 6331. Use
 * SCRAM-SHA-256, or PLAIN or OAUTHBEARER over TLS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL Framework</a>
 */
public final class SaslUtils {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");

    private static final Charset US_ASCII = StandardCharsets.US_ASCII;
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SaslUtils() {
        // Utility class
    }

    // ========================================================================
    // Encoding/Decoding
    // ========================================================================

    /**
     * RFC 4648 §4 — encodes data to Base64.
     * 
     * @param data the data to encode
     * @return Base64-encoded string
     */
    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * RFC 4648 §4 — encodes a string to Base64 using US-ASCII.
     * 
     * @param data the string to encode
     * @return Base64-encoded string
     */
    public static String encodeBase64(String data) {
        return encodeBase64(data.getBytes(US_ASCII));
    }

    /**
     * RFC 4648 §4 — decodes a Base64 string.
     * 
     * @param encoded the Base64-encoded string
     * @return decoded bytes
     * @throws IllegalArgumentException if the input is not valid Base64
     */
    public static byte[] decodeBase64(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    /**
     * RFC 4648 §4 — decodes a Base64 string to a UTF-8 string.
     * 
     * @param encoded the Base64-encoded string
     * @return decoded string
     * @throws IllegalArgumentException if the input is not valid Base64
     */
    public static String decodeBase64ToString(String encoded) {
        return new String(decodeBase64(encoded), UTF_8);
    }



    // ========================================================================
    // Challenge Generation
    // ========================================================================

    /**
     * RFC 4422 — generates a random nonce for challenge-response authentication.
     * 
     * @param length the number of random bytes
     * @return hex-encoded nonce
     */
    public static String generateNonce(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return ByteArrays.toHexString(bytes);
    }

    /**
     * RFC 5802 §5 / RFC 7677 — generates a SCRAM server-first-message.
     * 
     * @param nonce the combined client+server nonce
     * @param salt the salt (Base64-encoded)
     * @param iterations the iteration count
     * @return the server-first-message
     */
    public static String generateScramServerFirst(String nonce, String salt, int iterations) {
        return "r=" + nonce + ",s=" + salt + ",i=" + iterations;
    }

    /**
     * RFC 5802 §5 — verifies a SCRAM client-final message and computes the
     * server signature.
     *
     * @param creds the user's SCRAM credentials
     * @param authChallenge the auth message ({@code client-first-bare,server-first})
     * @param clientFinal the decoded client-final message
     * @param expectedNonce the combined nonce from the server-first message
     * @return the server signature bytes, or {@code null} if verification fails
     */
    public static byte[] verifyScramClientFinal(Realm.ScramCredentials creds,
            String authChallenge, String clientFinal, String expectedNonce) {
        if (creds == null || authChallenge == null || clientFinal == null
                || expectedNonce == null) {
            return null;
        }
        String nonce = null;
        String proof = null;
        for (String attr : clientFinal.split(",")) {
            if (attr.startsWith("r=")) {
                nonce = attr.substring(2);
            } else if (attr.startsWith("p=")) {
                proof = attr.substring(2);
            }
        }
        if (nonce == null || proof == null || !nonce.equals(expectedNonce)) {
            return null;
        }
        int proofIdx = clientFinal.lastIndexOf(",p=");
        if (proofIdx < 0) {
            return null;
        }
        String clientFinalWithoutProof = clientFinal.substring(0, proofIdx);
        String authMessage = authChallenge + "," + clientFinalWithoutProof;
        byte[] clientSignature = hmacSHA256(creds.storedKey,
                authMessage.getBytes(StandardCharsets.UTF_8));
        byte[] clientProof;
        try {
            clientProof = Base64.getDecoder().decode(proof);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (clientProof.length != clientSignature.length) {
            return null;
        }
        byte[] recoveredClientKey = new byte[clientProof.length];
        for (int i = 0; i < clientProof.length; i++) {
            recoveredClientKey[i] = (byte) (clientProof[i] ^ clientSignature[i]);
        }
        byte[] computedStoredKey = sha256(recoveredClientKey);
        if (!MessageDigest.isEqual(computedStoredKey, creds.storedKey)) {
            return null;
        }
        return hmacSHA256(creds.serverKey,
                authMessage.getBytes(StandardCharsets.UTF_8));
    }

    // ========================================================================
    // Cryptographic Operations
    // ========================================================================

    /**
     * FIPS 180-4 — computes SHA-256 hash.
     * 
     * @param data the data to hash
     * @return SHA-256 digest
     */
    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(L10N.getString("err.no_sha256"), e);
        }
    }

    /**
     * RFC 2104 — computes HMAC-SHA256.
     * 
     * @param key the secret key
     * @param data the data to authenticate
     * @return HMAC-SHA256 value
     */
    public static byte[] hmacSHA256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] k = key.length == 0 ? new byte[1] : key;
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            String msg = MessageFormat.format(L10N.getString("err.sasl_algorithm_failed"), "HMAC-SHA256");
            throw new RuntimeException(msg, e);
        }
    }

    // ========================================================================
    // PLAIN Mechanism Support
    // ========================================================================

    /**
     * RFC 4616 §2 — parses PLAIN credentials.
     * Format: authzid NUL authcid NUL password
     * 
     * @param credentials Base64-decoded credentials
     * @return array of [authzid, authcid, password], authzid may be empty
     * @throws IllegalArgumentException if format is invalid
     */
    public static String[] parsePlainCredentials(byte[] credentials) {
        int firstNull = -1;
        int secondNull = -1;
        
        for (int i = 0; i < credentials.length; i++) {
            if (credentials[i] == 0) {
                if (firstNull < 0) {
                    firstNull = i;
                } else {
                    secondNull = i;
                    break;
                }
            }
        }
        
        if (firstNull < 0 || secondNull < 0) {
            throw new IllegalArgumentException(L10N.getString("err.sasl_plain"));
        }
        
        String authzid = new String(credentials, 0, firstNull, UTF_8);
        String authcid = new String(credentials, firstNull + 1, secondNull - firstNull - 1, UTF_8);
        String password = new String(credentials, secondNull + 1, credentials.length - secondNull - 1, UTF_8);
        
        return new String[] { authzid, authcid, password };
    }

    // ========================================================================
    // OAUTHBEARER Support
    // ========================================================================

    /**
     * RFC 7628 §3.1 — parses OAUTHBEARER credentials.
     * Format: n,a=user@example.com,^Aauth=Bearer token^A^A
     * 
     * @param credentials Base64-decoded credentials
     * @return map with "user" and "token" keys
     * @throws IllegalArgumentException if format is invalid
     */
    public static Map<String, String> parseOAuthBearerCredentials(String credentials) {
        Map<String, String> result = new HashMap<String, String>();

        // RFC 7628 §3.1: the first part (before the first ^A) is the
        // GS2 header with comma-separated fields: gs2-cb-flag, [authzid], ""
        int firstCtrlA = credentials.indexOf('\u0001');
        if (firstCtrlA < 0) {
            return result;
        }
        String gs2Header = credentials.substring(0, firstCtrlA);
        for (String field : gs2Header.split(",", -1)) {
            if (field.startsWith("a=")) {
                result.put("user", field.substring(2));
            }
        }

        // Remaining parts are ^A-separated key=value pairs
        int partStart = firstCtrlA + 1;
        int credLen = credentials.length();
        while (partStart < credLen) {
            int partEnd = credentials.indexOf('\u0001', partStart);
            if (partEnd < 0) {
                partEnd = credLen;
            }
            String part = credentials.substring(partStart, partEnd);
            if (part.startsWith("auth=Bearer ")) {
                result.put("token", part.substring(12));
            }
            partStart = partEnd + 1;
        }

        return result;
    }

    /**
     * RFC 4422 Appendix A — performs SASL EXTERNAL authentication using
     * the peer certificate from the TLS session.
     *
     * <p>This method extracts the client certificate from the endpoint's
     * security info, delegates to the Realm for certificate-to-user
     * mapping, and handles the optional authorization identity (authzid).
     *
     * @param endpoint the TLS endpoint with peer certificates
     * @param realm the realm to authenticate against
     * @param authzid the requested authorization identity, or null
     * @param callback receives the authentication result; a realm that
     *        cannot answer is reported to {@code failed}
     */
    public static void authenticateExternal(Endpoint endpoint, Realm realm,
            final String authzid,
            final RealmCallback<CertificateAuthenticationResult> callback) {
        if (realm == null || !endpoint.isSecure()) {
            callback.completed(CertificateAuthenticationResult.failure());
            return;
        }
        SecurityInfo securityInfo = endpoint.getSecurityInfo();
        if (securityInfo == null) {
            callback.completed(CertificateAuthenticationResult.failure());
            return;
        }
        Certificate[] certs = securityInfo.getPeerCertificates();
        if (certs == null || certs.length == 0
                || !(certs[0] instanceof X509Certificate)) {
            callback.completed(CertificateAuthenticationResult.failure());
            return;
        }

        X509Certificate clientCert = (X509Certificate) certs[0];
        final Realm authRealm = realm;
        realm.authenticateCertificate(clientCert,
                new RealmCallback<CertificateAuthenticationResult>() {
            @Override
            public void completed(final CertificateAuthenticationResult result) {
                if (result == null || !result.valid) {
                    callback.completed(CertificateAuthenticationResult.failure());
                    return;
                }
                if (authzid == null || authzid.isEmpty()) {
                    callback.completed(CertificateAuthenticationResult.success(
                            result.username));
                    return;
                }
                authRealm.authorizeAs(result.username, authzid,
                        new RealmCallback<Boolean>() {
                    @Override
                    public void completed(Boolean allowed) {
                        if (allowed != null && allowed.booleanValue()) {
                            callback.completed(
                                    CertificateAuthenticationResult.success(authzid));
                        } else {
                            callback.completed(
                                    CertificateAuthenticationResult.failure());
                        }
                    }

                    @Override
                    public void failed(Throwable cause) {
                        callback.failed(cause);
                    }
                });
            }

            @Override
            public void failed(Throwable cause) {
                callback.failed(cause);
            }
        });
    }

    /**
     * RFC 7628 §3.1 — builds the OAUTHBEARER initial client response:
     * {@code n,a=<authzid>,^Aauth=Bearer <token>^A^A}. The authzid is
     * escaped per RFC 5801 §4 ({@code =} as {@code =3D}, {@code ,} as
     * {@code =2C}).
     *
     * @param authzid the account identity (e.g. the email address)
     * @param token the OAuth 2.0 access token
     * @return the unencoded initial response
     */
    public static String formatOAuthBearerInitialResponse(String authzid,
                                                          String token) {
        String escaped = authzid.replace("=", "=3D").replace(",", "=2C");
        return "n,a=" + escaped + ",\u0001auth=Bearer " + token
                + "\u0001\u0001";
    }

    /**
     * Builds the Google XOAUTH2 initial client response:
     * {@code user=<account>^Aauth=Bearer <token>^A^A}.
     *
     * @param user the account (email address)
     * @param token the OAuth 2.0 access token
     * @return the unencoded initial response
     * @see <a href="https://developers.google.com/gmail/imap/xoauth2-protocol">XOAUTH2 protocol</a>
     */
    public static String formatXOAuth2InitialResponse(String user,
                                                      String token) {
        return "user=" + user + "\u0001auth=Bearer " + token
                + "\u0001\u0001";
    }

    /**
     * Chooses an OAuth SASL mechanism from a server capability list
     * containing {@code AUTH=<mechanism>} tokens. XOAUTH2 is preferred
     * over OAUTHBEARER.
     *
     * @param capabilities the server capability tokens (may be null)
     * @return {@code "XOAUTH2"}, {@code "OAUTHBEARER"}, or null if neither
     *         is offered
     */
    public static String selectOAuthMechanism(
            java.util.Collection<String> capabilities) {
        if (capabilities == null) {
            return null;
        }
        boolean bearer = false;
        for (String cap : capabilities) {
            if ("AUTH=XOAUTH2".equalsIgnoreCase(cap)) {
                return "XOAUTH2";
            }
            if ("AUTH=OAUTHBEARER".equalsIgnoreCase(cap)) {
                bearer = true;
            }
        }
        return bearer ? "OAUTHBEARER" : null;
    }

    // ========================================================================
    // Client-Side SASL Mechanisms
    // ========================================================================

    /**
     * Creates a client-side SASL mechanism for driving an authentication
     * exchange with a remote server.
     *
     * <p>All implementations are non-blocking and use only gumdrop's own
     * cryptographic primitives, making them safe for the NIO event loop.
     *
     * <p>For GSSAPI, use the overload that accepts a {@code Subject}.
     *
     * @param mechanism the SASL mechanism name (PLAIN, EXTERNAL)
     * @param username the authentication identity
     * @param password the password (may be null for EXTERNAL)
     * @param host the server hostname
     * @return the mechanism, or null if the name is not recognised
     * @see #createClient(String, String, String, String, Subject)
     */
    public static SaslClientMechanism createClient(String mechanism,
                                                   String username,
                                                   String password,
                                                   String host) {
        return createClient(mechanism, username, password, host, null);
    }

    /**
     * Creates a client-side SASL mechanism for driving an authentication
     * exchange with a remote server.
     *
     * <p>All implementations except GSSAPI are non-blocking and safe for
     * the NIO event loop. GSSAPI may block on the first call to
     * {@code evaluateChallenge()} due to KDC contact; callers must
     * offload to a worker thread.
     *
     * @param mechanism the SASL mechanism name (PLAIN, EXTERNAL, GSSAPI,
     *        OAUTHBEARER, XOAUTH2)
     * @param username the authentication identity (the account for
     *        OAUTHBEARER and XOAUTH2)
     * @param password the password (may be null for EXTERNAL/GSSAPI); the
     *        OAuth 2.0 access token for OAUTHBEARER and XOAUTH2
     * @param host the server hostname (used by GSSAPI for the service principal)
     * @param subject the JAAS Subject with Kerberos credentials (required
     *        for GSSAPI, ignored for other mechanisms)
     * @return the mechanism, or null if the name is not recognised
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752">RFC 4752: GSSAPI SASL</a>
     */
    public static SaslClientMechanism createClient(String mechanism,
                                                   String username,
                                                   String password,
                                                   String host,
                                                   Subject subject) {
        if (mechanism == null) {
            return null;
        }
        switch (mechanism.toUpperCase()) {
            case "PLAIN":
                return new PlainClient(username, password);
            case "EXTERNAL":
                return new ExternalClient();
            case "OAUTHBEARER":
                if (username == null || password == null) {
                    return null;
                }
                return new OAuthClient("OAUTHBEARER",
                        formatOAuthBearerInitialResponse(username, password),
                        new byte[] {1});
            case "XOAUTH2":
                if (username == null || password == null) {
                    return null;
                }
                return new OAuthClient("XOAUTH2",
                        formatXOAuth2InitialResponse(username, password),
                        new byte[0]);
            case "GSSAPI":
                if (subject == null || host == null) {
                    return null;
                }
                try {
                    return new GssapiClientMechanism(host, subject);
                } catch (IOException e) {
                    return null;
                }
            default:
                return null;
        }
    }

    // RFC 7628 OAUTHBEARER / Google XOAUTH2: single initial response; if
    // the server answers with an error challenge the client acknowledges it
    // and the server then fails the exchange
    private static final class OAuthClient implements SaslClientMechanism {
        private final String name;
        private final byte[] initial;
        private final byte[] errorAck;
        private boolean sent;
        private boolean complete;

        OAuthClient(String name, String initialResponse, byte[] errorAck) {
            this.name = name;
            this.initial = initialResponse.getBytes(UTF_8);
            this.errorAck = errorAck;
        }

        @Override
        public String getMechanismName() { return name; }

        @Override
        public boolean hasInitialResponse() { return true; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) {
            if (!sent) {
                sent = true;
                return initial;
            }
            complete = true;
            return errorAck;
        }

        @Override
        public boolean isComplete() { return complete; }
    }

    // RFC 4616 — PLAIN: \0authcid\0password (single step)
    private static final class PlainClient implements SaslClientMechanism {
        private final String username;
        private final String password;
        private boolean complete;

        PlainClient(String username, String password) {
            this.username = username;
            this.password = password != null ? password : "";
        }

        @Override
        public String getMechanismName() { return "PLAIN"; }

        @Override
        public boolean hasInitialResponse() { return true; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) {
            complete = true;
            byte[] user = username.getBytes(UTF_8);
            byte[] pass = password.getBytes(UTF_8);
            byte[] response = new byte[1 + user.length + 1 + pass.length];
            // response[0] = 0 (authzid empty)
            System.arraycopy(user, 0, response, 1, user.length);
            // response[1 + user.length] = 0 (separator)
            System.arraycopy(pass, 0, response, 2 + user.length, pass.length);
            return response;
        }

        @Override
        public boolean isComplete() { return complete; }
    }

    // RFC 4422 Appendix A — EXTERNAL: no credentials, relies on TLS cert
    private static final class ExternalClient implements SaslClientMechanism {
        private boolean complete;

        @Override
        public String getMechanismName() { return "EXTERNAL"; }

        @Override
        public boolean hasInitialResponse() { return true; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) {
            complete = true;
            return new byte[0];
        }

        @Override
        public boolean isComplete() { return complete; }
    }

}

