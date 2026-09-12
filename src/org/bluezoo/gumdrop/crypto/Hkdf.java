/*
 * Hkdf.java
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

package org.bluezoo.gumdrop.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF (RFC 5869) and the TLS 1.3 HKDF-Expand-Label construction
 * (RFC 8446 section 7.1) that the TLS 1.3 handshake and record key
 * schedules, and QUIC's key schedule (RFC 9001 section 5.1), are built
 * from.
 *
 * <p>Each instance caches one {@link Mac} per thread ({@link ThreadLocal})
 * and re-{@code init}s it per operation instead of calling
 * {@code Mac.getInstance} on every extract/expand step. Instances may be
 * shared across threads (e.g. {@link org.bluezoo.gumdrop.quic.tls.InitialSecrets})
 * but must not be used reentrantly on one thread before a prior operation
 * on the same instance returns.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5869">RFC 5869</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-7.1">RFC 8446 section 7.1</a>
 */
public final class Hkdf {

    /** The TLS 1.3 label prefix prepended to every HKDF-Expand-Label label (RFC 8446 section 7.1). */
    private static final byte[] TLS13_LABEL_PREFIX = "tls13 ".getBytes(StandardCharsets.US_ASCII);

    /** RFC 9147 section 5.9: DTLS 1.3 uses {@code "dtls13"} with no trailing space. */
    private static final byte[] DTLS13_LABEL_PREFIX = "dtls13".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ZERO_SALT_32 = new byte[32];
    private static final byte[] ZERO_SALT_48 = new byte[48];

    private final String macAlgorithm;
    private final int hashLength;
    private final SecretKeySpec zeroLengthKeySpec;
    private final ThreadLocal<Mac> macHolder;

    /**
     * Creates an HKDF instance bound to a specific hash algorithm.
     *
     * @param macAlgorithm the JCE HMAC algorithm name, e.g. {@code "HmacSHA256"}
     * @param hashLength the output length in bytes of the underlying hash
     *                   (32 for SHA-256, 48 for SHA-384)
     */
    public Hkdf(String macAlgorithm, int hashLength) {
        this.macAlgorithm = macAlgorithm;
        this.hashLength = hashLength;
        this.zeroLengthKeySpec = new SecretKeySpec(new byte[hashLength], macAlgorithm);
        this.macHolder = new ThreadLocal<Mac>() {
            @Override
            protected Mac initialValue() {
                try {
                    return Mac.getInstance(macAlgorithm);
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("HMAC algorithm not available: " + macAlgorithm, e);
                }
            }
        };
    }

    /**
     * Returns an HKDF instance for SHA-256, the hash used for QUIC v1
     * Initial secrets (RFC 9001 section 5.2) and for the
     * {@code TLS_AES_128_GCM_SHA256} and {@code TLS_CHACHA20_POLY1305_SHA256}
     * cipher suites.
     *
     * @return an HKDF instance backed by HMAC-SHA-256
     */
    public static Hkdf sha256() {
        return new Hkdf("HmacSHA256", 32);
    }

    /**
     * Returns an HKDF instance for SHA-384, the hash used for the
     * {@code TLS_AES_256_GCM_SHA384} cipher suite.
     *
     * @return an HKDF instance backed by HMAC-SHA-384
     */
    public static Hkdf sha384() {
        return new Hkdf("HmacSHA384", 48);
    }

    /**
     * Returns the output length in bytes of the underlying hash.
     *
     * @return the hash length
     */
    public int getHashLength() {
        return hashLength;
    }

    /**
     * Returns the RFC 5869 / TLS 1.3 all-zero salt or zero PSK of
     * {@link #getHashLength()} bytes. The returned array is shared and
     * must not be modified.
     *
     * @return the zero salt bytes
     */
    public byte[] zeroSalt() {
        return hashLength == 32 ? ZERO_SALT_32 : ZERO_SALT_48;
    }

    /**
     * HKDF-Extract (RFC 5869 section 2.2): {@code HMAC-Hash(salt, ikm)}.
     *
     * @param salt the salt value (a non-secret random value)
     * @param ikm the input keying material
     * @return the pseudorandom key, {@code hashLength} bytes
     */
    public byte[] extract(byte[] salt, byte[] ikm) {
        Mac mac = macForKey(salt);
        return mac.doFinal(ikm);
    }

    /**
     * Plain HMAC under this instance's algorithm: {@code HMAC-Hash(key, data)}.
     * Used for the TLS 1.3 {@code Finished} message's verify-data
     * (RFC 8446 section 4.4.4), which is an HMAC keyed by a
     * HKDF-Expand-Label-derived {@code finished_key}, not itself an
     * HKDF-Extract/Expand step.
     *
     * @param key the HMAC key
     * @param data the data to authenticate
     * @return the HMAC, {@code hashLength} bytes
     */
    public byte[] hmac(byte[] key, byte[] data) {
        Mac mac = macForKey(key);
        return mac.doFinal(data);
    }

    /**
     * HKDF-Expand (RFC 5869 section 2.3): expands a pseudorandom key into
     * {@code length} bytes of output keying material.
     *
     * @param prk the pseudorandom key, normally the output of {@link #extract}
     * @param info context and application-specific information
     * @param length the length in bytes of output keying material
     * @return the output keying material, {@code length} bytes
     */
    public byte[] expand(byte[] prk, byte[] info, int length) {
        Mac mac = macForKey(prk);
        int n = (length + hashLength - 1) / hashLength;
        byte[] output = new byte[length];
        byte[] previousBlock = new byte[0];
        int written = 0;
        for (int i = 1; i <= n; i++) {
            mac.update(previousBlock);
            mac.update(info);
            mac.update((byte) i);
            byte[] block = mac.doFinal();
            int copyLength = Math.min(hashLength, length - written);
            System.arraycopy(block, 0, output, written, copyLength);
            written += copyLength;
            previousBlock = block;
            if (i < n) {
                mac = macForKey(prk);
            }
        }
        return output;
    }

    /**
     * HKDF-Expand-Label (RFC 8446 section 7.1): the TLS 1.3 key schedule's
     * wrapper around HKDF-Expand, using a length-prefixed
     * {@code "tls13 " + label} and an opaque context.
     *
     * <pre>
     *   struct {
     *       uint16 length = Length;
     *       opaque label&lt;7..255&gt; = "tls13 " + Label;
     *       opaque context&lt;0..255&gt; = Context;
     *   } HkdfLabel;
     * </pre>
     *
     * @param secret the secret to expand from
     * @param label the label, without the {@code "tls13 "} prefix
     *              (e.g. {@code "quic key"})
     * @param context the context octets (empty for QUIC's uses of this function)
     * @param length the length in bytes of output keying material
     * @return the output keying material, {@code length} bytes
     */
    public byte[] expandLabel(byte[] secret, String label, byte[] context, int length) {
        return expandLabelWithPrefix(TLS13_LABEL_PREFIX, secret, label, context, length);
    }

    /**
     * HKDF-Expand-Label with an explicit prefix (RFC 8446 section 7.1 /
     * RFC 9147 section 5.9).
     *
     * @param labelPrefix the prefix bytes, e.g. {@code "tls13 "} or {@code "dtls13"}
     * @param secret the secret to expand from
     * @param label the label without the prefix
     * @param context the context octets
     * @param length the length in bytes of output keying material
     * @return the output keying material
     */
    public byte[] expandLabelWithPrefix(byte[] labelPrefix, byte[] secret, String label, byte[] context,
            int length) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        int fullLabelLength = labelPrefix.length + labelBytes.length;

        byte[] hkdfLabel = new byte[2 + 1 + fullLabelLength + 1 + context.length];
        int pos = 0;
        hkdfLabel[pos++] = (byte) ((length >> 8) & 0xff);
        hkdfLabel[pos++] = (byte) (length & 0xff);
        hkdfLabel[pos++] = (byte) fullLabelLength;
        System.arraycopy(labelPrefix, 0, hkdfLabel, pos, labelPrefix.length);
        pos += labelPrefix.length;
        System.arraycopy(labelBytes, 0, hkdfLabel, pos, labelBytes.length);
        pos += labelBytes.length;
        hkdfLabel[pos++] = (byte) context.length;
        System.arraycopy(context, 0, hkdfLabel, pos, context.length);

        return expand(secret, hkdfLabel, length);
    }

    /**
     * Returns the RFC 9147 {@code "dtls13"} HKDF-Expand-Label prefix bytes.
     *
     * @return the prefix without a trailing space
     */
    public static byte[] dtls13LabelPrefix() {
        return DTLS13_LABEL_PREFIX.clone();
    }

    private Mac macForKey(byte[] key) {
        SecretKeySpec keySpec = key.length == 0 ? zeroLengthKeySpec : new SecretKeySpec(key, macAlgorithm);
        Mac mac = macHolder.get();
        try {
            mac.init(keySpec);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC key", e);
        }
        return mac;
    }
}
