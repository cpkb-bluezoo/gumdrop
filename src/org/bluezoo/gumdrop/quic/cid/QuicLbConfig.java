/*
 * QuicLbConfig.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * One QUIC-LB configuration (draft-ietf-quic-load-balancers-21): the
 * parameters a server needs to encode a routable server ID into every
 * connection ID it issues (section 5.2), and to recognise its own IDs
 * again (sections 5.4 and 5.5). Immutable and thread-safe.
 *
 * <p>The draft is not an RFC and revision 21 has expired, so all wire
 * encoding is kept in this class; a later revision replaces it here.
 *
 * <p>Layout: a first octet carrying the config id in its three most
 * significant bits and either a self-described length or random bits in
 * the other five, then the server ID and nonce. With a key the server ID
 * and nonce are encrypted with AES-128-ECB, in one pass when they total
 * exactly 16 octets (section 5.4.1) and by the four-round Feistel
 * construction otherwise (section 5.4.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicLbConfig {

    /** Highest legal config id; 7 (0b111) is reserved for unroutable IDs. */
    public static final int MAX_CONFIG_ID = 6;

    /** Section 5.3: the nonce must be at least this long. */
    public static final int MIN_NONCE_LENGTH = 4;

    /** Section 5.3: server ID plus nonce must not exceed this many octets. */
    public static final int MAX_PLAINTEXT_LENGTH = 19;

    /** Length of the AES-128 key. */
    public static final int KEY_LENGTH = 16;

    private static final int SINGLE_PASS_LENGTH = 16;
    private static final int ENVOY_MAX_PLAINTEXT_LENGTH = 18;

    private final int configId;
    private final byte[] serverId;
    private final int nonceLength;
    private final byte[] key;
    private final boolean encodesLength;
    private final SecretKeySpec keySpec;

    /**
     * Creates a configuration.
     *
     * @param configId the config id, 0 to {@link #MAX_CONFIG_ID}
     * @param serverId this server's ID; its length is the configured
     *        server ID length
     * @param nonceLength the nonce length in octets, at least
     *        {@link #MIN_NONCE_LENGTH}
     * @param key the 16-octet AES key, or {@code null} for plaintext IDs
     * @param encodesLength whether the first octet self-describes the
     *        connection ID length (section 3.3)
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public QuicLbConfig(int configId, byte[] serverId, int nonceLength, byte[] key, boolean encodesLength) {
        if (configId < 0 || configId > MAX_CONFIG_ID) {
            throw new IllegalArgumentException("QUIC-LB config-id must be 0 to 6: " + configId);
        }
        if (serverId == null || serverId.length < 1) {
            throw new IllegalArgumentException("QUIC-LB server-id must be at least 1 octet");
        }
        if (nonceLength < MIN_NONCE_LENGTH) {
            throw new IllegalArgumentException("QUIC-LB nonce-length must be at least 4");
        }
        if (serverId.length + nonceLength > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException("QUIC-LB server-id-length + nonce-length must not exceed 19");
        }
        if (key != null && key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("QUIC-LB cid-key must be 16 octets");
        }
        this.configId = configId;
        this.serverId = serverId.clone();
        this.nonceLength = nonceLength;
        this.key = key == null ? null : key.clone();
        this.encodesLength = encodesLength;
        this.keySpec = key == null ? null : new SecretKeySpec(key, "AES");
    }

    public int getConfigId() {
        return configId;
    }

    public byte[] getServerId() {
        return serverId.clone();
    }

    public int getNonceLength() {
        return nonceLength;
    }

    public boolean hasKey() {
        return key != null;
    }

    public boolean isEncodesLength() {
        return encodesLength;
    }

    /**
     * Returns the length of every connection ID this configuration
     * produces, including the first octet.
     *
     * @return the connection ID length
     */
    public int getConnectionIdLength() {
        return 1 + serverId.length + nonceLength;
    }

    /**
     * Requires the extra constraints of Envoy's QUIC-LB connection ID
     * generator: the length is always self-encoded and the server ID
     * plus nonce total at most 18 octets.
     *
     * @throws IllegalArgumentException if this configuration does not meet them
     */
    public void requireEnvoyProfile() {
        if (!encodesLength) {
            throw new IllegalArgumentException("Envoy profile requires first-octet-encodes-cid-length");
        }
        if (serverId.length + nonceLength > ENVOY_MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException("Envoy profile requires server-id-length + nonce-length <= 18");
        }
    }

    /**
     * Returns the config id carried in a connection ID's first octet.
     *
     * @param firstOctet the first octet
     * @return the config id, 0 to 7
     */
    public static int configIdOf(byte firstOctet) {
        return (firstOctet & 0xff) >>> 5;
    }

    /**
     * Returns the connection ID length a first octet self-describes
     * (section 3.3).
     *
     * @param firstOctet the first octet
     * @return the total length including the first octet
     */
    public static int selfEncodedLength(byte firstOctet) {
        return (firstOctet & 0x1f) + 1;
    }

    /**
     * Generates a fresh connection ID with a random nonce.
     *
     * @param random the entropy source
     * @return the connection ID
     */
    public byte[] generate(SecureRandom random) {
        byte[] nonce = new byte[nonceLength];
        random.nextBytes(nonce);
        int low = encodesLength ? getConnectionIdLength() - 1 : random.nextInt(32);
        return encode(low, nonce);
    }

    /**
     * Builds a connection ID from an explicit nonce, with the length
     * self-encoded or, when this configuration does not encode it, zero
     * in the low five bits of the first octet.
     *
     * @param nonce the nonce, of the configured length
     * @return the connection ID
     */
    public byte[] encode(byte[] nonce) {
        return encode(encodesLength ? getConnectionIdLength() - 1 : 0, nonce);
    }

    private byte[] encode(int lowBits, byte[] nonce) {
        if (nonce.length != nonceLength) {
            throw new IllegalArgumentException("nonce length");
        }
        byte[] plaintext = new byte[serverId.length + nonceLength];
        System.arraycopy(serverId, 0, plaintext, 0, serverId.length);
        System.arraycopy(nonce, 0, plaintext, serverId.length, nonceLength);
        byte[] body = key == null ? plaintext : encrypt(plaintext);
        byte[] cid = new byte[1 + body.length];
        cid[0] = (byte) ((configId << 5) | (lowBits & 0x1f));
        System.arraycopy(body, 0, cid, 1, body.length);
        return cid;
    }

    /**
     * Extracts the server ID from a connection ID.
     *
     * @param cid the connection ID
     * @return the server ID, or {@code null} if the connection ID does
     *         not use this configuration's config id or length
     */
    public byte[] decodeServerId(byte[] cid) {
        if (cid == null || cid.length != getConnectionIdLength() || configIdOf(cid[0]) != configId) {
            return null;
        }
        if (encodesLength && selfEncodedLength(cid[0]) != cid.length) {
            return null;
        }
        byte[] body = Arrays.copyOfRange(cid, 1, cid.length);
        if (key != null) {
            body = decrypt(body);
        }
        return Arrays.copyOfRange(body, 0, serverId.length);
    }

    /**
     * Returns whether a connection ID decodes to this configuration's
     * own server ID.
     *
     * @param cid the connection ID
     * @return true if it is routed to this server
     */
    public boolean isOwn(byte[] cid) {
        byte[] decoded = decodeServerId(cid);
        return decoded != null && Arrays.equals(decoded, serverId);
    }

    // ── Encryption (sections 5.4 and 5.5) ──

    private byte[] encrypt(byte[] plaintext) {
        int len = plaintext.length;
        if (len == SINGLE_PASS_LENGTH) {
            return aes(Cipher.ENCRYPT_MODE, plaintext);
        }
        int half = (len + 1) / 2;
        boolean odd = (len & 1) != 0;
        byte[] left = split(plaintext, half, odd, true);
        byte[] right = split(plaintext, half, odd, false);
        right = round(left, right, len, 1, half, odd, false);
        left = round(right, left, len, 2, half, odd, true);
        right = round(left, right, len, 3, half, odd, false);
        left = round(right, left, len, 4, half, odd, true);
        return merge(left, right, len, half, odd);
    }

    private byte[] decrypt(byte[] ciphertext) {
        int len = ciphertext.length;
        if (len == SINGLE_PASS_LENGTH) {
            return aes(Cipher.DECRYPT_MODE, ciphertext);
        }
        int half = (len + 1) / 2;
        boolean odd = (len & 1) != 0;
        byte[] left = split(ciphertext, half, odd, true);
        byte[] right = split(ciphertext, half, odd, false);
        left = round(right, left, len, 4, half, odd, true);
        right = round(left, right, len, 3, half, odd, false);
        left = round(right, left, len, 2, half, odd, true);
        right = round(left, right, len, 1, half, odd, false);
        return merge(left, right, len, half, odd);
    }

    /**
     * One Feistel round: XORs the truncated AES of the expanded
     * {@code source} into {@code target}, clearing the shared nibble
     * when the length is odd.
     */
    private byte[] round(byte[] source, byte[] target, int len, int pass, int half, boolean odd,
            boolean targetIsLeft) {
        byte[] block = new byte[16];
        System.arraycopy(source, 0, block, 0, half);
        block[14] = (byte) len;
        block[15] = (byte) pass;
        byte[] mask = aes(Cipher.ENCRYPT_MODE, block);
        byte[] result = new byte[half];
        for (int i = 0; i < half; i++) {
            result[i] = (byte) (target[i] ^ mask[i]);
        }
        if (odd) {
            clearNibble(result, half, targetIsLeft);
        }
        return result;
    }

    private static void clearNibble(byte[] half, int halfLen, boolean left) {
        if (left) {
            half[halfLen - 1] &= (byte) 0xf0;
        } else {
            half[0] &= (byte) 0x0f;
        }
    }

    private static byte[] split(byte[] data, int half, boolean odd, boolean left) {
        byte[] out;
        if (left) {
            out = Arrays.copyOfRange(data, 0, half);
        } else {
            out = Arrays.copyOfRange(data, data.length - half, data.length);
        }
        if (odd) {
            clearNibble(out, half, left);
        }
        return out;
    }

    private static byte[] merge(byte[] left, byte[] right, int len, int half, boolean odd) {
        byte[] out = new byte[len];
        System.arraycopy(left, 0, out, 0, half);
        int rightStart = len - half;
        for (int i = 0; i < half; i++) {
            out[rightStart + i] |= right[i];
        }
        return out;
    }

    private byte[] aes(int mode, byte[] block) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(mode, keySpec);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
