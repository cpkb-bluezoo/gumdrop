/*
 * DirectionalKeyMaterial.java
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

/**
 * Fixed AEAD key material for one direction of a TLS 1.2 connection (RFC
 * 5246 section 6.3's key block, sliced per direction) -- what
 * {@link Tls12HandshakeEngine} hands to {@link Tls12EventSink#keysReady}
 * for the record layer to stage.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DirectionalKeyMaterial {

    final byte[] key;

    /**
     * The fixed IV: 4 bytes for GCM suites (RFC 5288 section 3's
     * {@code salt}, concatenated with a per-record explicit nonce carried
     * on the wire) or 12 bytes for ChaCha20-Poly1305 (RFC 7905 section 2's
     * full IV, XORed with the sequence number, no wire nonce at all) --
     * see {@link Tls12CipherSuite#getFixedIvLength}.
     */
    final byte[] fixedIv;

    DirectionalKeyMaterial(byte[] key, byte[] fixedIv) {
        this.key = key;
        this.fixedIv = fixedIv;
    }

}
