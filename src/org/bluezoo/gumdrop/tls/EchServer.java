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

    /**
     * Result of opening a ClientHelloOuter, including HPKE state for
     * HelloRetryRequest follow-ups (RFC 9849 section 7.1.1).
     */
    public static final class OpenResult {
        private final byte[] innerClientHelloFramed;
        private final Hpke.RecipientContext hpkeRecipient;

        OpenResult(byte[] innerClientHelloFramed, Hpke.RecipientContext hpkeRecipient) {
            this.innerClientHelloFramed = innerClientHelloFramed;
            this.hpkeRecipient = hpkeRecipient;
        }

        public byte[] getInnerClientHelloFramed() {
            return innerClientHelloFramed;
        }

        public Hpke.RecipientContext getHpkeRecipient() {
            return hpkeRecipient;
        }
    }

    private EchServer() {
    }

    /**
     * Opens the outer ClientHello and returns a framed {@code ClientHelloInner}.
     *
     * @param framedClientHelloOuter complete outer ClientHello handshake message
     * @param config ECH configuration matching {@code config_id}
     * @param recipientPrivateKey 32-byte X25519 private key for {@code config}
     * @param retryRecipient prior HPKE context after HelloRetryRequest, or null
     */
    public static OpenResult openInnerClientHello(byte[] framedClientHelloOuter, EchConfig config,
            byte[] recipientPrivateKey, Hpke.RecipientContext retryRecipient)
            throws GeneralSecurityException, HandshakeFormatException {
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
        byte[] encodedInner;
        Hpke.RecipientContext recipient;
        if (ech.enc.length == 0) {
            if (retryRecipient == null) {
                throw new HandshakeFormatException("Missing HPKE context for ECH HelloRetryRequest follow-up");
            }
            // RFC 9849 section 6.1.5: the second flight repeats the first
            // flight's HPKE ciphersuite.
            if (ech.kdfId != retryRecipient.getKdfId() || ech.aeadId != retryRecipient.getAeadId()) {
                throw new HandshakeFormatException("ECH HPKE ciphersuite changed across HelloRetryRequest");
            }
            encodedInner = retryRecipient.open(aad, ech.payload);
            recipient = retryRecipient;
        } else {
            // RFC 9849 section 7: a ciphersuite the config did not advertise
            // cannot be decrypted, which the caller handles as any other
            // decryption failure.
            if (!Hpke.isSupported(config.getKemId(), ech.kdfId, ech.aeadId)
                    || !config.advertisesCipherSuite(ech.kdfId, ech.aeadId)) {
                throw new GeneralSecurityException("Client HPKE ciphersuite not advertised by ECHConfig");
            }
            Hpke hpke = Hpke.x25519HkdfSha256(ech.aeadId);
            recipient = hpke.setupBaseR(ech.enc, recipientPrivateKey, config.getPublicKey(), config.hpkeSetupInfo());
            encodedInner = recipient.open(aad, ech.payload);
        }
        byte[] innerContent = reconstructClientHelloContent(encodedInner, outer.legacySessionId);
        byte[] innerFramed = HandshakeMessages.frameClientHello(innerContent);
        return new OpenResult(innerFramed, recipient);
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
