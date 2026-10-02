/*
 * DtlsEchHandshakeTest.java
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
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Encrypted Client Hello over DTLS 1.3, in memory: the DTLS ClientHello
 * carries a legacy_cookie field after legacy_session_id (RFC 9147 section
 * 5.3), and the outer hello, the ClientHelloOuterAAD the inner hello is
 * sealed against, the encoded inner hello and every re-parse must agree on
 * that layout.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsEchHandshakeTest {

    private static final String ECH_PK = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d";
    private static final String ECH_SK = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final class Sink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        int errors;
        final List<String> messages = new ArrayList<String>();

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
            appData.add(plaintext);
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            errors++;
            messages.add(String.valueOf(err.getMessage()));
        }

        @Override
        public void peerClosed() {
        }
    }

    private static final class CookieSecret implements CookieValidator {
        @Override
        public byte[] computeCookie(byte[] clientHelloRandom) {
            byte[] cookie = new byte[16];
            for (int i = 0; i < cookie.length; i++) {
                cookie[i] = (byte) (0x5a ^ clientHelloRandom[i % clientHelloRandom.length]);
            }
            return cookie;
        }

        @Override
        public boolean validateCookie(byte[] clientHelloRandom, byte[] cookie) {
            return cookie != null && Arrays.equals(computeCookie(clientHelloRandom), cookie);
        }
    }

    private static final class Retry implements EchRetryConfigsListener {
        int calls;
        EchConfig[] configs;

        @Override
        public void retryConfigsReceived(EchConfig[] authenticatedConfigs) {
            calls++;
            configs = authenticatedConfigs;
        }
    }

    private static EchConfig echConfig(int id) throws Exception {
        // the public name is the certificate name so retry_configs authenticate
        return EchConfig.createV13(id, hex(ECH_PK), TestCertificates.SERVER_NAME, 64);
    }

    private static HandshakeConfig server(boolean withKeys, int keyConfigId, boolean cookie,
            boolean echRequired, EchConfig retry) throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setCertificateCompressionEnabled(false);
        c.setServerCredentials(new ServerCredentials(TestCertificates.ec256().getChain(),
                TestCertificates.ec256().getPrivateKey()));
        if (withKeys) {
            c.addEchServerKey(echConfig(keyConfigId), hex(ECH_SK));
        }
        c.setEchServerRequired(echRequired);
        if (retry != null) {
            c.setEchRetryConfigList(EchConfig.encodeList(new EchConfig[] { retry }));
        }
        if (cookie) {
            c.setCookieValidator(new CookieSecret());
        }
        return c;
    }

    private static HandshakeConfig client(EchConfig ech, boolean grease, Retry retry) throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setCertificateCompressionEnabled(false);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(CertificateVerifier.trustManagerFromCertificates(
                TestCertificates.ec256().getChain()));
        if (ech != null) {
            c.setEchEnabled(true);
            c.setEchConfig(ech);
            c.setEchRequired(true);
        }
        c.setEchGreaseEnabled(grease);
        if (retry != null) {
            c.setEchRetryConfigsListener(retry);
        }
        return c;
    }

    private static final class Run {
        final Dtls13RecordEngine client;
        final Dtls13RecordEngine server;
        final Sink cs = new Sink();
        final Sink ss = new Sink();

        Run(HandshakeConfig clientCfg, HandshakeConfig serverCfg) {
            client = new Dtls13RecordEngine(new Dtls13HandshakeConfig(clientCfg), 1024);
            server = new Dtls13RecordEngine(new Dtls13HandshakeConfig(serverCfg), 1024);
            client.start(cs);
            for (int round = 0; round < 60; round++) {
                boolean moved = false;
                List<byte[]> a = new ArrayList<byte[]>(cs.outbound);
                cs.outbound.clear();
                for (int i = 0; i < a.size(); i++) {
                    moved = true;
                    server.feedDatagram(a.get(i), ss);
                }
                List<byte[]> b = new ArrayList<byte[]>(ss.outbound);
                ss.outbound.clear();
                for (int i = 0; i < b.size(); i++) {
                    moved = true;
                    client.feedDatagram(b.get(i), cs);
                }
                if (!moved) {
                    break;
                }
            }
        }

        void assertCompletesAndCarriesData() {
            assertTrue("client complete", client.isComplete());
            assertTrue("server complete", server.isComplete());
            assertEquals(0, cs.errors);
            assertEquals(0, ss.errors);
            byte[] ping = new byte[] { 'e', 'c', 'h' };
            client.sendApplicationData(ping, cs);
            for (int i = 0; i < cs.outbound.size(); i++) {
                server.feedDatagram(cs.outbound.get(i), ss);
            }
            assertEquals(1, ss.appData.size());
            assertArrayEquals(ping, ss.appData.get(0));
        }
    }

    @Test
    public void echAcceptedOverDtls13() throws Exception {
        Run run = new Run(client(echConfig(7), false, null), server(true, 7, false, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echAcceptedOverDtls13WithCookieHelloRetryRequest() throws Exception {
        Run run = new Run(client(echConfig(7), false, null), server(true, 7, true, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echRequiredServerRejectsClientWithoutEch() throws Exception {
        Run run = new Run(client(null, false, null), server(true, 7, false, true, null));
        assertFalse(run.client.isComplete());
        assertFalse(run.server.isComplete());
        assertTrue("server reports ech_required", run.ss.errors > 0);
    }

    @Test
    public void clientWithoutEchStillServedByEchCapableServer() throws Exception {
        Run run = new Run(client(null, false, null), server(true, 7, false, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echGreaseIsIgnoredByServerWithoutKeys() throws Exception {
        Run run = new Run(client(null, true, null), server(false, 7, false, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echGreaseWithCookieHelloRetryRequest() throws Exception {
        Run run = new Run(client(null, true, null), server(false, 7, true, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echGreaseIsIgnoredByServerWithKeys() throws Exception {
        Run run = new Run(client(null, true, null), server(true, 7, true, false, null));
        run.assertCompletesAndCarriesData();
    }

    @Test
    public void echRejectedWithRetryConfigsFailsRequiredClientAndReportsConfigs() throws Exception {
        Retry retry = new Retry();
        // the server only holds key 7; the client offers config id 8
        Run run = new Run(client(echConfig(8), false, retry),
                server(true, 7, false, false, echConfig(7)));
        assertFalse("ech_required client must not complete", run.client.isComplete());
        assertTrue(run.cs.errors > 0);
        assertEquals(1, retry.calls);
        // the server's retry_configs carry its real config plus a GREASE one
        boolean found = false;
        for (int i = 0; i < retry.configs.length; i++) {
            found = found || retry.configs[i].getConfigId() == 7;
        }
        assertTrue("real config in retry_configs", found);
    }

    @Test
    public void echRejectedWithCookieAbortsAtHelloRetryRequest() throws Exception {
        Retry retry = new Retry();
        Run run = new Run(client(echConfig(8), false, retry),
                server(true, 7, true, false, echConfig(7)));
        // the cookie forces a HelloRetryRequest without ECH acceptance: a client
        // that requires ECH aborts there, before any retry_configs exist
        assertFalse(run.client.isComplete());
        assertTrue(run.cs.errors > 0);
        boolean rejected = run.cs.messages.contains("Server rejected Encrypted Client Hello");
        assertTrue("client aborted on rejected ECH", rejected);
        assertEquals(0, retry.calls);
    }
}
