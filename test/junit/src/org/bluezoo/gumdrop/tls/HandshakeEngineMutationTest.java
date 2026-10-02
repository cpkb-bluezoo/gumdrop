/*
 * HandshakeEngineMutationTest.java
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
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Drives two {@link HandshakeEngine}s through a TLS 1.3 handshake while an
 * adversary in the middle truncates, extends, bit-flips, retypes, drops
 * and duplicates each handshake message in turn. The engine must never
 * throw: every malformed input has to end in a reported protocol error or
 * be tolerated, and a failed engine must stay failed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HandshakeEngineMutationTest {

    private boolean useHrr;
    private boolean useTickets;
    private boolean useEch;
    private boolean useGrease;
    private SessionTicket resumeTicket;
    private SessionTicket lastTicket;

    private static List<X509Certificate> chain;
    private static PrivateKey key;

    @BeforeClass
    public static void loadFixtures() throws Exception {
        chain = TestCertificates.ec256().getChain();
        key = TestCertificates.ec256().getPrivateKey();
    }

    /** Describes what to do to one message of the exchange. */
    private abstract static class Mutation {
        final String label;
        boolean shortened;

        Mutation(String label) {
            this.label = label;
        }

        /** Returns the messages to deliver in place of {@code msg}. */
        abstract List<byte[]> apply(byte[] msg);
    }

    private static final class Sink implements TlsEventSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        TlsProtocolError error;
        SessionTicket ticket;

        @Override
        public void handshakeDataReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void handshakeSecretsReady() {
        }

        @Override
        public void applicationSecretsReady() {
        }

        @Override
        public void peerTransportParameters(byte[] parameters) {
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
        }

        @Override
        public void quicEarlyKeysReady(CipherSuite suite, byte[] secret) {
        }

        @Override
        public void earlyDataAccepted(boolean accepted) {
        }

        @Override
        public void sessionTicketReceived(SessionTicket t) {
            ticket = t;
        }
    }

    private static final class Outcome {
        final List<byte[]> trace = new ArrayList<byte[]>();
        final List<Boolean> toServer = new ArrayList<Boolean>();
        boolean clientComplete;
        boolean serverComplete;
        boolean clientFailed;
        boolean serverFailed;
        boolean errored;
    }

    private static final String PK_RM = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d";
    private static final String SK_RM = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8";

    private static byte[] hex(String h) {
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return r;
    }

    private static EchConfig echConfig() {
        return EchConfig.createV13(1, hex(PK_RM), "public." + TestCertificates.SERVER_NAME, 64);
    }

    private HandshakeConfig serverConfig(boolean mutual, boolean compress) throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(new ServerCredentials(chain, key));
        c.setApplicationProtocols(Collections.singletonList("h3"));
        c.setLocalTransportParameters(new byte[] { 1, 2, 3 });
        c.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
        if (mutual) {
            c.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            c.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        }
        c.setCertificateCompressionEnabled(compress);
        if (useEch) {
            c.setEchServerKeys(echConfig(), hex(SK_RM));
        }
        if (useTickets) {
            byte[] k = new byte[16];
            for (int i = 0; i < k.length; i++) {
                k[i] = (byte) (i * 7 + 1);
            }
            c.setTicketKeys(new TicketKeys(k));
            c.setEnableEarlyData(true);
        }
        return c;
    }

    private HandshakeConfig clientConfig(boolean mutual, boolean compress) throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        c.setApplicationProtocols(Collections.singletonList("h3"));
        c.setLocalTransportParameters(new byte[] { 4, 5, 6 });
        c.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
        if (mutual) {
            c.setClientCredentials(new ServerCredentials(chain, key));
        }
        c.setCertificateCompressionEnabled(compress);
        if (useHrr) {
            List<NamedGroup> groups = new ArrayList<NamedGroup>();
            groups.add(NamedGroup.SECP256R1);
            groups.add(NamedGroup.X25519);
            c.setNamedGroups(groups);
            c.setClientOmitInitialKeyShareGroups(Collections.singletonList(NamedGroup.SECP256R1));
        }
        if (useEch) {
            c.setEchEnabled(true);
            c.setEchConfig(echConfig());
        }
        if (useGrease) {
            c.setEchGreaseEnabled(true);
        }
        if (resumeTicket != null) {
            c.setSessionTicket(resumeTicket);
            c.setEnableEarlyData(true);
        }
        return c;
    }

    /**
     * Runs the handshake, applying {@code mutation} to the message with
     * overall index {@code target} (or nothing when null).
     */
    private Outcome run(boolean mutual, boolean compress, int target, Mutation mutation)
            throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig(mutual, compress));
        HandshakeEngine server = new HandshakeEngine(serverConfig(mutual, compress));
        Sink cs = new Sink();
        Sink ss = new Sink();
        Outcome out = new Outcome();
        int index = 0;
        client.start(cs);
        List<byte[]> pendingForServer = new ArrayList<byte[]>(cs.outbound);
        cs.outbound.clear();
        List<byte[]> pendingForClient = new ArrayList<byte[]>();
        int rounds = 0;
        boolean serverTurn = true;
        while (rounds < 12 && (!pendingForServer.isEmpty() || !pendingForClient.isEmpty())) {
            rounds++;
            List<byte[]> batch;
            if (serverTurn) {
                batch = pendingForServer;
                pendingForServer = new ArrayList<byte[]>();
            } else {
                batch = pendingForClient;
                pendingForClient = new ArrayList<byte[]>();
            }
            for (int i = 0; i < batch.size(); i++) {
                byte[] msg = batch.get(i);
                out.trace.add(msg);
                out.toServer.add(Boolean.valueOf(serverTurn));
                List<byte[]> delivered = new ArrayList<byte[]>();
                if (index == target && mutation != null) {
                    delivered.addAll(mutation.apply(msg));
                } else {
                    delivered.add(msg);
                }
                index++;
                for (int d = 0; d < delivered.size(); d++) {
                    try {
                        if (serverTurn) {
                            server.processMessage(delivered.get(d), ss);
                        } else {
                            client.processMessage(delivered.get(d), cs);
                        }
                    } catch (RuntimeException e) {
                        String l = (mutation == null) ? "clean" : mutation.label;
                        throw new AssertionError("engine threw on " + l + " at message " + target, e);
                    }
                }
            }
            pendingForServer.addAll(cs.outbound);
            cs.outbound.clear();
            pendingForClient.addAll(ss.outbound);
            ss.outbound.clear();
            serverTurn = !serverTurn;
            if (pendingForServer.isEmpty() && !pendingForClient.isEmpty()) {
                serverTurn = false;
            } else if (pendingForClient.isEmpty() && !pendingForServer.isEmpty()) {
                serverTurn = true;
            }
        }
        out.clientComplete = client.isComplete();
        out.serverComplete = server.isComplete();
        out.clientFailed = client.isFailed();
        out.serverFailed = server.isFailed();
        out.errored = cs.error != null || ss.error != null;
        if (cs.ticket != null) {
            lastTicket = cs.ticket;
        }
        if (out.clientFailed || out.serverFailed) {
            assertTrue("a failure must be reported", out.errored);
        }
        return out;
    }

    private static Mutation truncate(final int len) {
        return new Mutation("truncate to " + len) {
            @Override
            List<byte[]> apply(byte[] msg) {
                shortened = len < msg.length;
                byte[] r = new byte[Math.min(len, msg.length)];
                System.arraycopy(msg, 0, r, 0, r.length);
                return Collections.singletonList(r);
            }
        };
    }

    private static Mutation flip(final int offset, final int mask) {
        return new Mutation("flip " + offset + "^" + mask) {
            @Override
            List<byte[]> apply(byte[] msg) {
                byte[] r = msg.clone();
                if (offset < r.length) {
                    r[offset] ^= (byte) mask;
                }
                return Collections.singletonList(r);
            }
        };
    }

    private static Mutation retype(final int type) {
        return new Mutation("retype " + type) {
            @Override
            List<byte[]> apply(byte[] msg) {
                byte[] r = msg.clone();
                r[0] = (byte) type;
                return Collections.singletonList(r);
            }
        };
    }

    private static Mutation append(final int extra) {
        return new Mutation("append " + extra) {
            @Override
            List<byte[]> apply(byte[] msg) {
                byte[] r = new byte[msg.length + extra];
                System.arraycopy(msg, 0, r, 0, msg.length);
                return Collections.singletonList(r);
            }
        };
    }

    private static Mutation drop() {
        return new Mutation("drop") {
            @Override
            List<byte[]> apply(byte[] msg) {
                return Collections.emptyList();
            }
        };
    }

    private static Mutation duplicate() {
        return new Mutation("duplicate") {
            @Override
            List<byte[]> apply(byte[] msg) {
                List<byte[]> l = new ArrayList<byte[]>();
                l.add(msg);
                l.add(msg);
                return l;
            }
        };
    }

    /**
     * Sweeps every message. The first and last 12 bytes of each message
     * (type, length, version, leading fields) are always mutated
     * exhaustively; the middle of long messages is sampled every
     * {@code bigStride} bytes.
     */
    private static boolean contains(int[] types, int t) {
        if (types == null) {
            return true;
        }
        for (int i = 0; i < types.length; i++) {
            if (types[i] == t) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sweeps the messages of the exchange whose handshake type is in
     * {@code only} (all messages when null). Variants that only change a
     * few messages (compression, HRR, ECH, resumption, client auth) mutate
     * just those: the rest of the flight is identical to the plain
     * handshake, and every run pays for key agreement and signatures. The
     * first and last 12 bytes of each message (type, length, version,
     * leading fields) are always mutated exhaustively; the middle of long
     * messages is sampled every {@code bigStride} bytes.
     */
    private void sweep(boolean mutual, boolean compress, int bigStride, int[] only) throws Exception {
        Outcome clean = run(mutual, compress, -1, null);
        assertTrue("clean client complete", clean.clientComplete);
        assertTrue("clean server complete", clean.serverComplete);
        assertFalse(clean.errored);
        int n = clean.trace.size();
        assertTrue(n >= 5);
        int[] types = { 0, 1, 2, 4, 5, 8, 11, 13, 15, 20, 24, 25, 254 };
        for (int m = 0; m < n; m++) {
            int msgType = clean.trace.get(m)[0] & 0xff;
            if (!contains(only, msgType)) {
                continue;
            }
            int len = clean.trace.get(m).length;
            int stride = (len > 200) ? bigStride : 1;
            for (int t = 0; t < len; t += (t < 12 || len - t < 12) ? 1 : stride) {
                Mutation cut = truncate(t);
                Outcome o = run(mutual, compress, m, cut);
                if (cut.shortened) {
                    assertFalse("truncated message " + m + " to " + t + " must not complete both",
                            o.clientComplete && o.serverComplete);
                }
            }
            for (int off = 0; off < len; off += (off < 12 || len - off < 12) ? 1 : stride) {
                run(mutual, compress, m, flip(off, 0xff));
                run(mutual, compress, m, flip(off, 0x01));
            }
            for (int i = 0; i < types.length; i++) {
                run(mutual, compress, m, retype(types[i]));
            }
            run(mutual, compress, m, append(1));
            run(mutual, compress, m, append(4));
            Outcome dropped = run(mutual, compress, m, drop());
            boolean optional = clean.trace.get(m)[0] == 4;
            if (!optional) {
                assertFalse("dropped message " + m, dropped.clientComplete && dropped.serverComplete);
            }
            run(mutual, compress, m, duplicate());
        }
    }

    @Test
    public void plainHandshakeSurvivesEveryMutation() throws Exception {
        sweep(false, false, 11, null);
    }

    @Test
    public void mutualHandshakeSurvivesEveryMutation() throws Exception {
        sweep(true, false, 23, new int[] { 11, 13, 15, 20 });
    }

    @Test
    public void compressedCertificateHandshakeSurvivesEveryMutation() throws Exception {
        sweep(false, true, 41, new int[] { 25 });
    }

    @Test
    public void helloRetryRequestHandshakeSurvivesEveryMutation() throws Exception {
        useHrr = true;
        sweep(false, false, 41, new int[] { 1, 2 });
    }

    @Test
    public void echHandshakeSurvivesEveryMutation() throws Exception {
        useEch = true;
        sweep(false, false, 41, new int[] { 1, 2, 8 });
    }

    @Test
    public void echHelloRetryRequestHandshakeSurvivesEveryMutation() throws Exception {
        useEch = true;
        useHrr = true;
        sweep(false, false, 41, new int[] { 1, 2, 8 });
    }

    @Test
    public void echGreaseHandshakeSurvivesEveryMutation() throws Exception {
        useGrease = true;
        useHrr = true;
        sweep(false, false, 41, new int[] { 1, 2 });
    }

    @Test
    public void resumedHandshakeSurvivesEveryMutation() throws Exception {
        useTickets = true;
        Outcome first = run(false, false, -1, null);
        assertTrue(first.clientComplete);
        assertNotNull("ticket issued", lastTicket);
        resumeTicket = lastTicket;
        sweep(false, false, 41, new int[] { 1, 2, 8, 20 });
    }

    @Test
    public void failedEngineStaysFailedAndReportsAgain() throws Exception {
        HandshakeEngine server = new HandshakeEngine(serverConfig(false, false));
        Sink sink = new Sink();
        server.processMessage(new byte[] { 1, 0 }, sink);
        assertTrue(server.isFailed());
        assertNotNull(sink.error);
        assertFalse(server.isComplete());
        sink.error = null;
        server.processMessage(new byte[] { 1, 0, 0, 0 }, sink);
        assertNotNull(sink.error);
        assertEquals(AlertDescription.UNEXPECTED_MESSAGE, sink.error.getAlert());
        assertTrue(server.isFailed());
    }
}
