/*
 * UdpEndpointDtlsInterleavingTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Drives one server-mode secure {@link UdpEndpoint} and two client-mode
 * ones against each other without sockets, delivering datagrams one at a
 * time so that the two peers' handshakes and application data interleave
 * at every datagram boundary, and checks that each peer gets only its own
 * session's data back. Also pins the threading contract: a secure
 * {@link UdpEndpoint#sendTo} from a thread other than the selector loop's
 * must be marshalled onto the loop rather than run on the caller's thread
 * concurrently with datagram processing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointDtlsInterleavingTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5000);
    private static final InetSocketAddress ADDR_ONE = new InetSocketAddress("127.0.0.1", 5001);
    private static final InetSocketAddress ADDR_TWO = new InetSocketAddress("127.0.0.1", 5002);

    private static TestCertificates.Identity identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        identity = TestCertificates.ec256();
    }

    private static final class Peer implements ProtocolHandler {
        final InetSocketAddress address;
        final boolean echo;
        UdpEndpoint endpoint;
        final List<String> received = new ArrayList<String>();
        final List<Object> repliedTo = new ArrayList<Object>();
        boolean secure;
        final List<Exception> errors = new ArrayList<Exception>();

        Peer(InetSocketAddress address, boolean echo) {
            this.address = address;
            this.echo = echo;
        }

        @Override
        public void receive(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            received.add(new String(bytes, StandardCharsets.UTF_8));
            if (echo) {
                repliedTo.add(endpoint.getRemoteAddress());
                endpoint.send(ByteBuffer.wrap(bytes));
            }
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secure = true;
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static UdpEndpoint newEndpoint(Peer peer, UdpTransportFactory factory,
            boolean clientMode, SelectorLoop loop) {
        UdpEndpoint ep = new UdpEndpoint(peer);
        ep.setFactory(factory);
        ep.setSecure(true);
        ep.setClientMode(clientMode);
        if (clientMode) {
            ep.setRemoteAddress(SERVER_ADDR);
        }
        ep.setSelectorLoop(loop);
        ep.init();
        peer.endpoint = ep;
        return ep;
    }

    private static UdpTransportFactory serverFactory(DtlsVersion version) {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setDtlsVersion(version);
        factory.setServerCredentials(identity.credentials());
        factory.start();
        return factory;
    }

    private static UdpTransportFactory clientFactory(DtlsVersion version) {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setDtlsVersion(version);
        factory.setTrustManager(TestCertificates.trustAll());
        factory.start();
        return factory;
    }

    /** One simulated network with the server and two clients. */
    private static final class Net {
        final UdpEndpoint server;
        final UdpEndpoint[] clients = new UdpEndpoint[2];
        final Peer serverPeer = new Peer(SERVER_ADDR, true);
        final Peer[] clientPeers = new Peer[] {
            new Peer(ADDR_ONE, false), new Peer(ADDR_TWO, false)
        };

        Net(DtlsVersion version, SelectorLoop clientLoop) {
            server = newEndpoint(serverPeer, serverFactory(version), false,
                    new InlineSelectorLoop());
            for (int i = 0; i < 2; i++) {
                clients[i] = newEndpoint(clientPeers[i], clientFactory(version), true, clientLoop);
            }
        }

        /**
         * Delivers one pending datagram from the given endpoint index
         * (0 = client one, 1 = client two, 2 = server), if it has one.
         *
         * @return true if a datagram was delivered
         */
        boolean deliverOne(int from) {
            UdpEndpoint sender = from == 2 ? server : clients[from];
            UdpEndpoint.PendingDatagram pending = sender.pendingDatagrams.poll();
            if (pending == null) {
                return false;
            }
            byte[] bytes = new byte[pending.data.remaining()];
            pending.data.get(bytes);
            sender.onPendingDatagramFullySent(pending);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            if (from == 2) {
                int to = pending.destination.equals(ADDR_ONE) ? 0 : 1;
                clients[to].netReceive(buf, SERVER_ADDR);
            } else {
                InetSocketAddress source = from == 0 ? ADDR_ONE : ADDR_TWO;
                server.netReceive(buf, source);
            }
            return true;
        }

        /**
         * Runs the network until quiescent. The first few deliveries are
         * steered by the bits of {@code schedule} (which of the two clients
         * to serve first, with the server draining after); once those bits
         * are used up it alternates fairly.
         */
        void pump(int schedule, int steps) {
            int step = 0;
            boolean progress = true;
            while (progress) {
                progress = false;
                int first;
                if (step < steps) {
                    first = (schedule >> step) & 1;
                } else {
                    first = step & 1;
                }
                step++;
                if (deliverOne(first)) {
                    progress = true;
                }
                if (deliverOne(1 - first)) {
                    progress = true;
                }
                if (step < steps) {
                    // interleave the server's reply between the two clients' sends
                    if (deliverOne(2)) {
                        progress = true;
                    }
                } else {
                    while (deliverOne(2)) {
                        progress = true;
                    }
                }
            }
        }
    }

    private void interleave(DtlsVersion version, int steps) {
        int schedules = 1 << steps;
        for (int schedule = 0; schedule < schedules; schedule++) {
            Net net = new Net(version, new InlineSelectorLoop());
            net.clients[0].startClientDtlsHandshake();
            net.clients[1].startClientDtlsHandshake();
            net.pump(schedule, steps);
            String label = version + " schedule " + schedule;
            assertTrue(label, net.clientPeers[0].secure);
            assertTrue(label, net.clientPeers[1].secure);
            assertTrue(label, net.clientPeers[0].errors.isEmpty());
            assertTrue(label, net.clientPeers[1].errors.isEmpty());
            assertTrue(label, net.serverPeer.secure);

            net.clients[0].sendTo(ByteBuffer.wrap("one".getBytes(StandardCharsets.UTF_8)), SERVER_ADDR);
            net.clients[1].sendTo(ByteBuffer.wrap("two".getBytes(StandardCharsets.UTF_8)), SERVER_ADDR);
            net.pump(schedule >> steps, steps);

            assertEquals(label, 2, net.serverPeer.received.size());
            assertTrue(label, net.serverPeer.received.contains("one"));
            assertTrue(label, net.serverPeer.received.contains("two"));
            for (int i = 0; i < net.serverPeer.received.size(); i++) {
                String got = net.serverPeer.received.get(i);
                InetSocketAddress expected = "one".equals(got) ? ADDR_ONE : ADDR_TWO;
                assertEquals(label, expected, net.serverPeer.repliedTo.get(i));
            }
            assertEquals(label, 1, net.clientPeers[0].received.size());
            assertEquals(label, "one", net.clientPeers[0].received.get(0));
            assertEquals(label, 1, net.clientPeers[1].received.size());
            assertEquals(label, "two", net.clientPeers[1].received.get(0));
            net.clients[0].close();
            net.clients[1].close();
            net.server.close();
        }
    }

    @Test
    public void negotiateTwoPeersInterleavedAtEveryDatagramBoundary() {
        interleave(DtlsVersion.NEGOTIATE, 5);
    }

    @Test
    public void dtls12TwoPeersInterleavedAtEveryDatagramBoundary() {
        interleave(DtlsVersion.DTLS_1_2, 3);
    }

    @Test
    public void dtls13TwoPeersInterleavedAtEveryDatagramBoundary() {
        interleave(DtlsVersion.DTLS_1_3, 3);
    }
}
