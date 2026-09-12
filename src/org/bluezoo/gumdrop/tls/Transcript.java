/*
 * Transcript.java
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

package org.bluezoo.gumdrop.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The running TLS 1.3 handshake transcript hash (RFC 8446 section 4.4.1):
 * a hash of every handshake message sent and received so far, each
 * message's 4-byte header included, record-layer framing excluded.
 * {@link #hash} may be called at any point to snapshot the hash so far
 * without disturbing the running state, since several key-schedule steps
 * need the transcript hash at a specific point while more messages are
 * still to come.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.4.1">RFC 8446 section 4.4.1</a>
 */
public final class Transcript {

    private static final byte[] EMPTY_HASH_SHA256 = digestEmpty("SHA-256");
    private static final byte[] EMPTY_HASH_SHA384 = digestEmpty("SHA-384");

    private final MessageDigest digest;

    private Transcript(MessageDigest digest) {
        this.digest = digest;
    }

    /**
     * Creates an empty transcript using a cipher suite's hash algorithm.
     *
     * @param suite the negotiated (or, before negotiation, tentatively
     *              assumed) cipher suite
     * @return a fresh transcript
     */
    public static Transcript create(CipherSuite suite) {
        return create(suite.getHashAlgorithm());
    }

    /**
     * Creates an empty transcript using an explicit JCA hash algorithm
     * name -- for callers with no {@link CipherSuite} of their own (e.g.
     * the TLS 1.2 engine's {@code Tls12CipherSuite}) to select the
     * transcript hash a different way.
     *
     * @param hashAlgorithm the JCA digest algorithm name, e.g. {@code "SHA-256"}
     * @return a fresh transcript
     */
    public static Transcript create(String hashAlgorithm) {
        try {
            return new Transcript(MessageDigest.getInstance(hashAlgorithm));
        } catch (NoSuchAlgorithmException e) {
            // Programming error: every JDK bundles SHA-256/SHA-384.
            throw new IllegalStateException("Hash algorithm not available: " + hashAlgorithm, e);
        }
    }

    /**
     * Returns the transcript hash of an empty handshake (RFC 8446
     * section 4.4.1 with no messages appended). The returned array is
     * shared and must not be modified.
     *
     * @param suite the cipher suite whose hash algorithm to use
     * @return the empty transcript hash
     */
    public static byte[] emptyHash(CipherSuite suite) {
        return emptyHash(suite.getHashAlgorithm());
    }

    /**
     * Returns the transcript hash of an empty handshake for a JCA digest
     * algorithm name. The returned array is shared and must not be modified.
     *
     * @param hashAlgorithm the JCA digest algorithm name
     * @return the empty transcript hash
     */
    public static byte[] emptyHash(String hashAlgorithm) {
        return "SHA-384".equals(hashAlgorithm) ? EMPTY_HASH_SHA384 : EMPTY_HASH_SHA256;
    }

    private static byte[] digestEmpty(String hashAlgorithm) {
        try {
            return MessageDigest.getInstance(hashAlgorithm).digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Hash algorithm not available: " + hashAlgorithm, e);
        }
    }

    /**
     * Appends one complete handshake message (its 4-byte
     * {@code msg_type}/{@code length} header plus body) to the running
     * transcript.
     *
     * @param message the complete encoded handshake message
     */
    public void update(byte[] message) {
        digest.update(message);
    }

    /**
     * Snapshots the transcript hash of every message appended so far,
     * without resetting or otherwise disturbing the running digest.
     *
     * @return the transcript hash
     */
    public byte[] hash() {
        try {
            MessageDigest snapshot = (MessageDigest) digest.clone();
            return snapshot.digest();
        } catch (CloneNotSupportedException e) {
            // Every JDK-bundled MessageDigest provider (SUN) supports Cloneable.
            throw new IllegalStateException("Digest implementation is not cloneable", e);
        }
    }

    /**
     * Snapshots what the transcript hash would be if {@code extraBytes}
     * were appended next, without actually appending them -- used to
     * compute a PSK binder's hash over a truncated ClientHello (RFC 8446
     * section 4.2.11.2) that continues a running transcript already
     * holding a HelloRetryRequest, where the real, eventual binder-bearing
     * ClientHello bytes must still be added via {@link #update} once the
     * binder is known, not before.
     *
     * @param extraBytes the bytes to hash as if appended
     * @return the transcript hash including {@code extraBytes}
     */
    public byte[] hashWith(byte[] extraBytes) {
        try {
            MessageDigest snapshot = (MessageDigest) digest.clone();
            snapshot.update(extraBytes);
            return snapshot.digest();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("Digest implementation is not cloneable", e);
        }
    }

    /**
     * RFC 8446 section 4.4.1's {@code message_hash} substitution for a
     * HelloRetryRequest: discards every message appended so far and
     * replaces it with a synthetic single handshake message wrapping the
     * hash of the original ClientHello, {@code message_hash(Hash(ClientHello1))} --
     * handshake type 254, a 3-byte length equal to the hash length, then
     * the hash bytes themselves. Subsequent {@link #update} calls (the
     * HelloRetryRequest itself, then ClientHello2 onward) continue the
     * transcript from this synthetic message rather than from
     * ClientHello1's real bytes.
     *
     * @param clientHello1Hash the hash of the original ClientHello, as
     *        returned by {@link #hash} before this call
     */
    public void retry(byte[] clientHello1Hash) {
        digest.reset();
        digest.update((byte) 254);
        digest.update((byte) 0);
        digest.update((byte) 0);
        digest.update((byte) clientHello1Hash.length);
        digest.update(clientHello1Hash);
    }

}
