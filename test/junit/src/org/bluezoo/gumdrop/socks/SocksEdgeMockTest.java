/*
 * SocksEdgeMockTest.java
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


package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.util.ByteBufferPool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.bluezoo.gumdrop.socks.SocksConstants.*;

/**
 * Edge cases of the SOCKS UDP relay, BIND relay, CONNECT relay and UDP header
 * codec driven directly over a {@link MockSocksTransport}: relays without an
 * idle timeout or metrics, events arriving after the relay has closed, ports
 * that closed before the relay, and truncated or unresolved UDP headers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksEdgeMockTest {

    /** The control connection, capturing timers. */
    private static final class Control extends StubEndpoint {
        final List<Runnable> timers = new ArrayList<Runnable>();
        SelectorLoop loop;

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            return super.scheduleTimer(delayMs, callback);
        }
    }

    /** BIND callback recording the outcome. */
    private static final class Recorder implements SocksBindRelay.Callback {
        int accepted;
        int failed;

        @Override
        public void bindAccepted(java.nio.channels.SocketChannel sc,
                InetSocketAddress peerAddress) {
            accepted++;
        }

        @Override
        public void bindFailed(byte replyCode) {
            failed++;
        }
    }

    private SocksServer server;
    private Control control;
    private MockSocksTransport transport;

    @Before
    public void setUp() {
        Gumdrop gumdrop = TestGumdrop.create();
        server = new SocksServer();
        server.acquireRelay();
        control = new Control();
        control.loop = gumdrop.nextWorkerLoop();
        transport = new MockSocksTransport();
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static InetSocketAddress client() {
        return new InetSocketAddress("127.0.0.1", 40000);
    }

    private static byte[] udp(byte[] addr4, int port, byte[] payload) {
        byte[] out = new byte[10 + payload.length];
        out[3] = SOCKS5_ATYP_IPV4;
        System.arraycopy(addr4, 0, out, 4, 4);
        out[8] = (byte) (port >> 8);
        out[9] = (byte) port;
        System.arraycopy(payload, 0, out, 10, payload.length);
        return out;
    }

    private SocksUdpRelay startRelay(SocksServerMetrics metrics, long idle) throws IOException {
        SocksUdpRelay relay = new SocksUdpRelay(transport, control, server,
                metrics, idle, loopback());
        relay.start();
        return relay;
    }

    // ── UDP relay ──

    @Test
    public void relayWithoutIdleTimeoutOrMetricsForwardsBothWays() throws Exception {
        SocksUdpRelay relay = startRelay(null, 0L);
        assertTrue(control.timers.isEmpty());
        byte[] request = udp(new byte[] {(byte) 192, 0, 2, 9}, 53, new byte[] {'q'});
        transport.clientPort().deliver(request, client());
        assertEquals(1, transport.upstreamPort().sent.size());
        transport.upstreamPort().deliver(new byte[] {'a'}, new InetSocketAddress("192.0.2.9", 53));
        assertEquals(1, transport.clientPort().sent.size());
        assertTrue(control.timers.isEmpty());
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void emptyUpstreamDatagramIsRelayedButNotCounted() throws Exception {
        SocksServerMetrics metrics = new SocksServerMetrics(new TelemetryConfig());
        startRelay(metrics, 1000L);
        byte[] request = udp(new byte[] {(byte) 192, 0, 2, 9}, 53, new byte[] {'q'});
        transport.clientPort().deliver(request, client());
        transport.upstreamPort().deliver(new byte[0], new InetSocketAddress("192.0.2.9", 53));
        assertEquals(1, transport.clientPort().sent.size());
        assertEquals(10, transport.clientPort().sent.get(0).bytes.length);
    }

    @Test
    public void closeWithPortsAlreadyClosedDoesNotCloseThemAgain() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        transport.clientPort().open = false;
        transport.upstreamPort().open = false;
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void closeWithOnlyUpstreamPortClosedClosesTheClientPort() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        transport.upstreamPort().open = false;
        relay.close();
        assertFalse(transport.clientPort().open);
    }

    @Test
    public void resolutionCompletingAfterCloseForwardsNothing() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        byte[] name = "example.test".getBytes("US-ASCII");
        byte[] request = new byte[7 + name.length + 1];
        request[3] = SOCKS5_ATYP_DOMAINNAME;
        request[4] = (byte) name.length;
        System.arraycopy(name, 0, request, 5, name.length);
        request[5 + name.length] = 0;
        request[6 + name.length] = 53;
        request[7 + name.length] = 'q';
        transport.clientPort().deliver(request, client());
        assertEquals(1, transport.resolves.size());
        ResolveCallback callback = transport.lastResolve().callback;
        relay.close();
        callback.onResolved(Arrays.asList(InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 9})));
        assertTrue(transport.upstreamPort().sent.isEmpty());
    }

    @Test
    public void upstreamDatagramAfterCloseIsDropped() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        byte[] request = udp(new byte[] {(byte) 192, 0, 2, 9}, 53, new byte[] {'q'});
        transport.clientPort().deliver(request, client());
        relay.close();
        transport.upstreamPort().deliver(new byte[] {'a'}, new InetSocketAddress("192.0.2.9", 53));
        assertTrue(transport.clientPort().sent.isEmpty());
    }

    @Test
    public void idleTimerFiringAfterCloseIsIgnored() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        assertEquals(1, control.timers.size());
        relay.close();
        control.timers.get(0).run();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void errorsAndDisconnectsOnOpenPortsCloseTheRelay() throws Exception {
        SocksUdpRelay relay = startRelay(null, 1000L);
        transport.upstreamPort().handler.error(new IOException("boom"));
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(transport.clientPort().open);
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }

    // ── BIND relay ──

    @Test
    public void bindRelayAcceptsAnyPeerWhenExpectedAddressIsWildcard() throws Exception {
        Recorder recorder = new Recorder();
        SocksBindRelay relay = new SocksBindRelay(transport, control, server, 0L,
                InetAddress.getByAddress(new byte[4]), recorder);
        relay.start();
        assertTrue(control.timers.isEmpty());
        StubSocketChannel channel = new StubSocketChannel(
                new InetSocketAddress("127.0.0.1", 45000), new InetSocketAddress("192.0.2.7", 41000));
        transport.lastListener().accept(channel);
        assertEquals(1, recorder.accepted);
        assertEquals(0, recorder.failed);
    }

    @Test
    public void bindTimeoutFiringAfterCloseIsIgnored() throws Exception {
        Recorder recorder = new Recorder();
        SocksBindRelay relay = new SocksBindRelay(transport, control, server, 500L, null, recorder);
        relay.start();
        assertEquals(1, control.timers.size());
        relay.close();
        control.timers.get(0).run();
        assertEquals(0, recorder.failed);
        assertTrue(transport.lastListener().closed);
    }

    // ── CONNECT relay ──

    @Test
    public void upstreamDisconnectWithClientAlreadyClosedClosesNeitherAgain() {
        Control upstream = new Control();
        SocksRelay relay = new SocksRelay(control, server, null, 0L);
        relay.upstreamConnected(upstream);
        control.close();
        relay.upstreamDisconnected();
        assertTrue(upstream.isOpen());
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void clientDisconnectWithUpstreamAlreadyClosedClosesNeitherAgain() {
        Control upstream = new Control();
        SocksRelay relay = new SocksRelay(control, server, null, 0L);
        relay.upstreamConnected(upstream);
        upstream.close();
        relay.clientDisconnected();
        assertEquals(0, server.getActiveRelayCount());
        assertTrue(control.isOpen());
    }

    // ── UDP header codec ──

    @Test
    public void truncatedDomainHeaderWithoutLengthByteIsLeftUnconsumed() {
        ByteBuffer data = ByteBuffer.wrap(new byte[] {0, 0, 0, SOCKS5_ATYP_DOMAINNAME});
        final boolean[] called = new boolean[1];
        SocksUDPHeader.parse(data, new SocksUDPHeader.Handler() {
            @Override
            public void datagram(byte frag, InetAddress address, String hostname,
                    int port, ByteBuffer payload) {
                called[0] = true;
            }
        });
        assertFalse(called[0]);
        assertEquals(0, data.position());
    }

    @Test
    public void encodeWithUnresolvedSourceUsesZeroIpv4Address() {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("nowhere.test", 99);
        assertNull(unresolved.getAddress());
        ByteBuffer encoded = SocksUDPHeader.encode(unresolved, ByteBuffer.wrap(new byte[] {7}));
        try {
            assertEquals(SOCKS5_ATYP_IPV4, encoded.get(3));
            assertEquals(0, encoded.get(4));
            assertEquals(99, encoded.get(9));
            assertEquals(7, encoded.get(10));
        } finally {
            ByteBufferPool.release(encoded);
        }
    }
}
