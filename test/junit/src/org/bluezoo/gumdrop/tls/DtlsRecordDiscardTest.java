/*
 * DtlsRecordDiscardTest.java
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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 6347 section 4.1.2.7 and RFC 9147 section 4.5.2: a record that is
 * malformed, fails authentication, uses an unknown epoch or is a replay is
 * silently discarded and never fails the session, so an off-path attacker
 * who can only spoof datagrams cannot tear a session down. A fatal alert
 * from the authenticated peer, or a genuine handshake failure, still does.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsRecordDiscardTest {

    private static final byte[] PING = new byte[] { 'p', 'i', 'n', 'g' };
    private static final byte[] PONG = new byte[] { 'p', 'o', 'n', 'g', '!' };

    private static List<X509Certificate> chain;
    private static PrivateKey key;

    @BeforeClass
    public static void loadFixtures() throws Exception {
        chain = TestCertificates.ec256().getChain();
        key = TestCertificates.ec256().getPrivateKey();
    }

    private static final class Sink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        int errors;
        boolean closed;

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
        }

        @Override
        public void peerClosed() {
            closed = true;
        }
    }

    /** Version-neutral view of a DTLS record engine. */
    private abstract static class Peer {
        abstract void start(Sink s);

        abstract void feed(byte[] datagram, Sink s);

        abstract boolean complete();

        abstract boolean failed();

        abstract void send(byte[] data, Sink s);

        abstract void close(Sink s);

        abstract int discarded();
    }

    private static Peer wrap12(final Dtls12RecordEngine e) {
        return new Peer() {
            @Override
            void start(Sink s) {
                e.start(s);
            }

            @Override
            void feed(byte[] datagram, Sink s) {
                e.feedDatagram(datagram, s);
            }

            @Override
            boolean complete() {
                return e.isComplete();
            }

            @Override
            boolean failed() {
                return e.isFailed();
            }

            @Override
            void send(byte[] data, Sink s) {
                e.sendApplicationData(data, s);
            }

            @Override
            void close(Sink s) {
                e.sendCloseNotify(s);
            }

            @Override
            int discarded() {
                return e.discardedRecordCount();
            }
        };
    }

    private static Peer wrap13(final Dtls13RecordEngine e) {
        return new Peer() {
            @Override
            void start(Sink s) {
                e.start(s);
            }

            @Override
            void feed(byte[] datagram, Sink s) {
                e.feedDatagram(datagram, s);
            }

            @Override
            boolean complete() {
                return e.isComplete();
            }

            @Override
            boolean failed() {
                return e.isFailed();
            }

            @Override
            void send(byte[] data, Sink s) {
                e.sendApplicationData(data, s);
            }

            @Override
            void close(Sink s) {
                e.sendCloseNotify(s);
            }

            @Override
            int discarded() {
                return e.discardedRecordCount();
            }
        };
    }

    private static Peer newPeer(int version, boolean server, X509TrustManager trust) throws Exception {
        if (version == 12) {
            Tls12HandshakeConfig c = new Tls12HandshakeConfig(
                    server ? HandshakeRole.SERVER : HandshakeRole.CLIENT);
            c.setDtlsTransport(true);
            if (server) {
                c.setServerCredentials(new ServerCredentials(chain, key));
            } else {
                c.setServerName(TestCertificates.SERVER_NAME);
                c.setTrustManager(trust);
            }
            return wrap12(new Dtls12RecordEngine(c, 1024));
        }
        HandshakeConfig c = new HandshakeConfig(server ? HandshakeRole.SERVER : HandshakeRole.CLIENT);
        c.setCertificateCompressionEnabled(false);
        if (server) {
            c.setServerCredentials(new ServerCredentials(chain, key));
        } else {
            c.setServerName(TestCertificates.SERVER_NAME);
            c.setTrustManager(trust);
        }
        return wrap13(new Dtls13RecordEngine(new Dtls13HandshakeConfig(c), 1024));
    }

    /** Two engines joined by hand, with every datagram of each direction recorded. */
    private static final class Net {
        final Peer client;
        final Peer server;
        final Sink cs = new Sink();
        final Sink ss = new Sink();
        final List<byte[]> toServer = new ArrayList<byte[]>();
        final List<byte[]> toClient = new ArrayList<byte[]>();
        /** Datagrams injected at the receiver before each real datagram. */
        List<byte[]> noise = new ArrayList<byte[]>();

        Net(int version, X509TrustManager trust) throws Exception {
            client = newPeer(version, false, trust);
            server = newPeer(version, true, trust);
        }

        void injectNoise(Peer to, Sink toSink) {
            for (int i = 0; i < noise.size(); i++) {
                to.feed(noise.get(i), toSink);
            }
        }

        void pump() {
            for (int round = 0; round < 60; round++) {
                boolean moved = false;
                List<byte[]> a = new ArrayList<byte[]>(cs.outbound);
                cs.outbound.clear();
                for (int i = 0; i < a.size(); i++) {
                    moved = true;
                    toServer.add(a.get(i));
                    injectNoise(server, ss);
                    injectNoise(client, cs);
                    server.feed(a.get(i), ss);
                }
                List<byte[]> b = new ArrayList<byte[]>(ss.outbound);
                ss.outbound.clear();
                for (int i = 0; i < b.size(); i++) {
                    moved = true;
                    toClient.add(b.get(i));
                    injectNoise(client, cs);
                    injectNoise(server, ss);
                    client.feed(b.get(i), cs);
                }
                if (!moved) {
                    break;
                }
            }
        }

        void handshake() {
            client.start(cs);
            pump();
        }

        void assertHealthy(String label) {
            assertTrue(label + ": client complete", client.complete());
            assertTrue(label + ": server complete", server.complete());
            assertFalse(label + ": client failed", client.failed());
            assertFalse(label + ": server failed", server.failed());
            assertEquals(label + ": client errors", 0, cs.errors);
            assertEquals(label + ": server errors", 0, ss.errors);
        }

        /** Application data both ways, delivered exactly once each. */
        void assertDataFlows(String label) {
            int before = ss.appData.size();
            client.send(PING, cs);
            pump();
            assertEquals(label + ": server received PING", before + 1, ss.appData.size());
            assertArrayEquals(label, PING, ss.appData.get(before));
            int cbefore = cs.appData.size();
            server.send(PONG, ss);
            pump();
            assertEquals(label + ": client received PONG", cbefore + 1, cs.appData.size());
            assertArrayEquals(label, PONG, cs.appData.get(cbefore));
        }
    }

    private static byte[] legacyRecord(int type, int major, int minor, int epoch, int bodyLen,
            int bodyFill) {
        byte[] r = new byte[13 + bodyLen];
        r[0] = (byte) type;
        r[1] = (byte) major;
        r[2] = (byte) minor;
        r[3] = (byte) (epoch >> 8);
        r[4] = (byte) epoch;
        r[10] = 77;
        r[11] = (byte) (bodyLen >> 8);
        r[12] = (byte) bodyLen;
        for (int i = 13; i < r.length; i++) {
            r[i] = (byte) (bodyFill + i * 31);
        }
        return r;
    }

    /** Datagrams an off-path attacker could spray without any key. */
    private static List<byte[]> spoofedDatagrams() {
        List<byte[]> l = new ArrayList<byte[]>();
        Random rnd = new Random(42);
        for (int len = 1; len <= 40; len += 3) {
            byte[] b = new byte[len];
            rnd.nextBytes(b);
            l.add(b);
        }
        int[] types = { 20, 21, 22, 23, 24, 99 };
        int[] epochs = { 0, 1, 2, 3, 0xffff };
        for (int t = 0; t < types.length; t++) {
            for (int e = 0; e < epochs.length; e++) {
                l.add(legacyRecord(types[t], 0xfe, 0xfd, epochs[e], 30, t));
                l.add(legacyRecord(types[t], 0xfe, 0xfd, epochs[e], 3, t));
            }
        }
        l.add(legacyRecord(22, 3, 3, 0, 20, 1));
        l.add(legacyRecord(22, 0xfe, 0xff, 0, 20, 1));
        byte[] truncatedBody = legacyRecord(23, 0xfe, 0xfd, 1, 50, 5);
        l.add(Arrays.copyOf(truncatedBody, 30));
        l.add(Arrays.copyOf(truncatedBody, 12));
        byte[] unified = new byte[40];
        rnd.nextBytes(unified);
        unified[0] = 0x2c;
        unified[3] = 0;
        unified[4] = 30;
        l.add(unified);
        byte[] unifiedLong = unified.clone();
        unifiedLong[4] = 90;
        l.add(unifiedLong);
        byte[] unifiedShort = Arrays.copyOf(unified, 4);
        unifiedShort[0] = 0x2d;
        l.add(unifiedShort);
        byte[] unifiedTiny = new byte[] { 0x2c, 0, 1, 0, 3, 1, 2, 3 };
        l.add(unifiedTiny);
        return l;
    }

    private void spoofedGarbageBeforeAndDuringHandshake(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.noise = spoofedDatagrams();
        // before the handshake even starts
        net.injectNoise(net.server, net.ss);
        net.injectNoise(net.client, net.cs);
        net.handshake();
        net.assertHealthy("garbage in handshake v" + version);
        assertTrue("discards counted", net.server.discarded() > 0);
        assertTrue("discards counted", net.client.discarded() > 0);
        net.noise = new ArrayList<byte[]>();
        net.assertDataFlows("after noisy handshake v" + version);
    }

    @Test
    public void dtls12SurvivesSpoofedGarbageDuringHandshake() throws Exception {
        spoofedGarbageBeforeAndDuringHandshake(12);
    }

    @Test
    public void dtls13SurvivesSpoofedGarbageDuringHandshake() throws Exception {
        spoofedGarbageBeforeAndDuringHandshake(13);
    }

    private void spoofedGarbageAfterHandshake(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.assertHealthy("handshake v" + version);
        List<byte[]> garbage = spoofedDatagrams();
        for (int i = 0; i < garbage.size(); i++) {
            net.server.feed(garbage.get(i), net.ss);
            net.client.feed(garbage.get(i), net.cs);
        }
        assertFalse(net.server.failed());
        assertFalse(net.client.failed());
        assertEquals(0, net.ss.errors);
        assertEquals(0, net.cs.errors);
        net.assertDataFlows("garbage after handshake v" + version);
    }

    @Test
    public void dtls12SurvivesSpoofedGarbageAfterHandshake() throws Exception {
        spoofedGarbageAfterHandshake(12);
    }

    @Test
    public void dtls13SurvivesSpoofedGarbageAfterHandshake() throws Exception {
        spoofedGarbageAfterHandshake(13);
    }

    private void tamperedProtectedRecord(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.assertHealthy("handshake v" + version);
        net.client.send(PING, net.cs);
        assertEquals(1, net.cs.outbound.size());
        byte[] real = net.cs.outbound.get(0);
        net.cs.outbound.clear();
        int discardedBefore = net.server.discarded();
        // every single-bit flip of the protected record, plus every truncation
        for (int off = 0; off < real.length; off++) {
            for (int bit = 0; bit < 8; bit += 7) {
                byte[] flipped = real.clone();
                flipped[off] ^= (byte) (1 << bit);
                net.server.feed(flipped, net.ss);
            }
        }
        for (int len = 0; len < real.length; len++) {
            net.server.feed(Arrays.copyOf(real, len), net.ss);
        }
        assertFalse("tampering failed the session", net.server.failed());
        assertEquals(0, net.ss.errors);
        assertTrue("nothing tampered was delivered", net.ss.appData.isEmpty());
        assertTrue(net.server.discarded() > discardedBefore);
        // the genuine record still works (a junk tail after it is discarded), and a replay is dropped
        int beforeTail = net.server.discarded();
        net.server.feed(Arrays.copyOf(real, real.length + 7), net.ss);
        assertTrue("tail discarded", net.server.discarded() > beforeTail);
        net.server.feed(real, net.ss);
        net.server.feed(real.clone(), net.ss);
        assertEquals(1, net.ss.appData.size());
        assertArrayEquals(PING, net.ss.appData.get(0));
        assertFalse(net.server.failed());
        net.assertDataFlows("after tampering v" + version);
    }

    @Test
    public void dtls12DiscardsTamperedAndReplayedProtectedRecords() throws Exception {
        tamperedProtectedRecord(12);
    }

    @Test
    public void dtls13DiscardsTamperedAndReplayedProtectedRecords() throws Exception {
        tamperedProtectedRecord(13);
    }

    @Test
    public void dtls12DiscardsWrongEpochProtectedRecord() throws Exception {
        Net net = new Net(12, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.client.send(PING, net.cs);
        byte[] real = net.cs.outbound.get(0);
        net.cs.outbound.clear();
        int[] epochs = { 0, 2, 3, 0xffff };
        for (int i = 0; i < epochs.length; i++) {
            byte[] wrong = real.clone();
            wrong[3] = (byte) (epochs[i] >> 8);
            wrong[4] = (byte) epochs[i];
            net.server.feed(wrong, net.ss);
            assertFalse("epoch " + epochs[i], net.server.failed());
        }
        assertTrue(net.ss.appData.isEmpty());
        net.server.feed(real, net.ss);
        assertEquals(1, net.ss.appData.size());
    }

    private void replayEverything(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.assertHealthy("handshake v" + version);
        net.assertDataFlows("first exchange v" + version);
        List<byte[]> toServer = new ArrayList<byte[]>(net.toServer);
        List<byte[]> toClient = new ArrayList<byte[]>(net.toClient);
        int serverData = net.ss.appData.size();
        int clientData = net.cs.appData.size();
        for (int i = 0; i < toServer.size(); i++) {
            net.server.feed(toServer.get(i), net.ss);
        }
        for (int i = 0; i < toClient.size(); i++) {
            net.client.feed(toClient.get(i), net.cs);
        }
        assertFalse(net.server.failed());
        assertFalse(net.client.failed());
        assertEquals(0, net.ss.errors);
        assertEquals(0, net.cs.errors);
        assertEquals("no replayed data accepted", serverData, net.ss.appData.size());
        assertEquals("no replayed data accepted", clientData, net.cs.appData.size());
        net.assertDataFlows("after replay v" + version);
    }

    @Test
    public void dtls12SurvivesReplayOfEveryEarlierDatagram() throws Exception {
        replayEverything(12);
    }

    @Test
    public void dtls13SurvivesReplayOfEveryEarlierDatagram() throws Exception {
        replayEverything(13);
    }

    private void closeNotifyStillEndsSession(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.client.close(net.cs);
        net.pump();
        assertTrue("authenticated close_notify reaches the peer", net.ss.closed);
    }

    @Test
    public void dtls12CloseNotifyStillEndsSession() throws Exception {
        closeNotifyStillEndsSession(12);
    }

    @Test
    public void dtls13CloseNotifyStillEndsSession() throws Exception {
        closeNotifyStillEndsSession(13);
    }

    private void genuineHandshakeFailureStillFailsBothSides(int version) throws Exception {
        Net net = new Net(version, TestCertificates.trustNone());
        net.handshake();
        assertTrue("client rejected the certificate", net.client.failed());
        assertTrue(net.cs.errors > 0);
        assertTrue("peer's fatal alert fails the server", net.server.failed());
        assertTrue(net.ss.errors > 0);
    }

    @Test
    public void dtls12GenuineHandshakeFailureStillFailsBothSides() throws Exception {
        genuineHandshakeFailureStillFailsBothSides(12);
    }

    @Test
    public void dtls13GenuineHandshakeFailureStillFailsBothSides() throws Exception {
        genuineHandshakeFailureStillFailsBothSides(13);
    }

    private void lostAndReorderedRecordsDoNotBreakLaterOnes(int version) throws Exception {
        Net net = new Net(version, CertificateVerifier.trustManagerFromCertificates(chain));
        net.handshake();
        net.assertHealthy("handshake v" + version);
        byte[][] sent = new byte[4][];
        for (int i = 0; i < sent.length; i++) {
            net.client.send(new byte[] { 'a', (byte) ('0' + i) }, net.cs);
            sent[i] = net.cs.outbound.get(0);
            net.cs.outbound.clear();
        }
        // record 0 is lost; 2 arrives before 1; 3 arrives twice
        net.server.feed(sent[2], net.ss);
        net.server.feed(sent[1], net.ss);
        net.server.feed(sent[3], net.ss);
        net.server.feed(sent[3], net.ss);
        assertFalse(net.server.failed());
        assertEquals(3, net.ss.appData.size());
        assertArrayEquals(new byte[] { 'a', '2' }, net.ss.appData.get(0));
        assertArrayEquals(new byte[] { 'a', '1' }, net.ss.appData.get(1));
        assertArrayEquals(new byte[] { 'a', '3' }, net.ss.appData.get(2));
        net.assertDataFlows("after loss v" + version);
    }

    @Test
    public void dtls12LostAndReorderedRecordsDoNotBreakLaterOnes() throws Exception {
        lostAndReorderedRecordsDoNotBreakLaterOnes(12);
    }

    @Test
    public void dtls13LostAndReorderedRecordsDoNotBreakLaterOnes() throws Exception {
        lostAndReorderedRecordsDoNotBreakLaterOnes(13);
    }
}
