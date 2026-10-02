/*
 * SocksClientUdpAssociateMockTest.java
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

package org.bluezoo.gumdrop.socks.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.*;

/**
 * UDP ASSOCIATE of {@link SocksClientHandler} against a mock {@link
 * UdpTransportFactory} that records the connect and hands the relay handler
 * an in-memory endpoint, so no datagram socket is opened. The same handshake
 * and datagram flows run over a real loopback relay in {@code
 * SocksClientHandlerFlowsIntegrationTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksClientUdpAssociateMockTest {

    /** Records the relay connect and lets the test complete it. */
    private static final class MockUdpFactory extends UdpTransportFactory {
        final List<InetAddress> addresses = new ArrayList<InetAddress>();
        final List<Integer> ports = new ArrayList<Integer>();
        final List<ProtocolHandler> handlers = new ArrayList<ProtocolHandler>();
        IOException failure;

        @Override
        public UdpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port,
                ProtocolHandler handler, SelectorLoop loop) throws IOException {
            if (failure != null) {
                throw failure;
            }
            addresses.add(host);
            ports.add(Integer.valueOf(port));
            handlers.add(handler);
            return null;
        }
    }

    private static final class UdpListener
            implements SocksClientHandler.UdpAssociateListener {
        final List<InetSocketAddress> associated = new ArrayList<InetSocketAddress>();
        final List<String> datagrams = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void associated(InetSocketAddress relayAddress) {
            associated.add(relayAddress);
        }

        @Override
        public void receive(InetSocketAddress source, ByteBuffer payload) {
            byte[] b = new byte[payload.remaining()];
            payload.get(b);
            datagrams.add(source.getPort() + ":"
                    + new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    /** A control connection with a loop, as the real transport provides. */
    private static final class LoopEndpoint extends StubEndpoint {
        private final SelectorLoop loop;

        LoopEndpoint(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }
    }

    private MockUdpFactory factory;
    private UdpListener listener;
    private LoopEndpoint control;
    private SocksClientHandler handler;

    @Before
    public void setUp() {
        Gumdrop gumdrop = TestGumdrop.create();
        factory = new MockUdpFactory();
        listener = new UdpListener();
        control = new LoopEndpoint(gumdrop.nextWorkerLoop());
        handler = new SocksClientHandler(new SocksClientConfig(), factory, listener);
        handler.connected(control);
    }

    private static ByteBuffer bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return ByteBuffer.wrap(b);
    }

    /** Completes the SOCKS5 handshake with a relay at 127.0.0.1:{@code port}. */
    private void associate(int port) {
        handler.receive(bytes(5, 0));
        handler.receive(bytes(5, 0, 0, 1, 127, 0, 0, 1, port >> 8, port));
    }

    /** Hands the relay handler an in-memory endpoint, as a completed connect. */
    private StubEndpoint relayConnected() {
        StubEndpoint relay = new StubEndpoint();
        factory.handlers.get(0).connected(relay);
        return relay;
    }

    @Test
    public void associationConnectsToTheRelayAddress() {
        associate(40100);
        assertEquals(1, factory.handlers.size());
        assertEquals("127.0.0.1", factory.addresses.get(0).getHostAddress());
        assertEquals(40100, factory.ports.get(0).intValue());
        assertTrue(listener.associated.isEmpty());
        relayConnected();
        assertEquals(1, listener.associated.size());
        assertEquals(40100, listener.associated.get(0).getPort());
    }

    @Test
    public void datagramsAreFramedWithTheRfc1928Header() {
        associate(40100);
        StubEndpoint relay = relayConnected();
        handler.sendDatagram(new InetSocketAddress("192.0.2.5", 53),
                ByteBuffer.wrap(new byte[] {'q'}));
        byte[] sent = relay.getLastSent();
        assertEquals(11, sent.length);
        assertEquals(1, sent[3]);
        assertEquals((byte) 192, sent[4]);
        assertEquals('q', sent[10]);
    }

    @Test(expected = IllegalStateException.class)
    public void sendBeforeAssociationFails() {
        handler.sendDatagram(new InetSocketAddress("192.0.2.5", 53),
                ByteBuffer.wrap(new byte[] {'q'}));
    }

    @Test(expected = IllegalStateException.class)
    public void sendBeforeTheRelayEndpointIsConnectedFails() {
        associate(40100);
        handler.sendDatagram(new InetSocketAddress("192.0.2.5", 53),
                ByteBuffer.wrap(new byte[] {'q'}));
    }

    @Test
    public void relayDatagramsAreUnwrappedAndFiltered() {
        associate(40100);
        relayConnected();
        ProtocolHandler relay = factory.handlers.get(0);
        byte[] fragment = new byte[] {0, 0, 1, 1, 10, 0, 0, 1, 0, 9, 'f'};
        byte[] named = new byte[] {0, 0, 0, 3, 1, 'h', 0, 9, 'n'};
        byte[] good = new byte[] {0, 0, 0, 1, 10, 0, 0, 1, 0, 9, 'g'};
        relay.receive(ByteBuffer.wrap(fragment));
        relay.receive(ByteBuffer.wrap(named));
        relay.receive(ByteBuffer.wrap(good));
        assertEquals(1, listener.datagrams.size());
        assertEquals("9:g", listener.datagrams.get(0));
    }

    @Test
    public void relayCloseAndErrorAreReported() {
        associate(40100);
        relayConnected();
        ProtocolHandler relay = factory.handlers.get(0);
        relay.securityEstablished(null);
        relay.disconnected();
        assertEquals(1, listener.errors.size());
        relay.error(new IOException("boom"));
        assertEquals(2, listener.errors.size());
        assertEquals("boom", listener.errors.get(1).getMessage());
    }

    @Test
    public void relayConnectFailureIsReportedAndClosesControl() {
        factory.failure = new IOException("no socket");
        associate(40100);
        assertEquals(1, listener.errors.size());
        assertEquals("no socket", listener.errors.get(0).getMessage());
        assertFalse(control.isOpen());
    }

    @Test
    public void controlDisconnectClosesTheRelayEndpoint() {
        associate(40100);
        StubEndpoint relay = relayConnected();
        handler.disconnected();
        assertFalse(relay.isOpen());
    }

    @Test
    public void rejectedAssociationIsReported() {
        handler.receive(bytes(5, 0));
        handler.receive(bytes(5, 5, 0, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(1, listener.errors.size());
        assertTrue(factory.handlers.isEmpty());
        assertFalse(control.isOpen());
    }

    @Test
    public void domainNameRelayAddressIsRefused() {
        handler.receive(bytes(5, 0));
        handler.receive(bytes(5, 0, 0, 3, 1, 'h', 0, 9));
        assertEquals(1, listener.errors.size());
        assertTrue(factory.handlers.isEmpty());
    }

    @Test
    public void ipv6RelayAddressIsAccepted() {
        handler.receive(bytes(5, 0));
        handler.receive(bytes(5, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x9C, 0x44));
        assertEquals(1, factory.handlers.size());
        assertEquals(0x9C44, factory.ports.get(0).intValue());
    }
}
