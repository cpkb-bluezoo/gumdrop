/*
 * Dtls13DirectionalKeys.java
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

import org.bluezoo.gumdrop.crypto.Hkdf;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionException;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;

/**
 * One direction's DTLS 1.3 AEAD + header-protection state for one epoch.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Dtls13DirectionalKeys {

    private static final long AES_GCM_CONFIDENTIALITY_LIMIT = 23_726_566L;

    final int epoch;
    long seq;
    private final PacketProtectionKeys keys;
    private final QuicAeadAlgorithm algorithm;

    private Dtls13DirectionalKeys(int epoch, PacketProtectionKeys keys, QuicAeadAlgorithm algorithm) {
        this.epoch = epoch;
        this.keys = keys;
        this.algorithm = algorithm;
    }

    static Dtls13DirectionalKeys fromSecret(CipherSuite suite, byte[] secret, int epoch) {
        Hkdf hkdf = suite.newHkdf();
        QuicAeadAlgorithm algorithm = toAeadAlgorithm(suite);
        PacketProtectionKeys keys = PacketProtectionKeys.deriveForDtls(hkdf, secret, algorithm);
        return new Dtls13DirectionalKeys(epoch, keys, algorithm);
    }

    static QuicAeadAlgorithm toAeadAlgorithm(CipherSuite suite) {
        if (suite == CipherSuite.TLS_AES_128_GCM_SHA256) {
            return QuicAeadAlgorithm.AES_128_GCM;
        }
        if (suite == CipherSuite.TLS_AES_256_GCM_SHA384) {
            return QuicAeadAlgorithm.AES_256_GCM;
        }
        if (suite == CipherSuite.TLS_CHACHA20_POLY1305_SHA256) {
            return QuicAeadAlgorithm.CHACHA20_POLY1305;
        }
        throw new IllegalArgumentException("Unsupported cipher suite: " + suite);
    }

    PacketProtectionKeys getKeys() {
        return keys;
    }

    boolean overConfidentialityLimit() {
        return algorithm != QuicAeadAlgorithm.CHACHA20_POLY1305
                && seq >= AES_GCM_CONFIDENTIALITY_LIMIT;
    }

    byte[] seal(long sequenceNumber, byte[] associatedData, byte[] plaintext) throws PacketProtectionException {
        return PacketProtection.seal(keys, sequenceNumber, associatedData, plaintext);
    }

    byte[] open(long sequenceNumber, byte[] associatedData, byte[] ciphertext) throws PacketProtectionException {
        return PacketProtection.open(keys, sequenceNumber, associatedData, ciphertext);
    }

    byte[] headerProtectionMask(byte[] sample) throws PacketProtectionException {
        return PacketProtection.headerProtectionMask(keys, sample);
    }

    void advance() {
        seq++;
    }
}
