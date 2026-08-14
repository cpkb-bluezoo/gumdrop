/*
 * EncryptionLevel.java
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

package org.bluezoo.gumdrop.quic.tls;

import tech.kwik.agent15.ProtectionKeysType;

/**
 * QUIC's three packet-protection encryption levels that carry CRYPTO
 * frames (RFC 9001 section 4.1): Initial, Handshake, and 1-RTT
 * (Application). 0-RTT packets carry application data, not handshake
 * messages, and are not part of this enumeration.
 *
 * <p>Each level corresponds to one of Agent15's
 * {@link ProtectionKeysType} values, used to tell the TLS engine which
 * keys protected a given handshake message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.1">RFC 9001 section 4.1</a>
 */
public enum EncryptionLevel {

    /** ClientHello and ServerHello are always sent here, unprotected at the TLS layer. */
    INITIAL(ProtectionKeysType.None),

    /** EncryptedExtensions, Certificate, CertificateVerify, and both Finished messages. */
    HANDSHAKE(ProtectionKeysType.Handshake),

    /** Post-handshake messages only (NewSessionTicket); no handshake-completing message is sent here. */
    ONE_RTT(ProtectionKeysType.Application);

    private final ProtectionKeysType protectionKeysType;

    EncryptionLevel(ProtectionKeysType protectionKeysType) {
        this.protectionKeysType = protectionKeysType;
    }

    /**
     * Returns the Agent15 {@link ProtectionKeysType} corresponding to
     * this encryption level.
     *
     * @return the protection keys type
     */
    public ProtectionKeysType getProtectionKeysType() {
        return protectionKeysType;
    }
}
