/*
 * ScramSha256Client.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Client side of SASL SCRAM-SHA-256 (RFC 5802, RFC 7677), without channel
 * binding.
 *
 * <p>Three steps: the client-first message (the initial response), the
 * client-final message with the proof, answering the server-first message,
 * and the check of the server's signature in the server-final message. The
 * only cost worth noting is the PBKDF2 key derivation in the second step,
 * which takes as many iterations as the server asks for (at most
 * {@link #MAX_ITERATIONS}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ScramSha256Client implements SaslClientMechanism {

    /** The most PBKDF2 iterations a server may ask for. */
    static final int MAX_ITERATIONS = 1000000;

    private enum Step { FIRST, FINAL, VERIFY, DONE }

    private final String username;
    private final String password;
    private final String nonce;

    private Step step = Step.FIRST;
    private String clientFirstBare;
    private byte[] expectedSignature;

    ScramSha256Client(String username, String password) {
        this(username, password, SaslUtils.generateNonce(24));
    }

    /** As above, with the client nonce given, for known-answer tests. */
    ScramSha256Client(String username, String password, String nonce) {
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.nonce = nonce;
    }

    @Override
    public String getMechanismName() {
        return "SCRAM-SHA-256";
    }

    @Override
    public boolean hasInitialResponse() {
        return true;
    }

    @Override
    public byte[] evaluateChallenge(byte[] challenge) throws IOException {
        switch (step) {
            case FIRST:
                clientFirstBare = "n=" + saslName(username) + ",r=" + nonce;
                step = Step.FINAL;
                return ("n,," + clientFirstBare).getBytes(StandardCharsets.UTF_8);
            case FINAL:
                return clientFinal(new String(challenge, StandardCharsets.UTF_8));
            case VERIFY:
                verifyServerFinal(new String(challenge, StandardCharsets.UTF_8));
                step = Step.DONE;
                return new byte[0];
            default:
                throw new IOException("SCRAM exchange already complete");
        }
    }

    @Override
    public boolean isComplete() {
        return step == Step.DONE;
    }

    // RFC 5802 section 5.1: "=" and "," in the user name are escaped
    private static String saslName(String name) {
        return name.replace("=", "=3D").replace(",", "=2C");
    }

    private byte[] clientFinal(String serverFirst) throws IOException {
        String serverNonce = null;
        String salt = null;
        int iterations = 0;
        String[] attrs = serverFirst.split(",");
        for (int i = 0; i < attrs.length; i++) {
            String a = attrs[i];
            if (a.startsWith("r=")) {
                serverNonce = a.substring(2);
            } else if (a.startsWith("s=")) {
                salt = a.substring(2);
            } else if (a.startsWith("i=")) {
                try {
                    iterations = Integer.parseInt(a.substring(2));
                } catch (NumberFormatException e) {
                    throw new IOException("SCRAM: bad iteration count");
                }
            } else if (a.startsWith("e=")) {
                throw new IOException("SCRAM: server error " + a.substring(2));
            }
        }
        if (serverNonce == null || salt == null || iterations <= 0) {
            throw new IOException("SCRAM: malformed server-first message");
        }
        if (!serverNonce.startsWith(nonce) || serverNonce.length() == nonce.length()) {
            throw new IOException("SCRAM: server nonce does not extend the client nonce");
        }
        if (iterations > MAX_ITERATIONS) {
            throw new IOException("SCRAM: iteration count too high: " + iterations);
        }
        byte[] saltBytes;
        try {
            saltBytes = Base64.getDecoder().decode(salt);
        } catch (IllegalArgumentException e) {
            throw new IOException("SCRAM: bad salt");
        }

        byte[] salted;
        try {
            salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), saltBytes, iterations, 256))
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("SCRAM: key derivation failed", e);
        }
        byte[] clientKey = hmac(salted, "Client Key");
        byte[] storedKey = SaslUtils.sha256(clientKey);
        String withoutProof = "c=biws,r=" + serverNonce;
        byte[] authMessage = (clientFirstBare + "," + serverFirst + "," + withoutProof)
                .getBytes(StandardCharsets.UTF_8);
        byte[] clientSignature = SaslUtils.hmacSHA256(storedKey, authMessage);
        byte[] proof = new byte[clientKey.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ clientSignature[i]);
        }
        expectedSignature = SaslUtils.hmacSHA256(hmac(salted, "Server Key"), authMessage);
        step = Step.VERIFY;
        return (withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof))
                .getBytes(StandardCharsets.UTF_8);
    }

    private void verifyServerFinal(String serverFinal) throws IOException {
        if (serverFinal.startsWith("e=")) {
            throw new IOException("SCRAM: server error " + serverFinal.substring(2));
        }
        if (!serverFinal.startsWith("v=")) {
            throw new IOException("SCRAM: malformed server-final message");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(serverFinal.substring(2));
        } catch (IllegalArgumentException e) {
            throw new IOException("SCRAM: bad server signature");
        }
        if (!MessageDigest.isEqual(signature, expectedSignature)) {
            throw new IOException("SCRAM: server signature does not match");
        }
    }

    private static byte[] hmac(byte[] key, String text) {
        return SaslUtils.hmacSHA256(key, text.getBytes(StandardCharsets.UTF_8));
    }

}
