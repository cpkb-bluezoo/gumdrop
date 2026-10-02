/*
 * UdpEndpointShutdownTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.After;
import org.junit.Test;

/**
 * Closing a {@link UdpEndpoint} for shutdown: an orderly close writes the
 * datagrams still queued (a DTLS {@code close_notify} among them) to the
 * socket before it closes, an abort discards them. Observed on real
 * loopback sockets, where a datagram sent is already queued at the
 * receiver, so the negative case needs no waiting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointShutdownTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5000);
    private static final int GUARD_MS = 60000;
    private static final int ALERT = 21;

    private final List<DatagramChannel> channels = new ArrayList<DatagramChannel>();

    @After
    public void closeChannels() throws IOException {
        for (DatagramChannel channel : channels) {
            channel.close();
        }
    }

    private DatagramChannel bound() throws IOException {
        DatagramChannel dc = DatagramChannel.open();
        dc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        channels.add(dc);
        return dc;
    }

    private static final class Probe implements ProtocolHandler {
        boolean disconnected;
        boolean secure;

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
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
            disconnected = true;
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private UdpEndpoint plainEndpointTo(DatagramChannel peer, Probe probe) throws IOException {
        UdpEndpoint ep = new UdpEndpoint(probe);
        ep.setChannel(bound());
        ep.setClientMode(true);
        ep.setRemoteAddress((InetSocketAddress) peer.getLocalAddress());
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static String receiveText(DatagramChannel peer) throws IOException {
        peer.socket().setSoTimeout(GUARD_MS);
        byte[] buf = new byte[64];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        peer.socket().receive(packet);
        return new String(buf, 0, packet.getLength(), StandardCharsets.UTF_8);
    }

    private static ByteBuffer text(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void orderlyCloseWritesQueuedDatagramsBeforeClosing() throws Exception {
        DatagramChannel peer = bound();
        Probe probe = new Probe();
        UdpEndpoint ep = plainEndpointTo(peer, probe);
        ep.send(text("bye"));

        ep.closeForShutdown(true);

        assertEquals("bye", receiveText(peer));
        assertTrue(probe.disconnected);
        assertFalse(ep.isOpen());
    }

    @Test
    public void abortDiscardsQueuedDatagrams() throws Exception {
        DatagramChannel peer = bound();
        Probe probe = new Probe();
        UdpEndpoint ep = plainEndpointTo(peer, probe);
        ep.send(text("bye"));

        ep.closeForShutdown(false);

        peer.configureBlocking(false);
        assertNull("nothing reaches the peer on abort", peer.receive(ByteBuffer.allocate(64)));
        assertTrue(probe.disconnected);
        assertFalse(ep.isOpen());
    }

    private static UdpEndpoint dtlsEndpoint(Probe probe, UdpTransportFactory factory, boolean clientMode,
            DatagramChannel channel) {
        UdpEndpoint ep = new UdpEndpoint(probe);
        ep.setFactory(factory);
        ep.setSecure(true);
        ep.setClientMode(clientMode);
        if (clientMode) {
            ep.setRemoteAddress(SERVER_ADDR);
        }
        if (channel != null) {
            ep.setChannel(channel);
        }
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static void deliverAll(UdpEndpoint server, UdpEndpoint client, InetSocketAddress clientSeenByServer) {
        boolean progress = true;
        while (progress) {
            progress = false;
            UdpEndpoint.PendingDatagram fromClient = client.pendingDatagrams.poll();
            if (fromClient != null) {
                byte[] bytes = new byte[fromClient.data.remaining()];
                fromClient.data.get(bytes);
                client.onPendingDatagramFullySent(fromClient);
                server.netReceive(ByteBuffer.wrap(bytes), clientSeenByServer);
                progress = true;
            }
            UdpEndpoint.PendingDatagram fromServer = server.pendingDatagrams.poll();
            if (fromServer != null) {
                byte[] bytes = new byte[fromServer.data.remaining()];
                fromServer.data.get(bytes);
                server.onPendingDatagramFullySent(fromServer);
                client.netReceive(ByteBuffer.wrap(bytes), SERVER_ADDR);
                progress = true;
            }
        }
    }

    /** A handshaken DTLS 1.2 server endpoint on a real socket whose one client is {@code peer}. */
    private UdpEndpoint handshakenServer(DatagramChannel peer) throws Exception {
        TestCertificates.Identity identity = TestCertificates.ec256();
        UdpTransportFactory serverFactory = new UdpTransportFactory();
        serverFactory.setSecure(true);
        serverFactory.setDtlsVersion(DtlsVersion.DTLS_1_2);
        serverFactory.setServerCredentials(identity.credentials());
        serverFactory.start();
        UdpTransportFactory clientFactory = new UdpTransportFactory();
        clientFactory.setSecure(true);
        clientFactory.setDtlsVersion(DtlsVersion.DTLS_1_2);
        clientFactory.setTrustManager(TestCertificates.trustAll());
        clientFactory.start();

        Probe serverProbe = new Probe();
        Probe clientProbe = new Probe();
        UdpEndpoint server = dtlsEndpoint(serverProbe, serverFactory, false, bound());
        UdpEndpoint client = dtlsEndpoint(clientProbe, clientFactory, true, null);
        client.startClientDtlsHandshake();
        deliverAll(server, client, (InetSocketAddress) peer.getLocalAddress());
        assertTrue(serverProbe.secure);
        assertTrue(clientProbe.secure);
        return server;
    }

    @Test
    public void orderlyDtlsCloseSendsCloseNotifyOnTheWire() throws Exception {
        DatagramChannel peer = bound();
        UdpEndpoint server = handshakenServer(peer);

        server.closeForShutdown(true);

        peer.socket().setSoTimeout(GUARD_MS);
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        peer.socket().receive(packet);
        assertEquals("a DTLS alert record is on the wire", ALERT, buf[0] & 0xFF);
        assertFalse(server.isOpen());
    }

    @Test
    public void abortDtlsCloseSendsNothing() throws Exception {
        DatagramChannel peer = bound();
        UdpEndpoint server = handshakenServer(peer);

        server.closeForShutdown(false);

        peer.configureBlocking(false);
        assertNull("no close_notify on abort", peer.receive(ByteBuffer.allocate(2048)));
        assertFalse(server.isOpen());
    }
}
