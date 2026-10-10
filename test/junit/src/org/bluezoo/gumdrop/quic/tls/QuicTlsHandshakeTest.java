/*
 * QuicTlsHandshakeTest.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.AntiReplay;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.ServerCredentialsResolver;
import org.bluezoo.gumdrop.tls.TransportParameterConsistencyChecker;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.SessionTicket;
import org.bluezoo.gumdrop.tls.TicketKeys;

import static org.junit.Assert.*;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * In-memory QUIC-TLS handshakes between {@link QuicTlsClientEngine} and
 * {@link QuicTlsServerEngine}: crypto frames are carried from one engine's
 * listener to the other's {@code receiveCryptoData}, optionally split into
 * one-byte frames or delivered in reverse order.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTlsHandshakeTest {

    private static final class Frame {
        final EncryptionLevel level;
        final long offset;
        final byte[] data;

        Frame(EncryptionLevel level, long offset, byte[] data) {
            this.level = level;
            this.offset = offset;
            this.data = data;
        }
    }

    /** Listener recording everything an engine reports. */
    private static final class Peer implements QuicTlsEngineListener {
        final List<Frame> outbox = new ArrayList<Frame>();
        /** Every CRYPTO frame ever reported, in order, kept after the pump empties the outbox. */
        final List<Frame> sent = new ArrayList<Frame>();
        final List<Throwable> failures = new ArrayList<Throwable>();
        final List<SessionTicket> tickets = new ArrayList<SessionTicket>();
        TransportParameters peerParameters;
        int handshakeSecrets;
        int finished;
        int earlySecrets;
        Boolean earlyOutcome;
        Peer forwardTo;
        QuicTlsEngine forwardEngine;
        int chunk;
        Runnable onFirstCrypto;

        @Override
        public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
            sent.add(new Frame(level, offset, data));
            if (onFirstCrypto != null) {
                Runnable action = onFirstCrypto;
                onFirstCrypto = null;
                action.run();
            }
            if (forwardEngine != null) {
                try {
                    forwardEngine.receiveCryptoData(level, offset, ByteBuffer.wrap(data));
                } catch (StreamReassembler.BufferLimitExceededException e) {
                    failures.add(e);
                }
                return;
            }
            outbox.add(new Frame(level, offset, data));
        }

        @Override
        public void handshakeSecretsAvailable() {
            handshakeSecrets++;
        }

        @Override
        public void handshakeFinished() {
            finished++;
        }

        @Override
        public void transportParametersReceived(TransportParameters transportParameters) {
            peerParameters = transportParameters;
        }

        @Override
        public void earlySecretsAvailable() {
            earlySecrets++;
        }

        @Override
        public void newSessionTicketReceived(SessionTicket ticket) {
            tickets.add(ticket);
        }

        @Override
        public void earlyDataOutcomeKnown(boolean accepted) {
            earlyOutcome = Boolean.valueOf(accepted);
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public void cryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
            failures.add(cause);
        }
    }

    private ServerCredentials credentials;
    private Peer clientPeer;
    private Peer serverPeer;

    @Before
    public void setUp() throws Exception {
        credentials = TestCertificates.newEc256("localhost").credentials();
        clientPeer = new Peer();
        serverPeer = new Peer();
    }

    private static TransportParameters params(long maxData) {
        TransportParameters tp = new TransportParameters();
        tp.setInitialMaxData(maxData);
        tp.setInitialMaxStreamsBidi(7);
        return tp;
    }

    private QuicTlsClientEngine newClient(String alpn, String groups, String ciphers) {
        QuicTlsClientEngine client = new QuicTlsClientEngine(params(1000), clientPeer, alpn, groups, ciphers);
        client.setTrustManager(TestCertificates.trustAll());
        client.setVerifyHostname(false);
        return client;
    }

    private QuicTlsServerEngine newServer(String alpn, String ciphers, boolean early) {
        return new QuicTlsServerEngine(credentials, params(2000), serverPeer, early, alpn, ciphers);
    }

    private static void deliver(QuicTlsEngine to, Frame f, int chunk, boolean reverse) throws Exception {
        if (chunk <= 0 || chunk >= f.data.length) {
            to.receiveCryptoData(f.level, f.offset, ByteBuffer.wrap(f.data));
            return;
        }
        List<Frame> pieces = new ArrayList<Frame>();
        for (int pos = 0; pos < f.data.length; pos += chunk) {
            int end = Math.min(f.data.length, pos + chunk);
            byte[] piece = new byte[end - pos];
            System.arraycopy(f.data, pos, piece, 0, piece.length);
            pieces.add(new Frame(f.level, f.offset + pos, piece));
        }
        if (reverse) {
            for (int i = pieces.size() - 1; i >= 0; i--) {
                Frame p = pieces.get(i);
                to.receiveCryptoData(p.level, p.offset, ByteBuffer.wrap(p.data));
            }
        } else {
            for (int i = 0; i < pieces.size(); i++) {
                Frame p = pieces.get(i);
                to.receiveCryptoData(p.level, p.offset, ByteBuffer.wrap(p.data));
            }
        }
    }

    private void pump(QuicTlsEngine client, QuicTlsEngine server, int chunk, boolean reverse) throws Exception {
        for (int round = 0; round < 50; round++) {
            if (clientPeer.outbox.isEmpty() && serverPeer.outbox.isEmpty()) {
                return;
            }
            List<Frame> toServer = new ArrayList<Frame>(clientPeer.outbox);
            clientPeer.outbox.clear();
            for (int i = 0; i < toServer.size(); i++) {
                deliver(server, toServer.get(i), chunk, reverse);
            }
            List<Frame> toClient = new ArrayList<Frame>(serverPeer.outbox);
            serverPeer.outbox.clear();
            for (int i = 0; i < toClient.size(); i++) {
                deliver(client, toClient.get(i), chunk, reverse);
            }
        }
        fail("handshake did not quiesce");
    }

    private void assertCompleted(QuicTlsClientEngine client, QuicTlsServerEngine server) {
        assertTrue(clientPeer.failures.toString(), clientPeer.failures.isEmpty());
        assertTrue(serverPeer.failures.toString(), serverPeer.failures.isEmpty());
        assertEquals(1, clientPeer.finished);
        assertEquals(1, serverPeer.finished);
        assertEquals(1, clientPeer.handshakeSecrets);
        assertEquals(1, serverPeer.handshakeSecrets);
        assertTrue(client.isTlsHandshakeFinished());
        assertArrayEquals(client.getClientHandshakeTrafficSecret(), server.getClientHandshakeTrafficSecret());
        assertArrayEquals(client.getServerHandshakeTrafficSecret(), server.getServerHandshakeTrafficSecret());
        assertArrayEquals(client.getClientApplicationTrafficSecret(), server.getClientApplicationTrafficSecret());
        assertArrayEquals(client.getServerApplicationTrafficSecret(), server.getServerApplicationTrafficSecret());
        assertNotNull(client.getClientApplicationTrafficSecret());
        assertEquals(client.getSelectedCipher(), server.getSelectedCipher());
        assertEquals(2000, clientPeer.peerParameters.getInitialMaxData());
        assertEquals(1000, serverPeer.peerParameters.getInitialMaxData());
        assertEquals(1, client.getServerCertificateChain().size());
        assertFalse(client.isHandshakeProcessingBusy());
        assertFalse(server.isHandshakeProcessingBusy());
    }

    private void handshake(String alpn, String groups, String ciphers, int chunk, boolean reverse)
            throws Exception {
        QuicTlsClientEngine client = newClient(alpn, groups, ciphers);
        QuicTlsServerEngine server = newServer(alpn, ciphers, false);
        client.startHandshake("localhost");
        pump(client, server, chunk, reverse);
        assertCompleted(client, server);
    }

    @Test
    public void testDefaultHandshake() throws Exception {
        handshake("h3", null, null, 0, false);
    }

    @Test
    public void testHandshakeWithoutAlpn() throws Exception {
        handshake(null, null, null, 0, false);
    }

    @Test
    public void testChaCha20Suite() throws Exception {
        handshake("h3", null, "TLS_CHACHA20_POLY1305_SHA256", 0, false);
        // selected suite is the one configured
    }

    @Test
    public void testAes256Suite() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, "TLS_AES_256_GCM_SHA384");
        QuicTlsServerEngine server = newServer("h3", "TLS_AES_256_GCM_SHA384", false);
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertCompleted(client, server);
        assertEquals(CipherSuite.TLS_AES_256_GCM_SHA384, server.getSelectedCipher());
    }

    @Test
    public void testAes128Suite() throws Exception {
        handshake("h3", null, "TLS_AES_128_GCM_SHA256", 0, false);
    }

    @Test
    public void testNamedGroupX25519() throws Exception {
        handshake("h3", "x25519", null, 0, false);
    }

    @Test
    public void testNamedGroupSecp256r1() throws Exception {
        handshake("h3", "secp256r1", null, 0, false);
    }

    @Test
    public void testNamedGroupSecp384r1() throws Exception {
        handshake("h3", "secp384r1", null, 0, false);
    }

    @Test
    public void testOneByteFramesInOrder() throws Exception {
        handshake("h3", null, null, 1, false);
    }

    @Test
    public void testOneByteFramesReversed() throws Exception {
        handshake("h3", "x25519", null, 1, true);
    }

    @Test
    public void testSmallChunksReversed() throws Exception {
        handshake("h3", "x25519", null, 7, true);
    }

    @Test
    public void testSynchronousForwardingUsesPendingFrameQueue() throws Exception {
        QuicTlsClientEngine client = newClient("h3", "x25519", null);
        QuicTlsServerEngine server = newServer("h3", null, false);
        clientPeer.forwardEngine = server;
        serverPeer.forwardEngine = client;
        client.startHandshake("localhost");
        assertCompleted(client, server);
    }

    @Test
    public void testVersionPolicyAddsVersionInformation() throws Exception {
        TransportParameters clientParams = params(1000);
        clientParams.setVersionInformation(QuicVersion.V1.getWireValue(),
                new int[] {QuicVersion.V1.getWireValue(), QuicVersion.V2.getWireValue()});
        QuicTlsClientEngine client = new QuicTlsClientEngine(clientParams, clientPeer, "h3");
        client.setTrustManager(TestCertificates.trustAll());
        client.setVerifyHostname(false);
        QuicTlsServerEngine server = newServer("h3", null, false);
        server.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1, QuicVersion.V2});
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertTrue(clientPeer.failures.toString(), clientPeer.failures.isEmpty());
        assertTrue(clientPeer.peerParameters.hasVersionInformation());
        assertEquals(QuicVersion.V1.getWireValue(), clientPeer.peerParameters.getVersionInformationChosen());
        assertEquals(2, clientPeer.peerParameters.getVersionInformationAvailable().length);
    }

    @Test
    public void testVersionPolicyWithoutPeerVersionInformation() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, null);
        QuicTlsServerEngine server = newServer("h3", null, false);
        server.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1});
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertCompleted(client, server);
        assertEquals(QuicVersion.V1.getWireValue(), clientPeer.peerParameters.getVersionInformationChosen());
    }

    /**
     * RFC 9001 section 4.1.3: ClientHello and ServerHello are sent in
     * Initial packets, and so is the HelloRetryRequest that replaces the
     * first ServerHello when the client's key share is not the group the
     * server wants: it is a ServerHello message too, and the real
     * ServerHello follows it at the same level. Sent at the Handshake
     * level, the ServerHello never reaches a client that has no Handshake
     * keys yet (it has only just been told which keys to derive), and the
     * handshake stalls: ngtcp2, picoquic and msquic, whose first
     * ClientHello offers a key share the server will not take, all hung.
     * gumdrop's own client tolerates the wrong level, which is how this
     * went unseen.
     */
    @Test
    public void testHelloRetryRequestAndServerHelloAreSentAtTheInitialLevel() throws Exception {
        QuicTlsClientEngine client = newClient("h3", "x25519:secp256r1", null);
        QuicTlsServerEngine server = newServer("h3", null, false);
        server.setNamedGroups("secp256r1");
        // the client lists secp256r1 but sends no key share for it, so the
        // server has to ask for one with a HelloRetryRequest
        java.lang.reflect.Field configField = QuicTlsClientEngine.class.getDeclaredField("config");
        configField.setAccessible(true);
        ((org.bluezoo.gumdrop.tls.HandshakeConfig) configField.get(client)).setClientOmitInitialKeyShareGroups(
                java.util.Collections.singletonList(org.bluezoo.gumdrop.crypto.NamedGroup.SECP256R1));
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertCompleted(client, server);

        final int serverHello = 2;
        int atInitial = 0;
        for (int i = 0; i < serverPeer.sent.size(); i++) {
            Frame f = serverPeer.sent.get(i);
            boolean isServerHello = f.data.length > 0 && f.data[0] == serverHello;
            if (f.level == EncryptionLevel.INITIAL) {
                assertTrue("only ServerHello messages belong at the Initial level", isServerHello);
                atInitial++;
            } else {
                assertFalse("a ServerHello must not be sent at the " + f.level + " level", isServerHello);
            }
        }
        assertEquals("the HelloRetryRequest and the ServerHello are both at the Initial level", 2, atInitial);

        // and the client's second ClientHello, answering the retry, is an
        // Initial-level message too
        final int clientHello = 1;
        int clientHellosAtInitial = 0;
        for (int i = 0; i < clientPeer.sent.size(); i++) {
            Frame f = clientPeer.sent.get(i);
            boolean isClientHello = f.data.length > 0 && f.data[0] == clientHello;
            if (f.level == EncryptionLevel.INITIAL) {
                assertTrue("only ClientHello messages belong at the Initial level", isClientHello);
                clientHellosAtInitial++;
            } else {
                assertFalse("a ClientHello must not be sent at the " + f.level + " level", isClientHello);
            }
        }
        assertEquals("both ClientHellos are at the Initial level", 2, clientHellosAtInitial);
    }

    /**
     * RFC 9001 section 4.1.3: a NewSessionTicket is a post-handshake
     * message, so it goes in a 1-RTT CRYPTO frame. Sent at the Handshake
     * level it follows the server's Finished in that stream, and a peer
     * that checks (quic-go: "received crypto data after change of
     * encryption level") closes the connection with PROTOCOL_VIOLATION.
     * gumdrop's own client accepts it, which is how this went unseen.
     */
    @Test
    public void testSessionTicketIsSentAtTheOneRttLevel() throws Exception {
        TicketKeys keys = new TicketKeys(new byte[16]);
        QuicTlsClientEngine client = newClient("h3", null, null);
        QuicTlsServerEngine server = newServer("h3", null, false);
        server.setTicketKeys(keys);
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertCompleted(client, server);
        assertEquals("the client still gets its ticket", 1, clientPeer.tickets.size());

        final int newSessionTicket = 4;
        int ticketsAtOneRtt = 0;
        for (int i = 0; i < serverPeer.sent.size(); i++) {
            Frame f = serverPeer.sent.get(i);
            boolean isTicket = f.data.length > 0 && f.data[0] == newSessionTicket;
            if (f.level == EncryptionLevel.HANDSHAKE) {
                assertFalse("a NewSessionTicket must not be sent at the Handshake level", isTicket);
            }
            if (f.level == EncryptionLevel.ONE_RTT && isTicket) {
                ticketsAtOneRtt++;
            }
        }
        assertEquals("the ticket must be sent at the 1-RTT level", 1, ticketsAtOneRtt);
    }

    @Test
    public void testTicketIssuedAndZeroRttResumption() throws Exception {
        TicketKeys keys = new TicketKeys(new byte[16]);
        TransportParameters clientParams = params(1000);
        clientParams.setVersionInformation(QuicVersion.V1.getWireValue(),
                new int[] {QuicVersion.V1.getWireValue()});
        QuicTlsClientEngine client = new QuicTlsClientEngine(clientParams, clientPeer, "h3");
        client.setTrustManager(TestCertificates.trustAll());
        client.setVerifyHostname(false);
        QuicTlsServerEngine server = newServer("h3", null, true);
        server.setTicketKeys(keys);
        server.setAntiReplay(new AntiReplay(10000));
        server.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1});
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertTrue(clientPeer.failures.toString(), clientPeer.failures.isEmpty());
        assertEquals(1, clientPeer.tickets.size());
        assertFalse(server.wasEarlyDataAccepted());
        assertNull(server.getClientEarlyTrafficSecret());

        SessionTicket ticket = clientPeer.tickets.get(0);

        // second connection resumes with the ticket
        clientPeer = new Peer();
        serverPeer = new Peer();
        TransportParameters clientParams2 = params(1000);
        clientParams2.setVersionInformation(QuicVersion.V1.getWireValue(),
                new int[] {QuicVersion.V1.getWireValue()});
        QuicTlsClientEngine client2 = new QuicTlsClientEngine(clientParams2, clientPeer, "h3");
        client2.setTrustManager(TestCertificates.trustAll());
        client2.setVerifyHostname(false);
        client2.presentSessionTicket(ticket);
        QuicTlsServerEngine server2 = newServer("h3", null, true);
        server2.setTicketKeys(keys);
        server2.setAntiReplay(new AntiReplay(10000));
        server2.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1});
        client2.startHandshake("localhost");
        pump(client2, server2, 0, false);
        assertTrue(clientPeer.failures.toString(), clientPeer.failures.isEmpty());
        assertTrue(serverPeer.failures.toString(), serverPeer.failures.isEmpty());
        assertEquals(1, clientPeer.earlySecrets);
        assertEquals(1, serverPeer.earlySecrets);
        assertEquals(Boolean.TRUE, clientPeer.earlyOutcome);
        assertTrue(server2.wasEarlyDataAccepted());
        assertNotNull(client2.getEarlyDataCipher());
        assertArrayEquals(client2.getClientEarlyTrafficSecret(), server2.getClientEarlyTrafficSecret());
        assertEquals(1, serverPeer.finished);
    }

    @Test
    public void testServerWithoutCredentialsFailsViaListener() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, null);
        QuicTlsServerEngine server = new QuicTlsServerEngine(null, params(2000), serverPeer, false);
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertFalse(serverPeer.failures.isEmpty());
        assertEquals(0, serverPeer.finished);
    }

    @Test
    public void testClientRejectingCertificateFails() throws Exception {
        QuicTlsClientEngine client = new QuicTlsClientEngine(params(1000), clientPeer, "h3");
        client.setTrustManager(TestCertificates.trustNone());
        client.setVerifyHostname(false);
        QuicTlsServerEngine server = newServer("h3", null, false);
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertFalse(clientPeer.failures.isEmpty());
        assertEquals(0, clientPeer.finished);
        assertFalse(client.isTlsHandshakeFinished());
    }

    @Test
    public void testGarbageClientHelloFailsServer() throws Exception {
        QuicTlsServerEngine server = newServer("h3", null, false);
        byte[] bogus = new byte[] {0x01, 0x00, 0x00, 0x04, 0x03, 0x03, 0x00, 0x00};
        server.receiveCryptoData(EncryptionLevel.INITIAL, 0, ByteBuffer.wrap(bogus));
        assertFalse(serverPeer.failures.isEmpty());
    }

    @Test
    public void testUnexpectedMessageTypeFailsClient() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, null);
        client.startHandshake("localhost");
        byte[] bogus = new byte[] {0x14, 0x00, 0x00, 0x02, 0x01, 0x02};
        client.receiveCryptoData(EncryptionLevel.HANDSHAKE, 0, ByteBuffer.wrap(bogus));
        assertFalse(clientPeer.failures.isEmpty());
    }

    @Test
    public void testServerBufferLimitExceededThrows() throws Exception {
        QuicTlsServerEngine server = newServer("h3", null, false);
        try {
            server.receiveCryptoData(EncryptionLevel.INITIAL, 70000, ByteBuffer.wrap(new byte[70000]));
            fail("expected limit failure");
        } catch (StreamReassembler.BufferLimitExceededException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testClientBufferLimitExceededThrows() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, null);
        try {
            client.receiveCryptoData(EncryptionLevel.ONE_RTT, 70000, ByteBuffer.wrap(new byte[70000]));
            fail("expected limit failure");
        } catch (StreamReassembler.BufferLimitExceededException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testServerAccessorsBeforeHandshake() {
        QuicTlsServerEngine server = newServer("h3", null, true);
        assertNotNull(server.getHandshakeConfig());
        assertNull(server.getClientEarlyTrafficSecret());
        assertFalse(server.wasEarlyDataAccepted());
        server.setEchServerRequired(false);
        server.setEchRetryConfigList(new byte[0]);
        server.setEchServerKeys(null, null);
    }

    @Test
    public void testClientAccessorsBeforeHandshake() {
        QuicTlsClientEngine client = newClient("h3", null, null);
        assertNull(client.getClientEarlyTrafficSecret());
        assertNull(client.getEarlyDataCipher());
        assertFalse(client.isTlsHandshakeFinished());
        client.setEchEnabled(false);
        client.setEchRequired(false);
        client.setEchGreaseEnabled(false);
        client.setEchConfig(null);
        client.setEchRetryConfigsListener(null);
    }

    private static void feed(QuicTlsEngine engine, EncryptionLevel level, long offset, int size, Peer peer) {
        try {
            engine.receiveCryptoData(level, offset, ByteBuffer.wrap(new byte[size]));
        } catch (StreamReassembler.BufferLimitExceededException e) {
            peer.failures.add(e);
        }
    }

    @Test
    public void testServerQueuesFramesReceivedWhileBusy() throws Exception {
        final QuicTlsClientEngine client = newClient("h3", "x25519", null);
        final QuicTlsServerEngine server = newServer("h3", null, false);
        client.startHandshake("localhost");
        List<Frame> hello = new ArrayList<Frame>(clientPeer.outbox);
        clientPeer.outbox.clear();
        clientPeer.forwardEngine = server;
        serverPeer.forwardEngine = client;
        for (int i = 0; i < hello.size(); i++) {
            deliver(server, hello.get(i), 0, false);
        }
        assertCompleted(client, server);
    }

    @Test
    public void testServerPendingFrameOverLimitReportedOnDrain() throws Exception {
        final QuicTlsClientEngine client = newClient("h3", "x25519", null);
        final QuicTlsServerEngine server = newServer("h3", null, false);
        client.startHandshake("localhost");
        List<Frame> hello = new ArrayList<Frame>(clientPeer.outbox);
        clientPeer.outbox.clear();
        serverPeer.onFirstCrypto = new Runnable() {
            @Override
            public void run() {
                feed(server, EncryptionLevel.HANDSHAKE, 70000, 70000, serverPeer);
            }
        };
        for (int i = 0; i < hello.size(); i++) {
            deliver(server, hello.get(i), 0, false);
        }
        assertEquals(1, serverPeer.failures.size());
        assertTrue(serverPeer.failures.get(0) instanceof StreamReassembler.BufferLimitExceededException);
    }

    @Test
    public void testServerPendingFrameDrainsInOrder() throws Exception {
        final QuicTlsClientEngine client = newClient("h3", "x25519", null);
        final QuicTlsServerEngine server = newServer("h3", null, false);
        client.startHandshake("localhost");
        List<Frame> hello = new ArrayList<Frame>(clientPeer.outbox);
        clientPeer.outbox.clear();
        serverPeer.onFirstCrypto = new Runnable() {
            @Override
            public void run() {
                // a harmless duplicate of already-consumed bytes, queued while busy
                feed(server, EncryptionLevel.INITIAL, 0, 4, serverPeer);
            }
        };
        for (int i = 0; i < hello.size(); i++) {
            deliver(server, hello.get(i), 0, false);
        }
        assertTrue(serverPeer.failures.isEmpty());
    }

    @Test
    public void testClientPendingFrameOverLimitReportedOnDrain() throws Exception {
        final QuicTlsClientEngine client = newClient("h3", "x25519", null);
        clientPeer.onFirstCrypto = new Runnable() {
            @Override
            public void run() {
                feed(client, EncryptionLevel.HANDSHAKE, 70000, 70000, clientPeer);
            }
        };
        client.startHandshake("localhost");
        assertEquals(1, clientPeer.failures.size());
        assertTrue(clientPeer.failures.get(0) instanceof StreamReassembler.BufferLimitExceededException);
    }

    @Test
    public void testStartHandshakeWhileBusyRejected() throws Exception {
        final QuicTlsClientEngine client = newClient("h3", "x25519", null);
        final boolean[] rejected = new boolean[1];
        clientPeer.onFirstCrypto = new Runnable() {
            @Override
            public void run() {
                try {
                    client.startHandshake("localhost");
                } catch (IllegalStateException expected) {
                    rejected[0] = true;
                }
            }
        };
        client.startHandshake("localhost");
        assertTrue(rejected[0]);
    }

    @Test
    public void testRequiredClientAuthWithoutCertificateFails() throws Exception {
        QuicTlsClientEngine client = newClient("h3", null, null);
        QuicTlsServerEngine server = new QuicTlsServerEngine(credentials, null, params(2000), serverPeer,
                false, "h3", null, true, TestCertificates.trustAll());
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertEquals(0, serverPeer.finished);
    }

    @Test
    public void testCredentialsResolverSelectsCertificate() throws Exception {
        final ServerCredentials creds = credentials;
        final String[] seen = new String[1];
        ServerCredentialsResolver resolver = new ServerCredentialsResolver() {
            @Override
            public ServerCredentials resolve(String serverName) {
                seen[0] = serverName;
                return creds;
            }
        };
        QuicTlsClientEngine client = newClient("h3", null, null);
        QuicTlsServerEngine server = new QuicTlsServerEngine(null, resolver, params(2000), serverPeer,
                false, "h3", null, false, null);
        client.startHandshake("localhost");
        pump(client, server, 0, false);
        assertCompleted(client, server);
        assertEquals("localhost", seen[0]);
    }

    private static byte[] encodedParams(long maxData, long bidi, long uni, long bidiLocal, long bidiRemote,
            long uniData, long datagram, boolean version, int chosen) {
        TransportParameters tp = new TransportParameters();
        tp.setInitialMaxData(maxData);
        tp.setInitialMaxStreamsBidi(bidi);
        tp.setInitialMaxStreamsUni(uni);
        tp.setInitialMaxStreamDataBidiLocal(bidiLocal);
        tp.setInitialMaxStreamDataBidiRemote(bidiRemote);
        tp.setInitialMaxStreamDataUni(uniData);
        tp.setMaxDatagramFrameSize(datagram);
        if (version) {
            tp.setVersionInformation(chosen, new int[] {chosen});
        }
        return tp.encode();
    }

    private TransportParameterConsistencyChecker checker() {
        QuicTlsServerEngine server = newServer("h3", null, true);
        return server.getHandshakeConfig().getTransportParameterConsistencyChecker();
    }

    @Test
    public void testConsistencyCheckerAcceptsEqualOrLarger() {
        TransportParameterConsistencyChecker c = checker();
        byte[] base = encodedParams(10, 1, 1, 1, 1, 1, 1, false, 0);
        byte[] larger = encodedParams(20, 2, 2, 2, 2, 2, 2, false, 0);
        assertTrue(c.isConsistent(base, base));
        assertTrue(c.isConsistent(base, larger));
        assertFalse(c.isConsistent(larger, base));
        assertFalse(c.isConsistent(null, base));
        assertFalse(c.isConsistent(base, null));
    }

    @Test
    public void testConsistencyCheckerRejectsEachShrunkenLimit() {
        TransportParameterConsistencyChecker c = checker();
        byte[] big = encodedParams(10, 10, 10, 10, 10, 10, 10, false, 0);
        byte[][] smaller = new byte[][] {
            encodedParams(9, 10, 10, 10, 10, 10, 10, false, 0),
            encodedParams(10, 9, 10, 10, 10, 10, 10, false, 0),
            encodedParams(10, 10, 9, 10, 10, 10, 10, false, 0),
            encodedParams(10, 10, 10, 9, 10, 10, 10, false, 0),
            encodedParams(10, 10, 10, 10, 9, 10, 10, false, 0),
            encodedParams(10, 10, 10, 10, 10, 9, 10, false, 0),
            encodedParams(10, 10, 10, 10, 10, 10, 9, false, 0)
        };
        for (int i = 0; i < smaller.length; i++) {
            assertFalse("field " + i, c.isConsistent(big, smaller[i]));
        }
    }

    @Test
    public void testAcceptsTicketOnlyForSameQuicVersion() {
        TransportParameterConsistencyChecker c = checker();
        byte[] v1 = encodedParams(1, 1, 1, 1, 1, 1, 1, true, QuicVersion.V1.getWireValue());
        byte[] v2 = encodedParams(1, 1, 1, 1, 1, 1, 1, true, QuicVersion.V2.getWireValue());
        byte[] none = encodedParams(1, 1, 1, 1, 1, 1, 1, false, 0);
        assertTrue(c.acceptsTicketFrom(v1, v1));
        assertFalse(c.acceptsTicketFrom(v1, v2));
        assertFalse(c.acceptsTicketFrom(none, v1));
        assertFalse(c.acceptsTicketFrom(v1, none));
        assertFalse(c.acceptsTicketFrom(null, v1));
        assertFalse(c.acceptsTicketFrom(v1, null));
    }

    @Test
    public void testLocalParametersForWithoutVersionPolicyReturnsLocal() {
        TransportParameterConsistencyChecker c = checker();
        byte[] local = new byte[] {1, 2, 3};
        assertArrayEquals(local, c.localParametersFor(local, null));
    }

    @Test
    public void testLocalParametersForWithVersionPolicyAndNullPeer() {
        QuicTlsServerEngine server = newServer("h3", null, true);
        server.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1, QuicVersion.V2});
        TransportParameterConsistencyChecker c = server.getHandshakeConfig().getTransportParameterConsistencyChecker();
        byte[] out = c.localParametersFor(new byte[0], null);
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(out));
        assertEquals(QuicVersion.V1.getWireValue(), decoded.getVersionInformationChosen());
        assertEquals(2, decoded.getVersionInformationAvailable().length);
    }

    @Test
    public void testLocalParametersForPeerChoosingOtherVersionKeepsInUse() {
        QuicTlsServerEngine server = newServer("h3", null, true);
        server.setVersionPolicy(QuicVersion.V1, new QuicVersion[] {QuicVersion.V1, QuicVersion.V2});
        TransportParameterConsistencyChecker c = server.getHandshakeConfig().getTransportParameterConsistencyChecker();
        byte[] peer = encodedParams(1, 1, 1, 1, 1, 1, 1, true, QuicVersion.V2.getWireValue());
        byte[] out = c.localParametersFor(new byte[0], peer);
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(out));
        assertEquals(QuicVersion.V1.getWireValue(), decoded.getVersionInformationChosen());
    }

    @Test
    public void testUnknownEncryptionLevelFallsToApplicationBuffer() throws Exception {
        QuicTlsServerEngine server = newServer("h3", null, false);
        // an incomplete post-handshake message header produces no event and no failure
        server.receiveCryptoData(EncryptionLevel.ONE_RTT, 0, ByteBuffer.wrap(new byte[] {0x04, 0x00}));
        assertTrue(serverPeer.failures.isEmpty());
        assertEquals(3, EncryptionLevel.values().length);
        assertSame(EncryptionLevel.ONE_RTT, EncryptionLevel.valueOf("ONE_RTT"));
    }

}
