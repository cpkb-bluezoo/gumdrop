/*
 * EchClientHelloBuilder.java
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
import java.security.SecureRandom;

import org.bluezoo.gumdrop.crypto.Hpke;

/**
 * Builds RFC 9849 {@code ClientHelloInner} and {@code ClientHelloOuter}
 * for ECH-capable TLS 1.3 clients.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9849#section-6.1">RFC 9849 section 6.1</a>
 */
public final class EchClientHelloBuilder {

    /** AES-GCM authentication tag size for HPKE payload length calculation. */
    private static final int HPKE_AEAD_TAG_LENGTH = 16;

    private EchClientHelloBuilder() {
    }

    /**
     * Result of offering ECH: the wire ClientHello to send and the inner
     * hello to use for the transcript when the server accepts ECH.
     */
    public static final class Offer {
        private final byte[] clientHelloOuterFramed;
        private final byte[] clientHelloInnerFramed;
        private final byte[] clientHelloInnerContent;
        private final byte[] clientHelloOuterRandom;
        private final Hpke.SenderContext hpkeSender;

        Offer(byte[] clientHelloOuterFramed, byte[] clientHelloInnerFramed, byte[] clientHelloInnerContent,
                byte[] clientHelloOuterRandom, Hpke.SenderContext hpkeSender) {
            this.clientHelloOuterFramed = clientHelloOuterFramed;
            this.clientHelloInnerFramed = clientHelloInnerFramed;
            this.clientHelloInnerContent = clientHelloInnerContent;
            this.clientHelloOuterRandom = clientHelloOuterRandom;
            this.hpkeSender = hpkeSender;
        }

        public byte[] getClientHelloOuterFramed() {
            return clientHelloOuterFramed;
        }

        public byte[] getClientHelloInnerFramed() {
            return clientHelloInnerFramed;
        }

        public byte[] getClientHelloInnerContent() {
            return clientHelloInnerContent;
        }

        public byte[] getClientHelloOuterRandom() {
            return clientHelloOuterRandom;
        }

        Hpke.SenderContext getHpkeSender() {
            return hpkeSender;
        }
    }

    /**
     * Builds inner and outer ClientHellos from a standard {@link HandshakeMessages.ClientHelloParams}
     * template (real backend {@code server_name}, key shares, and so on).
     *
     * @param template negotiation parameters shared by inner and outer hellos
     * @param echConfig chosen ECH configuration
     * @param realPskBinder real PSK binder for inner hello, or null if no PSK
     * @param random entropy source
     * @return framed outer hello to transmit and framed inner hello for ECH acceptance
     */
    public static Offer build(HandshakeMessages.ClientHelloParams template, EchConfig echConfig,
            byte[] realPskBinder, SecureRandom random) throws GeneralSecurityException, HandshakeFormatException {
        if (!echConfig.supportsGumdropHpkeProfile()) {
            throw new HandshakeFormatException("ECHConfig is not compatible with gumdrop HPKE profile");
        }

        byte[] innerRandom = new byte[32];
        random.nextBytes(innerRandom);
        byte[] outerRandom = new byte[32];
        random.nextBytes(outerRandom);

        HandshakeMessages.ClientHelloParams inner = copyParams(template);
        inner.random = innerRandom;
        inner.encryptedClientHelloInner = true;
        inner.encryptedClientHelloOuter = null;

        HandshakeMessages.ClientHelloParams outer = copyParams(template);
        outer.random = outerRandom;
        outer.serverName = echConfig.getPublicName();
        outer.legacySessionId = inner.legacySessionId;
        outer.encryptedClientHelloInner = false;

        if (template.pskIdentity != null) {
            HandshakeMessages.GreasePreSharedKey grease = new HandshakeMessages.GreasePreSharedKey();
            grease.identity = randomBytes(random, template.pskIdentity.length);
            grease.obfuscatedTicketAge = random.nextInt();
            grease.binder = randomBytes(random, realPskBinder.length);
            outer.greasePreSharedKey = grease;
            outer.pskIdentity = null;
        }

        byte[] innerContent = HandshakeMessages.buildClientHelloContent(inner, realPskBinder);
        byte[] encodedInner = encodeClientHelloInner(innerContent, echConfig, template.serverName);
        byte[] hpkeInfo = echConfig.hpkeSetupInfo();

        Hpke hpke = Hpke.x25519Aes128Gcm();
        Hpke.SenderContext sender = hpke.setupBaseS(echConfig.getPublicKey(), hpkeInfo, random);
        byte[] enc = sender.getEnc();

        int payloadLength = encodedInner.length + HPKE_AEAD_TAG_LENGTH;
        byte[] zeroPayload = new byte[payloadLength];
        EncryptedClientHello.Outer placeholder = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                echConfig.getConfigId(),
                enc,
                zeroPayload);
        outer.encryptedClientHelloOuter = placeholder;

        byte[] outerAad = HandshakeMessages.buildClientHelloContent(outer, null);
        byte[] ciphertext = sender.seal(outerAad, encodedInner);

        EncryptedClientHello.Outer finalOuter = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                echConfig.getConfigId(),
                enc,
                ciphertext);
        outer.encryptedClientHelloOuter = finalOuter;
        byte[] outerContent = HandshakeMessages.buildClientHelloContent(outer, null);
        byte[] outerFramed = HandshakeMessages.frameClientHello(outerContent);
        byte[] innerFramed = HandshakeMessages.frameClientHello(innerContent);

        return new Offer(outerFramed, innerFramed, innerContent, outerRandom, sender);
    }

    /**
     * Builds the second ClientHelloOuter after HelloRetryRequest (RFC 9849 section 6.1.5).
     */
    public static Offer buildHelloRetryRequest(Hpke.SenderContext hpkeSender, EchConfig echConfig,
            HandshakeMessages.ClientHelloParams template, byte[] firstInnerFramed, byte[] realPskBinder)
            throws GeneralSecurityException, HandshakeFormatException {
        HandshakeMessages.ClientHello firstInner = HandshakeMessages.parseClientHello(firstInnerFramed);

        HandshakeMessages.ClientHelloParams inner = copyParams(template);
        inner.random = firstInner.random;
        inner.encryptedClientHelloInner = true;
        inner.legacySessionId = firstInner.legacySessionId;

        HandshakeMessages.ClientHelloParams outer = copyParams(template);
        outer.random = template.random;
        outer.serverName = echConfig.getPublicName();
        outer.legacySessionId = firstInner.legacySessionId;
        if (template.pskIdentity != null) {
            HandshakeMessages.GreasePreSharedKey grease = new HandshakeMessages.GreasePreSharedKey();
            grease.identity = template.pskIdentity;
            grease.obfuscatedTicketAge = template.obfuscatedTicketAge;
            grease.binder = new byte[realPskBinder != null ? realPskBinder.length : 32];
            outer.greasePreSharedKey = grease;
        }

        byte[] innerContent = HandshakeMessages.buildClientHelloContent(inner, realPskBinder);
        byte[] encodedInner = encodeClientHelloInner(innerContent, echConfig, template.serverName);

        int payloadLength = encodedInner.length + HPKE_AEAD_TAG_LENGTH;
        byte[] zeroPayload = new byte[payloadLength];
        EncryptedClientHello.Outer placeholder = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                echConfig.getConfigId(),
                new byte[0],
                zeroPayload);
        outer.encryptedClientHelloOuter = placeholder;

        byte[] outerAad = HandshakeMessages.buildClientHelloContent(outer, null);
        byte[] ciphertext = hpkeSender.seal(outerAad, encodedInner);

        EncryptedClientHello.Outer finalOuter = new EncryptedClientHello.Outer(
                Hpke.KDF_HKDF_SHA256,
                Hpke.AEAD_AES_128_GCM,
                echConfig.getConfigId(),
                new byte[0],
                ciphertext);
        outer.encryptedClientHelloOuter = finalOuter;
        byte[] outerContent = HandshakeMessages.buildClientHelloContent(outer, null);
        return new Offer(
                HandshakeMessages.frameClientHello(outerContent),
                HandshakeMessages.frameClientHello(innerContent),
                innerContent,
                outer.random,
                hpkeSender);
    }

    private static HandshakeMessages.ClientHelloParams copyParams(HandshakeMessages.ClientHelloParams src) {
        HandshakeMessages.ClientHelloParams copy = new HandshakeMessages.ClientHelloParams();
        copy.random = src.random;
        copy.cipherSuites = src.cipherSuites;
        copy.groups = src.groups;
        copy.keyShares = src.keyShares;
        copy.signatureAlgorithms = src.signatureAlgorithms;
        copy.applicationProtocols = src.applicationProtocols;
        copy.serverName = src.serverName;
        copy.quicTransportParameters = src.quicTransportParameters;
        copy.cookie = src.cookie;
        copy.pskIdentity = src.pskIdentity;
        copy.obfuscatedTicketAge = src.obfuscatedTicketAge;
        copy.earlyDataRequested = src.earlyDataRequested;
        copy.advertiseRecordSizeLimit = src.advertiseRecordSizeLimit;
        copy.recordSizeLimit = src.recordSizeLimit;
        copy.certificateCompressionAlgorithms = src.certificateCompressionAlgorithms;
        copy.legacySessionId = src.legacySessionId;
        return copy;
    }

    /**
     * RFC 9849 section 5.1 and 6.1.3: {@code EncodedClientHelloInner}.
     */
    static byte[] encodeClientHelloInner(byte[] clientHelloContent, EchConfig echConfig, String backendServerName)
            throws HandshakeFormatException {
        byte[] withEmptySession = clientHelloWithLegacySessionId(clientHelloContent, new byte[0]);
        int namePadding = computeNamePadding(echConfig.getMaximumNameLength(), backendServerName);
        int baseLength = withEmptySession.length + namePadding;
        int blockPadding = 31 - ((baseLength - 1) % 32);
        int totalPadding = namePadding + blockPadding;
        byte[] encoded = new byte[withEmptySession.length + totalPadding];
        System.arraycopy(withEmptySession, 0, encoded, 0, withEmptySession.length);
        return encoded;
    }

    private static int computeNamePadding(int maximumNameLength, String backendServerName) {
        if (backendServerName != null && backendServerName.length() > 0) {
            return Math.max(0, maximumNameLength - backendServerName.length());
        }
        return maximumNameLength + 9;
    }

    static byte[] clientHelloWithLegacySessionId(byte[] clientHelloContent, byte[] legacySessionId)
            throws HandshakeFormatException {
        if (clientHelloContent.length < 34) {
            throw new HandshakeFormatException("ClientHello too short");
        }
        byte[] sessionId = legacySessionId != null ? legacySessionId : new byte[0];
        WireReader r = new WireReader(clientHelloContent);
        WireWriter w = new WireWriter();
        w.u16(r.u16());
        w.bytes(r.bytes(32));
        r.opaque8();
        w.opaque8(sessionId);
        w.bytes(r.bytes(r.remaining()));
        return w.toByteArray();
    }

    private static byte[] randomBytes(SecureRandom random, int length) {
        byte[] out = new byte[length];
        random.nextBytes(out);
        return out;
    }
}
