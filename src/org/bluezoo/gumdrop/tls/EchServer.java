/*
 * EchServer.java
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

import java.security.GeneralSecurityException;
import java.util.Arrays;

import org.bluezoo.gumdrop.crypto.Hpke;

/**
 * Server-side ECH decryption (RFC 9849 section 7).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EchServer {

    private EchServer() {
    }

    /**
     * Opens the outer ClientHello and returns a framed {@code ClientHelloInner}.
     *
     * @param framedClientHelloOuter complete outer ClientHello handshake message
     * @param config ECH configuration matching {@code config_id}
     * @param recipientPrivateKey 32-byte X25519 private key for {@code config}
     */
    public static byte[] openInnerClientHello(byte[] framedClientHelloOuter, EchConfig config,
            byte[] recipientPrivateKey) throws GeneralSecurityException, HandshakeFormatException {
        HandshakeMessages.ClientHello outer = HandshakeMessages.parseClientHello(framedClientHelloOuter);
        if (outer.encryptedClientHelloOuter == null) {
            throw new HandshakeFormatException("ClientHello is not an ECH outer hello");
        }
        EncryptedClientHello.Outer ech = outer.encryptedClientHelloOuter;
        if (ech.configId != config.getConfigId()) {
            throw new HandshakeFormatException("ECH config_id mismatch");
        }
        byte[] outerContent = HandshakeMessages.extractClientHelloContent(framedClientHelloOuter);
        byte[] aad = EncryptedClientHello.clientHelloOuterAadWithZeroEchPayload(
                outerContent, ech.payload.length);
        byte[] encodedInner = decryptPayload(config, recipientPrivateKey, ech, aad);
        byte[] innerContent = reconstructClientHelloContent(encodedInner, outer.legacySessionId);
        return HandshakeMessages.frameClientHello(innerContent);
    }

    private static byte[] decryptPayload(EchConfig config, byte[] recipientPrivateKey,
            EncryptedClientHello.Outer ech, byte[] clientHelloOuterAad)
            throws GeneralSecurityException, HandshakeFormatException {
        Hpke hpke = Hpke.x25519Aes128Gcm();
        Hpke.RecipientContext recipient = hpke.setupBaseR(
                ech.enc, recipientPrivateKey, config.getPublicKey(), config.hpkeSetupInfo());
        return recipient.open(clientHelloOuterAad, ech.payload);
    }

    /**
     * Strips {@code EncodedClientHelloInner} padding and restores
     * {@code legacy_session_id} from the outer hello (RFC 9849 section 5.1).
     */
    static byte[] reconstructClientHelloContent(byte[] encodedInner, byte[] legacySessionIdFromOuter)
            throws HandshakeFormatException {
        int end = encodedInner.length;
        while (end > 0 && encodedInner[end - 1] == 0) {
            end--;
        }
        if (end < 34) {
            throw new HandshakeFormatException("EncodedClientHelloInner too short");
        }
        byte[] hello = Arrays.copyOfRange(encodedInner, 0, end);
        return EchClientHelloBuilder.clientHelloWithLegacySessionId(hello, legacySessionIdFromOuter);
    }
}
