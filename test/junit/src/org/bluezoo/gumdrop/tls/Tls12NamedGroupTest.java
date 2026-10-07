/*
 * Tls12NamedGroupTest.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.crypto.NamedGroup;

/**
 * RFC 8422 ECDHE group negotiation in {@link Tls12HandshakeEngine}:
 * x25519 preferred, secp256r1 as the fallback, and the signed
 * {@code ServerECDHParams} covering the group actually sent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12NamedGroupTest {

    private static final int HANDSHAKE_SERVER_KEY_EXCHANGE = 12;

    private static final class Rec implements Tls12EventSink {
        final List<byte[]> out = new ArrayList<byte[]>();
        TlsProtocolError error;

        @Override
        public void handshakeDataReady(byte[] data) {
            out.add(data);
        }

        @Override
        public void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client,
                DirectionalKeyMaterial server) {
        }

        @Override
        public void sendChangeCipherSpec() {
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void protocolError(TlsProtocolError e) {
            error = e;
        }
    }

    private static final class Run {
        Rec cr = new Rec();
        Rec sr = new Rec();
        Tls12HandshakeEngine client;
        Tls12HandshakeEngine server;
    }

    private static Tls12HandshakeConfig serverCfg(NamedGroup... groups) throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        if (groups.length > 0) {
            c.setNamedGroups(Arrays.asList(groups));
        }
        return c;
    }

    private static Tls12HandshakeConfig clientCfg(NamedGroup... groups) throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        if (groups.length > 0) {
            c.setNamedGroups(Arrays.asList(groups));
        }
        return c;
    }

    /** Runs a handshake, rewriting the ServerKeyExchange's named-curve id when {@code curveOverride} >= 0. */
    private static Run run(Tls12HandshakeConfig cc, Tls12HandshakeConfig sc, int curveOverride) {
        Run r = new Run();
        r.client = new Tls12HandshakeEngine(cc);
        r.server = new Tls12HandshakeEngine(sc);
        r.client.start(r.cr);
        for (int round = 0; round < 40; round++) {
            boolean moved = false;
            List<byte[]> cmsgs = new ArrayList<byte[]>(r.cr.out);
            r.cr.out.clear();
            for (int i = 0; i < cmsgs.size(); i++) {
                moved = true;
                r.server.processMessage(cmsgs.get(i), r.sr);
            }
            List<byte[]> smsgs = new ArrayList<byte[]>(r.sr.out);
            r.sr.out.clear();
            for (int i = 0; i < smsgs.size(); i++) {
                moved = true;
                byte[] m = smsgs.get(i);
                if (curveOverride >= 0 && (m[0] & 0xff) == HANDSHAKE_SERVER_KEY_EXCHANGE) {
                    m = m.clone();
                    // header(4) + ECCurveType(1), then the u16 named curve
                    m[5] = (byte) (curveOverride >> 8);
                    m[6] = (byte) curveOverride;
                }
                r.client.processMessage(m, r.cr);
            }
            if (!moved) {
                break;
            }
        }
        return r;
    }

    private static void assertCompletedOn(Run r, NamedGroup expected) {
        assertTrue("client: " + (r.cr.error != null ? r.cr.error.getMessage() : ""), r.client.isComplete());
        assertTrue("server: " + (r.sr.error != null ? r.sr.error.getMessage() : ""), r.server.isComplete());
        assertEquals(expected, r.client.getNegotiatedGroup());
        assertEquals(expected, r.server.getNegotiatedGroup());
    }

    @Test
    public void defaultsNegotiateX25519() throws Exception {
        assertCompletedOn(run(clientCfg(), serverCfg(), -1), NamedGroup.X25519);
    }

    @Test
    public void clientOfferingOnlySecp256r1FallsBack() throws Exception {
        assertCompletedOn(run(clientCfg(NamedGroup.SECP256R1), serverCfg(), -1), NamedGroup.SECP256R1);
    }

    @Test
    public void serverOfferingOnlySecp256r1FallsBack() throws Exception {
        assertCompletedOn(run(clientCfg(), serverCfg(NamedGroup.SECP256R1), -1), NamedGroup.SECP256R1);
    }

    @Test
    public void serverPrefersX25519WhenClientListsBothInEitherOrder() throws Exception {
        assertCompletedOn(run(clientCfg(NamedGroup.SECP256R1, NamedGroup.X25519), serverCfg(), -1),
                NamedGroup.X25519);
    }

    @Test
    public void serverPreferenceOrderIsHonoured() throws Exception {
        assertCompletedOn(run(clientCfg(), serverCfg(NamedGroup.SECP256R1, NamedGroup.X25519), -1),
                NamedGroup.SECP256R1);
    }

    @Test
    public void noCommonGroupFailsWithHandshakeFailure() throws Exception {
        Run r = run(clientCfg(NamedGroup.X25519), serverCfg(NamedGroup.SECP256R1), -1);
        assertNotNull(r.sr.error);
        assertEquals(AlertDescription.HANDSHAKE_FAILURE, r.sr.error.getAlert());
        assertNull(r.server.getNegotiatedGroup());
    }

    @Test
    public void signatureOverWrongCurveIdFails() throws Exception {
        // The server signed x25519 params; rewriting the id to secp256r1 (in the
        // client's offer) makes the client verify different bytes.
        Run r = run(clientCfg(), serverCfg(), NamedGroup.SECP256R1.getCode());
        assertNotNull(r.cr.error);
        assertEquals(AlertDescription.DECRYPT_ERROR, r.cr.error.getAlert());
    }

    @Test
    public void serverKeyExchangeGroupNotOfferedFails() throws Exception {
        Run r = run(clientCfg(NamedGroup.SECP256R1), serverCfg(NamedGroup.SECP256R1), NamedGroup.X25519.getCode());
        assertNotNull(r.cr.error);
        assertEquals(AlertDescription.ILLEGAL_PARAMETER, r.cr.error.getAlert());
    }

    @Test
    public void otherNamedCurveFailsHandshake() throws Exception {
        Run r = run(clientCfg(), serverCfg(), NamedGroup.SECP384R1.getCode());
        assertNotNull(r.cr.error);
    }

    @Test
    public void configRejectsGroupsTheEngineDoesNotImplement() throws Exception {
        Tls12HandshakeConfig c = clientCfg();
        try {
            c.setNamedGroups(Collections.singletonList(NamedGroup.X25519_MLKEM768));
            org.junit.Assert.fail("hybrid group must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
