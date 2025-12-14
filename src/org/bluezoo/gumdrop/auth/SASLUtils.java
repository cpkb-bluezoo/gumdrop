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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods for SASL authentication mechanisms.
 * Provides cryptographic helpers shared across POP3, IMAP, and SMTP.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SASLUtils {

    private static final Charset US_ASCII = StandardCharsets.US_ASCII;
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SASLUtils() {
        // Utility class
    }

    // ========================================================================
    // Encoding/Decoding
    // ========================================================================

    /**
     * Encodes data to Base64.
     * 
     * @param data the data to encode
     * @return Base64-encoded string
     */
    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * Encodes a string to Base64 using US-ASCII.
     * 
     * @param data the string to encode
     * @return Base64-encoded string
     */
    public static String encodeBase64(String data) {
        return encodeBase64(data.getBytes(US_ASCII));
    }

    /**
     * Decodes a Base64 string.
     * 
     * @param encoded the Base64-encoded string
     * @return decoded bytes
     * @throws IllegalArgumentException if the input is not valid Base64
     */
    public static byte[] decodeBase64(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    /**
     * Decodes a Base64 string to a UTF-8 string.
     * 
     * @param encoded the Base64-encoded string
     * @return decoded string
     * @throws IllegalArgumentException if the input is not valid Base64
     */
    public static String decodeBase64ToString(String encoded) {
        return new String(decodeBase64(encoded), UTF_8);
    }

    /**
     * Converts bytes to hexadecimal string.
     * 
     * @param bytes the bytes to convert
     * @return lowercase hexadecimal string
     */
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Converts hexadecimal string to bytes.
     * 
     * @param hex the hexadecimal string
     * @return decoded bytes
     * @throws IllegalArgumentException if the input is not valid hex
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ========================================================================
    // Challenge Generation
    // ========================================================================

    /**
     * Generates a random nonce for challenge-response authentication.
     * 
     * @param length the number of random bytes
     * @return hex-encoded nonce
     */
    public static String generateNonce(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    /**
     * Generates a CRAM-MD5 challenge string.
     * 
     * @param hostname the server hostname
     * @return the challenge string (ready for Base64 encoding)
     */
    public static String generateCramMD5Challenge(String hostname) {
        long timestamp = System.currentTimeMillis();
        int pid = (int) ProcessHandle.current().pid();
        return "<" + timestamp + "." + pid + "@" + hostname + ">";
    }

    /**
     * Generates a DIGEST-MD5 challenge.
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
     * Generates a SCRAM server-first-message.
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
     * Computes MD5 hash.
     * 
     * @param data the data to hash
     * @return MD5 digest
     */
    public static byte[] md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Computes MD5 hash as hex string.
     * 
     * @param data the data to hash
     * @return hex-encoded MD5 digest
     */
    public static String md5Hex(byte[] data) {
        return bytesToHex(md5(data));
    }

    /**
     * Computes SHA-256 hash.
     * 
     * @param data the data to hash
     * @return SHA-256 digest
     */
    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Computes HMAC-MD5.
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
            throw new RuntimeException("HMAC-MD5 failed", e);
        }
    }

    /**
     * Computes HMAC-SHA256.
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
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    // ========================================================================
    // CRAM-MD5 Support
    // ========================================================================

    /**
     * Computes the expected CRAM-MD5 response.
     * 
     * @param password the user's password
     * @param challenge the server's challenge
     * @return the expected HMAC-MD5 digest as hex
     */
    public static String computeCramMD5Response(String password, String challenge) {
        byte[] hmac = hmacMD5(password.getBytes(UTF_8), challenge.getBytes(US_ASCII));
        return bytesToHex(hmac);
    }

    /**
     * Verifies a CRAM-MD5 response.
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
     * Parses DIGEST-MD5 response parameters.
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
     * Computes DIGEST-MD5 HA1 value.
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
     * Parses PLAIN credentials.
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
            throw new IllegalArgumentException("Invalid PLAIN credentials format");
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
     * Parses OAUTHBEARER credentials.
     * Format: n,a=user@example.com,^Aauth=Bearer token^A^A
     * 
     * @param credentials Base64-decoded credentials
     * @return map with "user" and "token" keys
     * @throws IllegalArgumentException if format is invalid
     */
    public static Map<String, String> parseOAuthBearerCredentials(String credentials) {
        Map<String, String> result = new HashMap<>();
        
        // Split on ^A (0x01)
        String[] parts = credentials.split("\u0001");
        
        for (String part : parts) {
            if (part.startsWith("a=")) {
                result.put("user", part.substring(2));
            } else if (part.startsWith("auth=Bearer ")) {
                result.put("token", part.substring(12));
            }
        }
        
        return result;
    }

}

