/*
 * SelectorLoopTcpLoopbackIntegrationTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs {@link TcpEndpoint}s on real {@link SelectorLoop} threads over
 * loopback sockets: the read, write, connect, EOF, reset and flow-control
 * dispatch paths, plus complete TLS sessions over real channels. Every
 * wait is a latch (with a generous hang guard), every send runs on the
 * endpoint's own loop, and each test uses its own ephemeral listening
 * socket that is never reused.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoopTcpLoopbackIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    private final List<SelectorLoop> loops = new ArrayList<SelectorLoop>();
    private final List<java.io.Closeable> closeables = new ArrayList<java.io.Closeable>();
    private static final AtomicInteger INDEX = new AtomicInteger(200);

    /** Handler with latches; behaviour switches set before connecting. */
    private static final class Handler implements ProtocolHandler {
        volatile Endpoint endpoint;
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch disconnectedLatch = new CountDownLatch(1);
        final CountDownLatch secureLatch = new CountDownLatch(1);
        final CountDownLatch errorLatch = new CountDownLatch(1);
        final StringBuffer received = new StringBuffer();
        final List<Exception> errors = new ArrayList<Exception>();
        volatile boolean echo;
        volatile boolean throwOnReceive;
        volatile boolean pauseOnReceive;
        volatile int bulkBytes;
        volatile boolean closeAfterBulk;
        volatile CountDownLatch dataLatch = new CountDownLatch(1);
        volatile int expectChars = 1;
        volatile String sendWhenSecure;
        volatile SecurityInfo security;

        @Override
        public void receive(ByteBuffer data) {
            if (throwOnReceive) {
                throw new IllegalStateException("handler failure");
            }
            byte[] b = new byte[data.remaining()];
            data.get(b);
            String s = new String(b, StandardCharsets.UTF_8);
            received.append(s);
            if (pauseOnReceive) {
                endpoint.pauseRead();
            }
            if (echo) {
                endpoint.send(ByteBuffer.wrap(b));
            }
            int n = bulkBytes;
            if (n > 0) {
                bulkBytes = 0;
                endpoint.send(ByteBuffer.wrap(new byte[n]));
                if (closeAfterBulk) {
                    endpoint.close();
                }
            }
            if (received.length() >= expectChars) {
                dataLatch.countDown();
            }
        }

        @Override
        public void connected(Endpoint ep) {
            endpoint = ep;
            connectedLatch.countDown();
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            security = info;
            secureLatch.countDown();
            String s = sendWhenSecure;
            if (s != null) {
                endpoint.send(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public void disconnected() {
            disconnectedLatch.countDown();
        }

        @Override
        public void error(Exception cause) {
            synchronized (errors) {
                errors.add(cause);
            }
            errorLatch.countDown();
        }
    }

    @Before
    public void setUp() {
        loops.clear();
        closeables.clear();
    }

    @After
    public void tearDown() throws Exception {
        for (SelectorLoop loop : loops) {
            loop.shutdownNow();
            loop.awaitQuiesce(HANG_GUARD_MS);
        }
        for (java.io.Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException e) {
                // best effort
            }
        }
    }

    private SelectorLoop newLoop() {
        SelectorLoop loop = new SelectorLoop(INDEX.incrementAndGet());
        loops.add(loop);
        loop.start();
        return loop;
    }

    private ServerSocketChannel listen() throws IOException {
        ServerSocketChannel ss = ServerSocketChannel.open();
        closeables.add(ss);
        InetAddress lo = InetAddress.getLoopbackAddress();
        ss.bind(new InetSocketAddress(lo, 0));
        return ss;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("hang guard", latch.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    /** A connected raw peer plus the server endpoint registered on a loop. */
    private static final class Pair {
        SocketChannel raw;
        TcpEndpoint endpoint;
        Handler handler;
        SelectorLoop loop;
    }

    private Pair accepted(Handler h, HandshakeConfig c13, Tls12HandshakeConfig c12,
            TlsVersion policy, boolean secure) throws Exception {
        SelectorLoop loop = newLoop();
        ServerSocketChannel ss = listen();
        SocketChannel raw = SocketChannel.open(ss.getLocalAddress());
        closeables.add(raw);
        SocketChannel accepted = ss.accept();
        accepted.configureBlocking(false);
        accepted.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
        TcpEndpoint ep = new TcpEndpoint(h, c13, c12, policy, secure);
        ep.setChannel(accepted);
        ep.init();
        loop.registerTCP(accepted, ep);
        final TcpEndpoint fep = ep;
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                fep.connected();
            }
        });
        await(h.connectedLatch);
        Pair p = new Pair();
        p.raw = raw;
        p.endpoint = ep;
        p.handler = h;
        p.loop = loop;
        return p;
    }

    private Pair accepted(Handler h) throws Exception {
        return accepted(h, null, null, TlsVersion.TLS_1_3, false);
    }

    private static void writeRaw(SocketChannel raw, String s) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            raw.write(b);
        }
    }

    private static int readFully(SocketChannel raw, int wanted) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(65536);
        int total = 0;
        while (total < wanted) {
            b.clear();
            int n = raw.read(b);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static int readToEof(SocketChannel raw) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(65536);
        int total = 0;
        for (;;) {
            b.clear();
            int n = raw.read(b);
            if (n < 0) {
                return total;
            }
            total += n;
        }
    }

    @Test
    public void echoRoundTripThenPeerEof() throws Exception {
        Handler h = new Handler();
        h.echo = true;
        h.expectChars = 5;
        Pair p = accepted(h);
        writeRaw(p.raw, "hello");
        assertEquals(5, readFully(p.raw, 5));
        await(h.dataLatch);
        assertEquals("hello", h.received.toString());
        assertTrue(p.endpoint.isOpen());
        p.raw.shutdownOutput();
        await(h.disconnectedLatch);
    }

    @Test
    public void bulkReplyIsFlushedThroughPartialWritesThenClosed() throws Exception {
        Handler h = new Handler();
        h.bulkBytes = 6 * 1024 * 1024;
        h.closeAfterBulk = true;
        Pair p = accepted(h);
        writeRaw(p.raw, "go");
        int total = readToEof(p.raw);
        assertEquals(6 * 1024 * 1024, total);
        await(h.disconnectedLatch);
    }

    @Test
    public void writeReadyCallbackRunsOnceOutputDrains() throws Exception {
        final Handler h = new Handler();
        Pair p = accepted(h);
        final CountDownLatch drained = new CountDownLatch(1);
        final TcpEndpoint ep = p.endpoint;
        p.loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                ep.onWriteReady(new Runnable() {
                    @Override
                    public void run() {
                        drained.countDown();
                    }
                });
                ep.send(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)));
            }
        });
        assertEquals(3, readFully(p.raw, 3));
        await(drained);
    }

    @Test
    public void closeWhenOutboundIdleClosesAfterFlush() throws Exception {
        final Handler h = new Handler();
        Pair p = accepted(h);
        final TcpEndpoint ep = p.endpoint;
        p.loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                ep.send(ByteBuffer.wrap("bye".getBytes(StandardCharsets.UTF_8)));
                ep.closeWhenOutboundIdle();
            }
        });
        assertEquals(3, readToEof(p.raw));
        await(h.disconnectedLatch);
        assertFalse(ep.isOpen());
    }

    @Test
    public void peerResetReportsDisconnect() throws Exception {
        Handler h = new Handler();
        Pair p = accepted(h);
        p.raw.setOption(StandardSocketOptions.SO_LINGER, Integer.valueOf(0));
        p.raw.close();
        await(h.disconnectedLatch);
    }

    @Test
    public void handlerFailureIsIsolatedAndConnectionClosed() throws Exception {
        Handler h = new Handler();
        h.throwOnReceive = true;
        Pair p = accepted(h);
        writeRaw(p.raw, "boom");
        await(h.errorLatch);
        await(h.disconnectedLatch);
        synchronized (h.errors) {
            assertTrue(h.errors.get(0) instanceof IllegalStateException);
        }
        assertEquals(-1, p.raw.read(ByteBuffer.allocate(8)));
    }

    @Test
    public void pausedReadResumesWhenAsked() throws Exception {
        final Handler h = new Handler();
        h.pauseOnReceive = true;
        h.expectChars = 1;
        Pair p = accepted(h);
        writeRaw(p.raw, "a");
        await(h.dataLatch);
        assertTrue(p.endpoint.isReadPaused());
        h.pauseOnReceive = false;
        h.dataLatch = new CountDownLatch(1);
        h.expectChars = 2;
        writeRaw(p.raw, "b");
        final TcpEndpoint ep = p.endpoint;
        p.loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                ep.resumeRead();
            }
        });
        await(h.dataLatch);
        assertEquals("ab", h.received.toString());
    }

    @Test
    public void clientModeConnectDeliversAndSends() throws Exception {
        SelectorLoop loop = newLoop();
        ServerSocketChannel ss = listen();
        Handler h = new Handler();
        h.expectChars = 4;
        SocketChannel ch = SocketChannel.open();
        closeables.add(ch);
        ch.configureBlocking(false);
        ch.connect(ss.getLocalAddress());
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setClientMode(true);
        ep.setChannel(ch);
        ep.init();
        loop.registerForConnect(ch, ep);
        await(h.connectedLatch);
        SocketChannel peer = ss.accept();
        closeables.add(peer);
        writeRaw(peer, "pong");
        await(h.dataLatch);
        assertEquals("pong", h.received.toString());
        final TcpEndpoint fep = ep;
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                fep.send(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
                fep.close();
            }
        });
        assertEquals(4, readToEof(peer));
        await(h.disconnectedLatch);
    }

    private void tlsSession(HandshakeConfig sc13, Tls12HandshakeConfig sc12,
            HandshakeConfig cc13, Tls12HandshakeConfig cc12, TlsVersion serverPolicy,
            TlsVersion clientPolicy) throws Exception {
        SelectorLoop serverLoop = newLoop();
        SelectorLoop clientLoop = newLoop();
        ServerSocketChannel ss = listen();
        Handler client = new Handler();
        client.sendWhenSecure = "hi-from-client";
        Handler server = new Handler();
        server.echo = true;
        client.expectChars = 14;

        SocketChannel ch = SocketChannel.open();
        closeables.add(ch);
        ch.configureBlocking(false);
        ch.connect(ss.getLocalAddress());
        TcpEndpoint cep = new TcpEndpoint(client, cc13, cc12, clientPolicy, true);
        cep.setClientMode(true);
        cep.setChannel(ch);
        cep.init();

        SocketChannel accepted = ss.accept();
        accepted.configureBlocking(false);
        closeables.add(accepted);
        TcpEndpoint sep = new TcpEndpoint(server, sc13, sc12, serverPolicy, true);
        sep.setChannel(accepted);
        sep.init();
        serverLoop.registerTCP(accepted, sep);
        final TcpEndpoint fsep = sep;
        serverLoop.invokeLater(new Runnable() {
            @Override
            public void run() {
                fsep.connected();
            }
        });
        clientLoop.registerForConnect(ch, cep);

        await(client.secureLatch);
        await(server.secureLatch);
        assertNotNull(client.security);
        assertNotNull(server.security);
        await(client.dataLatch);
        assertEquals("hi-from-client", client.received.toString());
        assertEquals("hi-from-client", server.received.toString());
        final TcpEndpoint fcep = cep;
        clientLoop.invokeLater(new Runnable() {
            @Override
            public void run() {
                fcep.close();
            }
        });
        await(server.disconnectedLatch);
        await(client.disconnectedLatch);
    }

    @Test
    public void tls13SessionOverRealChannels() throws Exception {
        HandshakeConfig s = new HandshakeConfig(HandshakeRole.SERVER);
        s.setServerCredentials(TestCertificates.ec256().credentials());
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        tlsSession(s, null, c, null, TlsVersion.TLS_1_3, TlsVersion.TLS_1_3);
    }

    @Test
    public void tls12SessionOverRealChannels() throws Exception {
        Tls12HandshakeConfig s = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        s.setServerCredentials(TestCertificates.ec256().credentials());
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        tlsSession(null, s, null, c, TlsVersion.TLS_1_2, TlsVersion.TLS_1_2);
    }

    @Test
    public void negotiatedSessionOverRealChannels() throws Exception {
        HandshakeConfig s13 = new HandshakeConfig(HandshakeRole.SERVER);
        s13.setServerCredentials(TestCertificates.ec256().credentials());
        Tls12HandshakeConfig s12 = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        s12.setServerCredentials(TestCertificates.ec256().credentials());
        HandshakeConfig c13 = new HandshakeConfig(HandshakeRole.CLIENT);
        c13.setServerName(TestCertificates.SERVER_NAME);
        c13.setTrustManager(TestCertificates.ec256().trustManager());
        Tls12HandshakeConfig c12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c12.setServerName(TestCertificates.SERVER_NAME);
        c12.setTrustManager(TestCertificates.ec256().trustManager());
        tlsSession(s13, s12, c13, c12, TlsVersion.NEGOTIATE, TlsVersion.NEGOTIATE);
    }
}
