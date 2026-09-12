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

/**
 * QUIC's three packet-protection encryption levels that carry CRYPTO
 * frames (RFC 9001 section 4.1): Initial, Handshake, and 1-RTT
 * (Application). 0-RTT packets carry application data, not handshake
 * messages, and are not part of this enumeration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.1">RFC 9001 section 4.1</a>
 */
public enum EncryptionLevel {

    /** ClientHello and ServerHello are always sent here, unprotected at the TLS layer. */
    INITIAL,

    /** EncryptedExtensions, Certificate, CertificateVerify, and both Finished messages. */
    HANDSHAKE,

    /** Post-handshake messages only (NewSessionTicket); no handshake-completing message is sent here. */
    ONE_RTT

}
