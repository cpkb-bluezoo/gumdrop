/*
 * EchClientHelloBuilderTest.java
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
import java.util.Collections;

import org.bluezoo.gumdrop.crypto.Hpke;
import org.bluezoo.gumdrop.crypto.NamedGroup;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link EchClientHelloBuilder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EchClientHelloBuilderTest {

    private static final byte[] PK_RM = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
    private static final byte[] SK_RM = hex(
            "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8");

    @Test
    public void buildsDecryptableOuterAndInnerMarkers() throws Exception {
        EchConfig ech = EchConfig.createV13(9, PK_RM, "public.example", 32);
        HandshakeMessages.ClientHelloParams params = sampleParams("backend.example");
        SecureRandom random = new SecureRandom();

        EchClientHelloBuilder.Offer offer = EchClientHelloBuilder.build(params, ech, null, random);

        HandshakeMessages.ClientHello outer = HandshakeMessages.parseClientHello(
                offer.getClientHelloOuterFramed());
        assertEquals("public.example", outer.serverName);
        assertNotNull(outer.encryptedClientHelloOuter);
        assertFalse(outer.encryptedClientHelloInner);

        HandshakeMessages.ClientHello inner = HandshakeMessages.parseClientHello(
                offer.getClientHelloInnerFramed());
        assertEquals("backend.example", inner.serverName);
        assertTrue(inner.encryptedClientHelloInner);
        assertNull(inner.encryptedClientHelloOuter);

        byte[] encodedInner = EchClientHelloBuilder.encodeClientHelloInner(
                offer.getClientHelloInnerContent(), ech, "backend.example");
        byte[] outerContent = HandshakeMessages.extractClientHelloContent(offer.getClientHelloOuterFramed());
        EncryptedClientHello.Outer wire = outer.encryptedClientHelloOuter;
        byte[] aad = EncryptedClientHello.clientHelloOuterAadWithZeroEchPayload(
                outerContent, wire.payload.length);
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        Hpke.RecipientContext recipient = hpke.setupBaseR(
                wire.enc, SK_RM, PK_RM, ech.hpkeSetupInfo());
        assertArrayEquals(encodedInner, recipient.open(aad, wire.payload));
    }

    @Test
    public void helloRetryRequestSecondOuterUsesEmptyEncAndSameHpkeContext() throws Exception {
        EchConfig ech = EchConfig.createV13(10, PK_RM, "public.example", 32);
        HandshakeMessages.ClientHelloParams params = sampleParams("backend.example");
        SecureRandom random = new SecureRandom();

        EchClientHelloBuilder.Offer first = EchClientHelloBuilder.build(params, ech, null, random);
        EchServer.OpenResult openedFirst = EchServer.openInnerClientHello(
                first.getClientHelloOuterFramed(), ech, SK_RM, null);

        HandshakeMessages.ClientHelloParams retryParams = sampleParams("backend.example");
        retryParams.random = first.getClientHelloOuterRandom();
        retryParams.keyShares = params.keyShares;

        EchClientHelloBuilder.Offer second = EchClientHelloBuilder.buildHelloRetryRequest(
                first.getHpkeSender(), ech, retryParams, first.getClientHelloInnerFramed(), null);
        HandshakeMessages.ClientHello outer2 = HandshakeMessages.parseClientHello(
                second.getClientHelloOuterFramed());
        assertEquals(0, outer2.encryptedClientHelloOuter.enc.length);

        EchServer.OpenResult openedSecond = EchServer.openInnerClientHello(
                second.getClientHelloOuterFramed(), ech, SK_RM, openedFirst.getHpkeRecipient());
        HandshakeMessages.ClientHello inner2 = HandshakeMessages.parseClientHello(
                openedSecond.getInnerClientHelloFramed());
        assertEquals("backend.example", inner2.serverName);
        assertTrue(inner2.encryptedClientHelloInner);
    }

    @Test
    public void greaseOuterHasValidEncAndNonEmptyPayload() throws Exception {
        HandshakeMessages.ClientHelloParams params = sampleParams("client.example");
        EncryptedClientHello.Outer grease = EchClientHelloBuilder.buildGreaseOuter(params, new SecureRandom());
        assertEquals(32, grease.enc.length);
        assertTrue(grease.payload.length > 16);
        assertTrue(grease.configId >= 0 && grease.configId <= 255);
    }

    private static final int[] AEADS = {
        Hpke.AEAD_AES_128_GCM, Hpke.AEAD_AES_256_GCM, Hpke.AEAD_CHACHA20_POLY1305
    };

    @Test
    public void offerUsesTheSelectedAeadOnTheWireAndServerOpensIt() throws Exception {
        for (int aead : AEADS) {
            EchConfig ech = EchConfig.createV13(9, PK_RM, "public.example", 32,
                    new int[][] { { Hpke.KDF_HKDF_SHA256, aead } });
            EchClientHelloBuilder.Offer offer = EchClientHelloBuilder.build(
                    sampleParams("backend.example"), ech, null, new SecureRandom());
            HandshakeMessages.ClientHello outer = HandshakeMessages.parseClientHello(
                    offer.getClientHelloOuterFramed());
            assertEquals(Hpke.KDF_HKDF_SHA256, outer.encryptedClientHelloOuter.kdfId);
            assertEquals("aead id on the wire", aead, outer.encryptedClientHelloOuter.aeadId);

            EchServer.OpenResult opened = EchServer.openInnerClientHello(
                    offer.getClientHelloOuterFramed(), ech, SK_RM, null);
            HandshakeMessages.ClientHello inner = HandshakeMessages.parseClientHello(
                    opened.getInnerClientHelloFramed());
            assertEquals("backend.example", inner.serverName);
        }
    }

    @Test
    public void helloRetryRequestKeepsTheOriginalAead() throws Exception {
        EchConfig ech = EchConfig.createV13(10, PK_RM, "public.example", 32,
                new int[][] { { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305 } });
        HandshakeMessages.ClientHelloParams params = sampleParams("backend.example");
        EchClientHelloBuilder.Offer first = EchClientHelloBuilder.build(params, ech, null, new SecureRandom());
        EchServer.OpenResult openedFirst = EchServer.openInnerClientHello(
                first.getClientHelloOuterFramed(), ech, SK_RM, null);
        HandshakeMessages.ClientHelloParams retryParams = sampleParams("backend.example");
        retryParams.random = first.getClientHelloOuterRandom();
        retryParams.keyShares = params.keyShares;
        EchClientHelloBuilder.Offer second = EchClientHelloBuilder.buildHelloRetryRequest(
                first.getHpkeSender(), ech, retryParams, first.getClientHelloInnerFramed(), null);
        HandshakeMessages.ClientHello outer2 = HandshakeMessages.parseClientHello(
                second.getClientHelloOuterFramed());
        assertEquals(Hpke.AEAD_CHACHA20_POLY1305, outer2.encryptedClientHelloOuter.aeadId);
        EchServer.OpenResult openedSecond = EchServer.openInnerClientHello(
                second.getClientHelloOuterFramed(), ech, SK_RM, openedFirst.getHpkeRecipient());
        assertTrue(HandshakeMessages.parseClientHello(
                openedSecond.getInnerClientHelloFramed()).encryptedClientHelloInner);
    }

    @Test
    public void serverRejectsSuiteNotAdvertisedByItsConfig() throws Exception {
        EchConfig clientView = EchConfig.createV13(9, PK_RM, "public.example", 32,
                new int[][] { { Hpke.KDF_HKDF_SHA256, Hpke.AEAD_CHACHA20_POLY1305 } });
        EchClientHelloBuilder.Offer offer = EchClientHelloBuilder.build(
                sampleParams("backend.example"), clientView, null, new SecureRandom());
        // Same key and id, but the server config lists only the section 9 suite:
        // the client's suite is not one it advertised.
        EchConfig serverView = EchConfig.createV13(9, PK_RM, "public.example", 32);
        try {
            EchServer.openInnerClientHello(offer.getClientHelloOuterFramed(), serverView, SK_RM, null);
            fail("suite outside the advertised list must not be opened");
        } catch (GeneralSecurityException expected) {
            // treated as an ECH decryption failure by the handshake engine
        }
    }

    @Test
    public void builderRejectsConfigWithNoSupportedSuite() throws Exception {
        EchConfig ech = EchConfig.createV13(9, PK_RM, "public.example", 32,
                new int[][] { { 0x0002, Hpke.AEAD_AES_128_GCM } });
        try {
            EchClientHelloBuilder.build(sampleParams("backend.example"), ech, null, new SecureRandom());
            fail("expected HandshakeFormatException");
        } catch (HandshakeFormatException expected) {
            // nothing usable in the config
        }
    }

    private static HandshakeMessages.ClientHelloParams sampleParams(String serverName) {
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = new byte[32];
        params.cipherSuites = Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256);
        params.groups = Collections.singletonList(NamedGroup.X25519);
        params.keyShares = Collections.singletonMap(NamedGroup.X25519, new byte[32]);
        params.signatureAlgorithms = Collections.singletonList(
                org.bluezoo.gumdrop.crypto.SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.singletonList("h3");
        params.serverName = serverName;
        return params;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
