/*
 * SocksLiveLoopIntegrationTest.java
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SocksConstants.*;

/**
 * Drives {@link SocksProtocolHandler} against a live worker loop with
 * loopback sockets as the remote side: CONNECT relaying (SOCKS4, 4a, 5),
 * BIND and UDP ASSOCIATE. The client side is an in-memory stub endpoint,
 * so synchronisation is on a queue of replies with a hang-guard timeout.
 *
 * <p>Integration test: relays real loopback TCP and UDP through a live worker
 * loop; the relay opens its own kernel sockets.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksLiveLoopIntegrationTest {

    private static final long GUARD_MS = 5000;

    private static Gumdrop gumdrop;
    private static ClientEndpoint keeper;

    /** Client-side endpoint whose replies are queued for the test. */
    private static final class LiveEndpoint extends StubEndpoint {
        final BlockingQueue<byte[]> replies =
                new LinkedBlockingQueue<byte[]>();
        final List<Runnable> timers =
                Collections.synchronizedList(new ArrayList<Runnable>());
        final CountDownLatch closed = new CountDownLatch(1);
        private final SelectorLoop loop;

        LiveEndpoint(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            replies.add(copy);
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            return super.scheduleTimer(delayMs, callback);
        }

        @Override
        public void close() {
            super.close();
            closed.countDown();
        }

        byte[] next() throws InterruptedException {
            byte[] r = replies.poll(GUARD_MS, TimeUnit.MILLISECONDS);
            assertNotNull("no reply within the hang guard", r);
            return r;
        }
    }

    /** Echoes everything received on the first connection. */
    private static final class EchoServer extends Thread {
        final ServerSocket server;

        EchoServer() throws IOException {
            server = new ServerSocket(0, 1,
                    InetAddress.getLoopbackAddress());
            setDaemon(true);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void run() {
            try {
                Socket s = server.accept();
                try {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[512];
                    int n = in.read(buf);
                    while (n >= 0) {
                        out.write(buf, 0, n);
                        out.flush();
                        n = in.read(buf);
                    }
                } finally {
                    s.close();
                }
            } catch (IOException e) {
                // shutting down
            }
        }
    }

    private SocksServer server;
    private SocksListener listener;
    private SocksProtocolHandler handler;
    private LiveEndpoint endpoint;

    @BeforeClass
    public static void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1)
                .drainTimeoutMs(0));
        keeper = new ClientEndpoint(new TcpTransportFactory(),
                gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        gumdrop.ensureAcceptLoop();
    }

    @AfterClass
    public static void stopGumdrop() throws Exception {
        gumdrop.shutdown();
        gumdrop.join();
    }

    @Before
    public void setUp() {
        server = new SocksServer();
        listener = new SocksListener();
        listener.server(server);
        listener.start(gumdrop);
        handler = server.createProtocolHandler(listener);
        endpoint = new LiveEndpoint(gumdrop.nextWorkerLoop());
        handler.connected(endpoint);
    }

    @After
    public void tearDown() {
        handler.disconnected();
    }

    private void feed(final ByteBuffer data) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        endpoint.getSelectorLoop().invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    handler.receive(data);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(GUARD_MS, TimeUnit.MILLISECONDS));
    }

    private static ByteBuffer buf(byte[] b) {
        return ByteBuffer.wrap(b);
    }

    private void greet5() throws InterruptedException {
        feed(buf(new byte[] {5, 1, 0}));
        byte[] r = endpoint.next();
        assertEquals(SOCKS5_AUTH_NONE, r[1]);
    }

    private static byte[] request5(byte cmd, byte[] addr4, int port) {
        return new byte[] {5, cmd, 0, 1, addr4[0], addr4[1], addr4[2],
            addr4[3], (byte) (port >> 8), (byte) port};
    }

    private static byte[] loopback4() {
        return new byte[] {127, 0, 0, 1};
    }

    private static int portOf(byte[] reply) {
        int n = reply.length;
        return ((reply[n - 2] & 0xFF) << 8) | (reply[n - 1] & 0xFF);
    }

    @Test
    public void socks5ConnectRelaysBothWays() throws Exception {
        EchoServer echo = new EchoServer();
        echo.start();
        greet5();
        feed(buf(request5(SOCKS5_CMD_CONNECT, loopback4(),
                echo.port())));
        byte[] reply = endpoint.next();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, reply[1]);
        feed(buf("hello".getBytes("US-ASCII")));
        byte[] echoed = endpoint.next();
        assertEquals("hello", new String(echoed, "US-ASCII"));
        assertEquals(1, server.getActiveRelayCount());
        handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
        echo.server.close();
    }

    @Test
    public void socks4ConnectRelays() throws Exception {
        EchoServer echo = new EchoServer();
        echo.start();
        int p = echo.port();
        byte[] req = new byte[] {4, 1, (byte) (p >> 8), (byte) p, 127, 0, 0,
            1, 'u', 0};
        feed(buf(req));
        byte[] reply = endpoint.next();
        assertEquals(SOCKS4_REPLY_GRANTED, reply[1]);
        feed(buf(new byte[] {'x'}));
        byte[] echoed = endpoint.next();
        assertEquals('x', echoed[0]);
        echo.server.close();
    }

    @Test
    public void socks4aHostnameResolvesLocalhost() throws Exception {
        EchoServer echo = new EchoServer();
        echo.start();
        int p = echo.port();
        byte[] head = new byte[] {4, 1, (byte) (p >> 8), (byte) p, 0, 0, 0,
            1, 'u', 0};
        byte[] name = "localhost".getBytes("US-ASCII");
        byte[] req = new byte[head.length + name.length + 1];
        System.arraycopy(head, 0, req, 0, head.length);
        System.arraycopy(name, 0, req, head.length, name.length);
        feed(buf(req));
        byte[] reply = endpoint.next();
        assertEquals(SOCKS4_REPLY_GRANTED, reply[1]);
        echo.server.close();
    }

    @Test
    public void socks5DomainResolvesLocalhost() throws Exception {
        EchoServer echo = new EchoServer();
        echo.start();
        greet5();
        int p = echo.port();
        byte[] name = "localhost".getBytes("US-ASCII");
        byte[] req = new byte[5 + name.length + 2];
        req[0] = 5;
        req[1] = SOCKS5_CMD_CONNECT;
        req[3] = SOCKS5_ATYP_DOMAINNAME;
        req[4] = (byte) name.length;
        System.arraycopy(name, 0, req, 5, name.length);
        req[5 + name.length] = (byte) (p >> 8);
        req[6 + name.length] = (byte) p;
        feed(buf(req));
        byte[] reply = endpoint.next();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, reply[1]);
        echo.server.close();
    }

    @Test
    public void socks5DomainResolvedToBlockedAddressIsRejected()
            throws Exception {
        server.blockedDestinations(CidrNetwork.parseList("127.0.0.0/8"));
        greet5();
        byte[] name = "localhost".getBytes("US-ASCII");
        byte[] req = new byte[5 + name.length + 2];
        req[0] = 5;
        req[1] = SOCKS5_CMD_CONNECT;
        req[3] = SOCKS5_ATYP_DOMAINNAME;
        req[4] = (byte) name.length;
        System.arraycopy(name, 0, req, 5, name.length);
        feed(buf(req));
        byte[] reply = endpoint.next();
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, reply[1]);
        assertTrue(endpoint.closed.await(GUARD_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void bindAcceptsPeerAndRelays() throws Exception {
        greet5();
        feed(buf(request5(SOCKS5_CMD_BIND, new byte[4], 0)));
        byte[] first = endpoint.next();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, first[1]);
        int port = portOf(first);
        Socket peer = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            peer.setSoTimeout((int) GUARD_MS);
            byte[] second = endpoint.next();
            assertEquals(SOCKS5_REPLY_SUCCEEDED, second[1]);
            OutputStream out = peer.getOutputStream();
            out.write('p');
            out.flush();
            byte[] fromPeer = endpoint.next();
            assertEquals('p', fromPeer[0]);
            feed(buf(new byte[] {'c'}));
            int c = peer.getInputStream().read();
            assertEquals('c', c);
        } finally {
            peer.close();
        }
    }

    @Test
    public void bindFromUnexpectedPeerIsRejected() throws Exception {
        greet5();
        feed(buf(request5(SOCKS5_CMD_BIND,
                new byte[] {(byte) 192, 0, 2, 7}, 0)));
        byte[] first = endpoint.next();
        int port = portOf(first);
        Socket peer = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            byte[] second = endpoint.next();
            assertEquals(SOCKS5_REPLY_NOT_ALLOWED, second[1]);
            assertTrue(endpoint.closed.await(GUARD_MS, TimeUnit.MILLISECONDS));
        } finally {
            peer.close();
        }
    }

    @Test
    public void socks4BindAcceptsPeer() throws Exception {
        byte[] req = new byte[] {4, 2, 0, 0, 0, 0, 0, 0, 'u', 0};
        feed(buf(req));
        byte[] first = endpoint.next();
        assertEquals(SOCKS4_REPLY_GRANTED, first[1]);
        int port = ((first[2] & 0xFF) << 8) | (first[3] & 0xFF);
        Socket peer = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            byte[] second = endpoint.next();
            assertEquals(SOCKS4_REPLY_GRANTED, second[1]);
        } finally {
            peer.close();
        }
    }

    @Test
    public void bindTimeoutReportsTtlExpired() throws Exception {
        greet5();
        feed(buf(request5(SOCKS5_CMD_BIND, new byte[4], 0)));
        byte[] first = endpoint.next();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, first[1]);
        Runnable timer = endpoint.timers.get(0);
        timer.run();
        byte[] second = endpoint.next();
        assertEquals(SOCKS5_REPLY_TTL_EXPIRED, second[1]);
    }

    private static byte[] udpHeader(byte frag, int port, byte[] payload) {
        byte[] d = new byte[10 + payload.length];
        d[2] = frag;
        d[3] = SOCKS5_ATYP_IPV4;
        d[4] = 127;
        d[7] = 1;
        d[8] = (byte) (port >> 8);
        d[9] = (byte) port;
        System.arraycopy(payload, 0, d, 10, payload.length);
        return d;
    }

    private static byte[] udpDomain(String host, int port, byte[] payload) {
        byte[] name = host.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] d = new byte[7 + name.length + payload.length];
        d[3] = SOCKS5_ATYP_DOMAINNAME;
        d[4] = (byte) name.length;
        System.arraycopy(name, 0, d, 5, name.length);
        d[5 + name.length] = (byte) (port >> 8);
        d[6 + name.length] = (byte) port;
        System.arraycopy(payload, 0, d, 7 + name.length, payload.length);
        return d;
    }

    private static byte[] receive(DatagramSocket s, DatagramPacket[] holder)
            throws IOException {
        byte[] b = new byte[600];
        DatagramPacket p = new DatagramPacket(b, b.length);
        s.receive(p);
        holder[0] = p;
        byte[] out = new byte[p.getLength()];
        System.arraycopy(b, 0, out, 0, out.length);
        return out;
    }

    @Test
    public void udpAssociateForwardsAndFilters() throws Exception {
        endpoint.setRemoteAddress(new InetSocketAddress("127.0.0.1", 40000));
        server.blockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        greet5();
        feed(buf(request5(SOCKS5_CMD_UDP_ASSOCIATE, new byte[4],
                0)));
        byte[] assoc = endpoint.next();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, assoc[1]);
        int relayPort = portOf(assoc);
        assertEquals(1, server.getActiveRelayCount());

        DatagramSocket upstream = new DatagramSocket(0,
                InetAddress.getLoopbackAddress());
        DatagramSocket client = new DatagramSocket(0,
                InetAddress.getLoopbackAddress());
        try {
            upstream.setSoTimeout((int) GUARD_MS);
            client.setSoTimeout((int) GUARD_MS);
            InetSocketAddress relay = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), relayPort);
            byte[] fragmented = udpHeader((byte) 1, upstream.getLocalPort(),
                    new byte[] {'f'});
            byte[] blocked = new byte[] {0, 0, 0, 1, 10, 0, 0, 1, 0, 9, 'b'};
            byte[] empty = udpHeader((byte) 0, upstream.getLocalPort(),
                    new byte[0]);
            byte[] good = udpHeader((byte) 0, upstream.getLocalPort(),
                    new byte[] {'g', 'o'});
            client.send(new DatagramPacket(fragmented, fragmented.length,
                    relay));
            client.send(new DatagramPacket(blocked, blocked.length, relay));
            client.send(new DatagramPacket(empty, empty.length, relay));
            client.send(new DatagramPacket(good, good.length, relay));
            DatagramPacket[] holder = new DatagramPacket[1];
            byte[] got = receive(upstream, holder);
            assertEquals(2, got.length);
            assertEquals('g', got[0]);

            byte[] answer = new byte[] {'r'};
            upstream.send(new DatagramPacket(answer, 1,
                    holder[0].getSocketAddress()));
            byte[] back = receive(client, holder);
            assertEquals(11, back.length);
            assertEquals('r', back[10]);

            byte[] named = udpDomain("localhost", upstream.getLocalPort(),
                    new byte[] {'n'});
            client.send(new DatagramPacket(named, named.length, relay));
            got = receive(upstream, holder);
            assertEquals('n', got[0]);
        } finally {
            upstream.close();
            client.close();
        }
        handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void udpDatagramFromWrongSourceIsIgnored() throws Exception {
        greet5();
        feed(buf(request5(SOCKS5_CMD_UDP_ASSOCIATE, new byte[4],
                0)));
        byte[] assoc = endpoint.next();
        int relayPort = portOf(assoc);
        DatagramSocket client = new DatagramSocket(0,
                InetAddress.getLoopbackAddress());
        try {
            byte[] d = udpHeader((byte) 0, 9, new byte[] {'z'});
            client.send(new DatagramPacket(d, d.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(),
                            relayPort)));
        } finally {
            client.close();
        }
        assertEquals(1, server.getActiveRelayCount());
    }

    @Test
    public void udpIdleTimerClosesRelay() throws Exception {
        greet5();
        feed(buf(request5(SOCKS5_CMD_UDP_ASSOCIATE, new byte[4],
                0)));
        endpoint.next();
        Runnable timer = endpoint.timers.get(0);
        timer.run();
        assertEquals(0, server.getActiveRelayCount());
    }
}
