/*
 * UdpEndpointChannelTest.java
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
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.StubDatagramChannel;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link UdpEndpoint} holding a channel with no network behind it: queueing,
 * the output ceiling, close and orderly shutdown flushing, server-mode
 * addressing, and admission of DTLS peers by the listener.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointChannelTest {

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 6000);
    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 6001);
    private static final InetSocketAddress OTHER = new InetSocketAddress("127.0.0.1", 6002);

    private static final class Loop extends SelectorLoop {
        int writeRequests;

        Loop() {
            super(0);
        }

        @Override
        public boolean tryInvokeLater(Runnable task) {
            task.run();
            return true;
        }

        @Override
        public void requestDatagramWrite(ChannelHandler handler) {
            writeRequests++;
        }
    }

    private static final class Recorder implements ProtocolHandler {
        final List<String> received = new ArrayList<String>();
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        UdpEndpoint endpoint;
        boolean secure;

        @Override
        public void receive(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            received.add(new String(b, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void connected(Endpoint ep) {
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secure = true;
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final class Counting extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "counting";
        }
    }

    private Loop loop;
    private StubDatagramChannel channel;
    private Recorder handler;
    private UdpEndpoint endpoint;

    @Before
    public void setUp() {
        loop = new Loop();
        channel = new StubDatagramChannel(LOCAL);
        handler = new Recorder();
        endpoint = new UdpEndpoint(handler);
        handler.endpoint = endpoint;
        endpoint.setChannel(channel);
        endpoint.setSelectorLoop(loop);
        endpoint.init();
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void addressesAndOpenStateComeFromTheChannel() {
        assertEquals(LOCAL, endpoint.getLocalAddress());
        assertTrue(endpoint.isOpen());
        endpoint.close();
        assertFalse(endpoint.isOpen());
        assertTrue(endpoint.isClosing());
        assertEquals(1, handler.events.size());
        endpoint.close();
        assertEquals(1, handler.events.size());
    }

    @Test
    public void serverModeRepliesToTheLastSource() {
        endpoint.netReceive(bytes("ping"), PEER);
        assertEquals("ping", handler.received.get(0));
        assertEquals(PEER, endpoint.getRemoteAddress());
        endpoint.send(bytes("pong"));
        UdpEndpoint.PendingDatagram p = endpoint.pendingDatagrams.peek();
        assertEquals(PEER, p.destination);
        assertTrue(loop.writeRequests > 0);
    }

    @Test
    public void serverModeSendWithoutAPeerIsRejected() {
        try {
            endpoint.send(bytes("x"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(endpoint.pendingDatagrams.isEmpty());
        }
    }

    @Test
    public void sendingNullClosesTheEndpoint() {
        endpoint.send(null);
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void startTlsIsNotSupported() throws Exception {
        try {
            endpoint.startTLS();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(endpoint.isOpen());
        }
    }

    @Test
    public void closeDiscardsQueuedDatagrams() {
        endpoint.sendTo(bytes("a"), PEER);
        endpoint.sendTo(bytes("b"), PEER);
        endpoint.close();
        assertTrue(channel.getSent().isEmpty());
        assertTrue(endpoint.pendingDatagrams.isEmpty());
    }

    @Test
    public void orderlyShutdownFlushesQueuedDatagramsInOrder() {
        endpoint.sendTo(bytes("first"), PEER);
        endpoint.sendTo(bytes("second"), OTHER);
        endpoint.closeForShutdown(true);
        assertEquals(2, channel.getSent().size());
        assertEquals("first", new String(channel.getSent().get(0).getBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(OTHER, channel.getSent().get(1).getDestination());
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void abortShutdownDiscardsQueuedDatagrams() {
        endpoint.sendTo(bytes("lost"), PEER);
        endpoint.closeForShutdown(false);
        assertTrue(channel.getSent().isEmpty());
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void orderlyShutdownKeepsGoingWhenAWriteFails() {
        channel.setFailOnSend(true);
        endpoint.sendTo(bytes("a"), PEER);
        endpoint.sendTo(bytes("b"), PEER);
        endpoint.closeForShutdown(true);
        assertTrue(endpoint.pendingDatagrams.isEmpty());
        assertEquals(1, handler.events.size());
    }

    @Test
    public void connectedClientFlushesUnaddressedDatagramsByWriting() {
        UdpEndpoint client = new UdpEndpoint(handler);
        client.setClientMode(true);
        client.setChannel(channel);
        client.setSelectorLoop(loop);
        client.init();
        client.send(bytes("hello"));
        assertNull(client.pendingDatagrams.peek().destination);
        client.closeForShutdown(true);
        assertEquals(1, channel.getSent().size());
        assertNull(channel.getSent().get(0).getDestination());
    }

    @Test
    public void flushOnAClosedChannelSendsNothing() throws Exception {
        endpoint.sendTo(bytes("a"), PEER);
        channel.close();
        endpoint.closeForShutdown(true);
        assertTrue(channel.getSent().isEmpty());
    }

    @Test
    public void channelCloseFailureDoesNotPreventShutdown() {
        channel.setFailOnClose(true);
        endpoint.close();
        assertTrue(endpoint.isClosing());
        assertEquals(1, handler.events.size());
    }

    @Test
    public void outputCeilingClosesTheEndpoint() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setMaxNetOutSize(10);
        endpoint.setFactory(f);
        endpoint.sendTo(bytes("12345"), PEER);
        assertFalse(endpoint.isClosing());
        endpoint.sendTo(bytes("1234567890"), PEER);
        assertTrue(endpoint.isClosing());
        assertTrue(endpoint.pendingDatagrams.isEmpty());
    }

    @Test
    public void secureServerWithoutDtlsConfigurationRefusesTheFirstDatagram() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(DtlsVersion.DTLS_1_2);
        f.start();
        endpoint.setFactory(f);
        endpoint.setSecure(true);
        try {
            endpoint.netReceive(bytes("hello"), PEER);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(handler.received.isEmpty());
        }
    }

    @Test
    public void listenerBlocksDtlsPeersBeforeASessionExists() throws Exception {
        DtlsVersion[] versions = new DtlsVersion[] {
            DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3, DtlsVersion.NEGOTIATE
        };
        for (int i = 0; i < versions.length; i++) {
            UdpTransportFactory f = new UdpTransportFactory();
            f.setSecure(true);
            f.setDtlsVersion(versions[i]);
            f.setServerCredentials(TestCertificates.ec256().credentials());
            f.start();
            Recorder h = new Recorder();
            UdpEndpoint ep = new UdpEndpoint(h);
            ep.setFactory(f);
            ep.setSecure(true);
            ep.setSelectorLoop(loop);
            ep.init();
            Counting listener = new Counting();
            List<CidrNetwork> blocked = new ArrayList<CidrNetwork>();
            blocked.add(new CidrNetwork("127.0.0.0/8"));
            listener.blockedNetworks(blocked);
            ep.setListener(listener);
            byte[] hello = new byte[] {22, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1};
            ep.netReceive(ByteBuffer.wrap(hello), PEER);
            assertTrue(versions[i].toString(), ep.pendingDatagrams.isEmpty());
            assertFalse(versions[i].toString(), h.secure);
        }
    }

    @Test
    public void clientHandshakeQueuesAClientHello() throws Exception {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(DtlsVersion.DTLS_1_2);
        f.setTrustManager(TestCertificates.trustAll());
        f.setMaxDtlsPeers(1);
        f.start();
        Recorder h = new Recorder();
        UdpEndpoint ep = new UdpEndpoint(h);
        ep.setFactory(f);
        ep.setSecure(true);
        ep.setClientMode(true);
        ep.setRemoteAddress(PEER);
        ep.setSelectorLoop(loop);
        ep.init();
        ep.startClientDtlsHandshake();
        assertTrue(h.errors.isEmpty());
        assertFalse(ep.pendingDatagrams.isEmpty());
    }
}
