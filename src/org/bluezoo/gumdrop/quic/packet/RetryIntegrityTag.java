/*
 * RetryIntegrityTag.java
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

package org.bluezoo.gumdrop.quic.packet;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Retry Integrity Tag (RFC 9001 section 5.8): a 16-byte AEAD
 * authentication tag appended to every Retry packet (RFC 9000 section
 * 17.2.5), computed with a fixed, publicly known key and nonce -- unlike
 * every other QUIC AEAD use, this is not a secrecy mechanism (the key is
 * public), only a way to let a client detect a corrupted or off-path-forged
 * Retry packet before acting on it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.8">RFC 9001 section 5.8</a>
 */
public final class RetryIntegrityTag {

    /** RFC 9001 section 5.8: the fixed 128-bit AEAD_AES_128_GCM key. */
    private static final byte[] KEY = {
        (byte) 0xbe, (byte) 0x0c, (byte) 0x69, (byte) 0x0b,
        (byte) 0x9f, (byte) 0x66, (byte) 0x57, (byte) 0x5a,
        (byte) 0x1d, (byte) 0x76, (byte) 0x6b, (byte) 0x54,
        (byte) 0xe3, (byte) 0x68, (byte) 0xc8, (byte) 0x4e
    };

    /** RFC 9001 section 5.8: the fixed 96-bit nonce. */
    private static final byte[] NONCE = {
        (byte) 0x46, (byte) 0x15, (byte) 0x99, (byte) 0xd3,
        (byte) 0x5d, (byte) 0x63, (byte) 0x2b, (byte) 0xf2,
        (byte) 0x23, (byte) 0x98, (byte) 0x25, (byte) 0xbb
    };

    private static final SecretKeySpec SECRET_KEY = new SecretKeySpec(KEY, "AES");

    /** The tag length in bytes (RFC 9000 section 17.2.5.1). */
    public static final int LENGTH = 16;

    private RetryIntegrityTag() {
    }

    /**
     * Computes the Retry Integrity Tag for a Retry packet.
     *
     * @param originalDestinationConnectionId the Destination Connection
     *        ID from the client's Initial packet the Retry is responding to
     * @param retryPacketWithoutTag the Retry packet as it will be sent,
     *        minus the tag itself (RFC 9000 section 17.2's header through
     *        the end of the Retry Token field)
     * @return the 16-byte tag
     */
    public static byte[] compute(byte[] originalDestinationConnectionId, byte[] retryPacketWithoutTag) {
        // RFC 9001 section 5.8: the AEAD's associated data is a
        // pseudo-packet that is never itself transmitted -- the actual
        // Retry packet (minus the tag), with the Original Destination
        // Connection ID's length and bytes prepended.
        byte[] pseudoPacket = new byte[1 + originalDestinationConnectionId.length + retryPacketWithoutTag.length];
        pseudoPacket[0] = (byte) originalDestinationConnectionId.length;
        System.arraycopy(originalDestinationConnectionId, 0, pseudoPacket, 1, originalDestinationConnectionId.length);
        System.arraycopy(retryPacketWithoutTag, 0, pseudoPacket,
                1 + originalDestinationConnectionId.length, retryPacketWithoutTag.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(LENGTH * 8, NONCE);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, spec);
            cipher.updateAAD(pseudoPacket);
            // RFC 9001 section 5.8: "The plaintext, P, is empty" -- with
            // an empty plaintext, AEAD_AES_128_GCM's output is exactly
            // the 16-byte tag and nothing else.
            return cipher.doFinal();
        } catch (GeneralSecurityException e) {
            // KEY/NONCE/LENGTH are fixed, valid constants; a real JCE
            // provider never fails this call.
            throw new IllegalStateException("Retry Integrity Tag computation failed", e);
        }
    }

    /**
     * Verifies a received Retry packet's integrity tag.
     *
     * @param originalDestinationConnectionId the Destination Connection
     *        ID this endpoint used in the Initial packet the Retry is a
     *        response to
     * @param retryPacketWithoutTag the received Retry packet, minus its
     *        trailing 16-byte tag
     * @param receivedTag the received 16-byte tag
     * @return true if the tag is valid
     */
    public static boolean verify(byte[] originalDestinationConnectionId, byte[] retryPacketWithoutTag,
            byte[] receivedTag) {
        if (receivedTag.length != LENGTH) {
            return false;
        }
        byte[] expected = compute(originalDestinationConnectionId, retryPacketWithoutTag);
        // Constant-time comparison: this tag is attacker-observable data
        // (arrives on the wire), so timing differences in a naive
        // byte-by-byte compare-and-return-early could leak which prefix
        // bytes matched.
        int diff = 0;
        for (int i = 0; i < LENGTH; i++) {
            diff |= expected[i] ^ receivedTag[i];
        }
        return diff == 0;
    }
}
