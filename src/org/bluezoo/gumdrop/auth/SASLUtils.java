/*
 * SASLUtils.java
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
import java.util.Map;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.auth.Realm.CertificateAuthenticationResult;

/**
 * Utility methods for SASL authentication mechanisms.
 * Provides cryptographic helpers shared across POP3, IMAP, and SMTP.
 *
 * <p>DIGEST-MD5 (RFC 2831) is deprecated by RFC 6331 and SHOULD NOT be
 * used in new deployments; it is retained here for backward compatibility.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422: SASL Framework</a>
 */
public final class SASLUtils {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");

    private static final Charset US_ASCII = StandardCharsets.US_ASCII;
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SASLUtils() {
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
     * RFC 2195 — generates a CRAM-MD5 challenge string.
     * 
     * @param hostname the server hostname
     * @return the challenge string (ready for Base64 encoding)
     */
    public static String generateCramMD5Challenge(String hostname) {
        long timestamp = System.currentTimeMillis();
        int pid = getProcessId();
        return "<" + timestamp + "." + pid + "@" + hostname + ">";
    }

    /**
     * Gets the current process ID.
     */
    private static int getProcessId() {
        return (int) ProcessHandle.current().pid();
    }

    /**
     * RFC 2831 §2.1 — generates a DIGEST-MD5 challenge.
     * 
     * @param realm the authentication realm
     * @param nonce the nonce value
     * @return the challenge string (ready for Base64 encoding)
     */
    public static String generateDigestMD5Challenge(String realm, String nonce) {
        return "realm=\"" + realm + "\",nonce=\"" + nonce + 
               "\",qop=\"auth\",charset=utf-8,algorithm=md5-sess";
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

    // ========================================================================
    // Cryptographic Operations
    // ========================================================================

    /**
     * RFC 1321 — computes MD5 hash.
     * 
     * @param data the data to hash
     * @return MD5 digest
     */
    public static byte[] md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(L10N.getString("err.no_md5"), e);
        }
    }

    /**
     * RFC 1321 — computes MD5 hash as hex string.
     * 
     * @param data the data to hash
     * @return hex-encoded MD5 digest
     */
    public static String md5Hex(byte[] data) {
        return ByteArrays.toHexString(md5(data));
    }

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
     * RFC 2104 — computes HMAC-MD5.
     * 
     * @param key the secret key
     * @param data the data to authenticate
     * @return HMAC-MD5 value
     */
    public static byte[] hmacMD5(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key, "HmacMD5"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            String msg = MessageFormat.format(L10N.getString("err.sasl_algorithm_failed"), "HMAC-MD5");
            throw new RuntimeException(msg, e);
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
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            String msg = MessageFormat.format(L10N.getString("err.sasl_algorithm_failed"), "HMAC-SHA256");
            throw new RuntimeException(msg, e);
        }
    }

    // ========================================================================
    // CRAM-MD5 Support
    // ========================================================================

    /**
     * RFC 2195 §2 — computes the expected CRAM-MD5 response.
     * 
     * @param password the user's password
     * @param challenge the server's challenge
     * @return the expected HMAC-MD5 digest as hex
     */
    public static String computeCramMD5Response(String password, String challenge) {
        byte[] hmac = hmacMD5(password.getBytes(UTF_8), challenge.getBytes(US_ASCII));
        return ByteArrays.toHexString(hmac);
    }

    /**
     * RFC 2195 §2 — verifies a CRAM-MD5 response.
     * 
     * @param response the response in format "username digest"
     * @param challenge the original challenge
     * @param password the user's password
     * @return true if the response is valid
     */
    public static boolean verifyCramMD5(String response, String challenge, String password) {
        int spaceIndex = response.lastIndexOf(' ');
        if (spaceIndex <= 0) {
            return false;
        }
        String digest = response.substring(spaceIndex + 1).toLowerCase();
        String expected = computeCramMD5Response(password, challenge);
        return MessageDigest.isEqual(digest.getBytes(US_ASCII), expected.getBytes(US_ASCII));
    }

    // ========================================================================
    // DIGEST-MD5 Support
    // ========================================================================

    /**
     * RFC 2831 §2.1 — parses DIGEST-MD5 response parameters.
     * 
     * @param response the response string
     * @return map of parameter names to values
     */
    public static Map<String, String> parseDigestParams(String response) {
        Map<String, String> params = new HashMap<>();
        StringBuilder key = new StringBuilder();
        StringBuilder value = new StringBuilder();
        boolean inQuote = false;
        boolean inValue = false;
        
        for (int i = 0; i < response.length(); i++) {
            char c = response.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    inQuote = false;
                } else if (c == '\\' && i + 1 < response.length()) {
                    value.append(response.charAt(++i));
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '=') {
                inValue = true;
            } else if (c == ',') {
                params.put(key.toString().trim(), value.toString());
                key.setLength(0);
                value.setLength(0);
                inValue = false;
            } else if (inValue) {
                value.append(c);
            } else {
                key.append(c);
            }
        }
        
        if (key.length() > 0) {
            params.put(key.toString().trim(), value.toString());
        }
        
        return params;
    }

    /**
     * RFC 2831 §2.1.1 — computes DIGEST-MD5 HA1 value.
     * 
     * @param username the username
     * @param realm the authentication realm
     * @param password the password
     * @return hex-encoded HA1
     */
    public static String computeDigestHA1(String username, String realm, String password) {
        String a1 = username + ":" + realm + ":" + password;
        return md5Hex(a1.getBytes(UTF_8));
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
     * @return the authentication result
     */
    public static CertificateAuthenticationResult authenticateExternal(
            Endpoint endpoint, Realm realm, String authzid) {
        if (realm == null) {
            return CertificateAuthenticationResult.failure();
        }
        if (!endpoint.isSecure()) {
            return CertificateAuthenticationResult.failure();
        }
        Certificate[] certs =
                endpoint.getSecurityInfo().getPeerCertificates();
        if (certs == null || certs.length == 0) {
            return CertificateAuthenticationResult.failure();
        }
        if (!(certs[0] instanceof X509Certificate)) {
            return CertificateAuthenticationResult.failure();
        }

        X509Certificate clientCert = (X509Certificate) certs[0];
        CertificateAuthenticationResult result =
                realm.authenticateCertificate(clientCert);
        if (result == null || !result.valid) {
            return CertificateAuthenticationResult.failure();
        }

        String targetUser = result.username;
        if (authzid != null && !authzid.isEmpty()) {
            if (!realm.authorizeAs(result.username, authzid)) {
                return CertificateAuthenticationResult.failure();
            }
            targetUser = authzid;
        }
        return CertificateAuthenticationResult.success(targetUser);
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
     * @param mechanism the SASL mechanism name (PLAIN, CRAM-MD5, DIGEST-MD5, EXTERNAL)
     * @param username the authentication identity
     * @param password the password (may be null for EXTERNAL)
     * @param host the server hostname (used by DIGEST-MD5 for digest-uri)
     * @return the mechanism, or null if the name is not recognised
     * @see #createClient(String, String, String, String, javax.security.auth.Subject)
     */
    public static SASLClientMechanism createClient(String mechanism,
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
     * @param mechanism the SASL mechanism name (PLAIN, CRAM-MD5, DIGEST-MD5,
     *        EXTERNAL, GSSAPI)
     * @param username the authentication identity
     * @param password the password (may be null for EXTERNAL/GSSAPI)
     * @param host the server hostname (used by DIGEST-MD5 for digest-uri,
     *        and by GSSAPI for the service principal)
     * @param subject the JAAS Subject with Kerberos credentials (required
     *        for GSSAPI, ignored for other mechanisms)
     * @return the mechanism, or null if the name is not recognised
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4752">RFC 4752: GSSAPI SASL</a>
     */
    public static SASLClientMechanism createClient(String mechanism,
                                                   String username,
                                                   String password,
                                                   String host,
                                                   javax.security.auth.Subject subject) {
        if (mechanism == null) {
            return null;
        }
        switch (mechanism.toUpperCase()) {
            case "PLAIN":
                return new PlainClient(username, password);
            case "CRAM-MD5":
                return new CramMD5Client(username, password);
            case "DIGEST-MD5":
                return new DigestMD5Client(username, password, host);
            case "EXTERNAL":
                return new ExternalClient();
            case "GSSAPI":
                if (subject == null || host == null) {
                    return null;
                }
                try {
                    return new GSSAPIClientMechanism(host, subject);
                } catch (java.io.IOException e) {
                    return null;
                }
            default:
                return null;
        }
    }

    // RFC 4616 — PLAIN: \0authcid\0password (single step)
    private static final class PlainClient implements SASLClientMechanism {
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

    // RFC 2195 — CRAM-MD5: server sends challenge, client returns
    // "username SP HMAC-MD5-hex" (single step after challenge)
    private static final class CramMD5Client implements SASLClientMechanism {
        private final String username;
        private final String password;
        private boolean complete;

        CramMD5Client(String username, String password) {
            this.username = username;
            this.password = password != null ? password : "";
        }

        @Override
        public String getMechanismName() { return "CRAM-MD5"; }

        @Override
        public boolean hasInitialResponse() { return false; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) {
            complete = true;
            String challengeStr = new String(challenge, UTF_8);
            String digest = computeCramMD5Response(password, challengeStr);
            return (username + " " + digest).getBytes(UTF_8);
        }

        @Override
        public boolean isComplete() { return complete; }
    }

    // RFC 2831 — DIGEST-MD5: server sends challenge with realm/nonce,
    // client computes md5-sess response digest.
    private static final class DigestMD5Client implements SASLClientMechanism {
        private final String username;
        private final String password;
        private final String host;
        private boolean complete;
        private int step;

        DigestMD5Client(String username, String password, String host) {
            this.username = username;
            this.password = password != null ? password : "";
            this.host = host;
        }

        @Override
        public String getMechanismName() { return "DIGEST-MD5"; }

        @Override
        public boolean hasInitialResponse() { return false; }

        @Override
        public byte[] evaluateChallenge(byte[] challenge) throws IOException {
            if (step == 0) {
                step = 1;
                complete = true;
                return computeDigestResponse(challenge);
            }
            // Optional rspauth verification step — nothing to send back
            return new byte[0];
        }

        private byte[] computeDigestResponse(byte[] challenge)
                throws IOException {
            Map<String, String> params =
                    parseDigestParams(new String(challenge, UTF_8));

            String realm = params.getOrDefault("realm", "");
            String nonce = params.get("nonce");
            String qop = params.getOrDefault("qop", "auth");
            if (nonce == null) {
                throw new IOException("DIGEST-MD5: missing nonce");
            }

            String cnonce = generateNonce(16);
            String nc = "00000001";
            String digestUri = "ldap/" + host;

            // RFC 2831 §2.1.2.1 — A1 for md5-sess:
            //   H(username:realm:password) : nonce : cnonce
            byte[] h = md5((username + ":" + realm + ":" + password)
                    .getBytes(UTF_8));
            byte[] suffix = (":" + nonce + ":" + cnonce).getBytes(UTF_8);
            byte[] a1 = new byte[h.length + suffix.length];
            System.arraycopy(h, 0, a1, 0, h.length);
            System.arraycopy(suffix, 0, a1, h.length, suffix.length);
            String ha1 = md5Hex(a1);

            String ha2 = md5Hex(
                    ("AUTHENTICATE:" + digestUri).getBytes(UTF_8));

            String responseHash = md5Hex(
                    (ha1 + ":" + nonce + ":" + nc + ":" + cnonce
                            + ":" + qop + ":" + ha2).getBytes(UTF_8));

            String response = "charset=utf-8"
                    + ",username=\"" + username + "\""
                    + ",realm=\"" + realm + "\""
                    + ",nonce=\"" + nonce + "\""
                    + ",nc=" + nc
                    + ",cnonce=\"" + cnonce + "\""
                    + ",digest-uri=\"" + digestUri + "\""
                    + ",response=" + responseHash
                    + ",qop=" + qop;

            return response.getBytes(UTF_8);
        }

        @Override
        public boolean isComplete() { return complete; }
    }

    // RFC 4422 Appendix A — EXTERNAL: no credentials, relies on TLS cert
    private static final class ExternalClient implements SASLClientMechanism {
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

