/*
 * Prf.java
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
 * The TLS 1.2 PRF (RFC 5246 section 5): an iterative HMAC construction,
 * {@code P_hash(secret, seed) = HMAC(secret, A(1) || seed) ||
 * HMAC(secret, A(2) || seed) || ...} where {@code A(0) = seed} and
 * {@code A(i) = HMAC(secret, A(i-1))}, truncated to the requested length.
 * {@code PRF(secret, label, seed) = P_hash(secret, label + seed)}.
 *
 * <p>Structurally distinct from {@link Hkdf} (TLS 1.3's HKDF-Expand-Label)
 * -- this is the older, plain-HMAC-iteration key schedule TLS 1.2 uses
 * instead, not a variant of HKDF.
 *
 * <p>Each instance caches one {@link Mac} per thread and re-{@code init}s
 * it per {@link #compute} call instead of calling {@code Mac.getInstance}
 * on every HMAC iteration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5246#section-5">RFC 5246 section 5</a>
 */
public final class Prf {

    private final String macAlgorithm;
    private final ThreadLocal<Mac> macHolder;

    private Prf(String macAlgorithm) {
        this.macAlgorithm = macAlgorithm;
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
     * Returns a PRF instance for SHA-256, used by every TLS 1.2 cipher
     * suite this engine offers except the AES-256-GCM pair.
     *
     * @return a PRF instance backed by HMAC-SHA-256
     */
    public static Prf sha256() {
        return new Prf("HmacSHA256");
    }

    /**
     * Returns a PRF instance for SHA-384, used by the AES-256-GCM cipher
     * suites (RFC 5289).
     *
     * @return a PRF instance backed by HMAC-SHA-384
     */
    public static Prf sha384() {
        return new Prf("HmacSHA384");
    }

    /**
     * Returns the PRF instance matching a cipher suite's own digest
     * algorithm name (e.g. {@link Tls12CipherSuite#getPrfHashAlgorithm}).
     *
     * @param digestAlgorithm {@code "SHA-256"} or {@code "SHA-384"}
     * @return the matching PRF instance
     */
    public static Prf forDigest(String digestAlgorithm) {
        if ("SHA-384".equals(digestAlgorithm)) {
            return sha384();
        }
        return sha256();
    }

    /**
     * {@code PRF(secret, label, seed)} (RFC 5246 section 5).
     *
     * @param secret the secret to expand from
     * @param label the ASCII label, e.g. {@code "master secret"}
     * @param seed the seed bytes
     * @param length the length in bytes of output keying material
     * @return the output keying material, {@code length} bytes
     */
    public byte[] compute(byte[] secret, String label, byte[] seed, int length) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        byte[] labelAndSeed = new byte[labelBytes.length + seed.length];
        System.arraycopy(labelBytes, 0, labelAndSeed, 0, labelBytes.length);
        System.arraycopy(seed, 0, labelAndSeed, labelBytes.length, seed.length);
        return pHash(secret, labelAndSeed, length);
    }

    private byte[] pHash(byte[] secret, byte[] seed, int length) {
        Mac mac = macForKey(secret);
        byte[] output = new byte[length];
        int written = 0;
        byte[] a = seed;
        while (written < length) {
            a = mac.doFinal(a);
            mac = macForKey(secret);
            mac.update(a);
            byte[] block = mac.doFinal(seed);
            int n = Math.min(block.length, length - written);
            System.arraycopy(block, 0, output, written, n);
            written += n;
        }
        return output;
    }

    private Mac macForKey(byte[] key) {
        Mac mac = macHolder.get();
        try {
            mac.init(new SecretKeySpec(key, macAlgorithm));
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC key", e);
        }
        return mac;
    }

}
