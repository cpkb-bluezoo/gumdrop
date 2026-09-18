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
        Hpke hpke = Hpke.x25519Aes128Gcm();
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
