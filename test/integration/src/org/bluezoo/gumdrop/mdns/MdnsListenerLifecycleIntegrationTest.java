/*
 * MdnsListenerLifecycleIntegrationTest.java
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

package org.bluezoo.gumdrop.mdns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.mdns.server.MdnsServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Lifecycle of {@link MdnsListener} against a real Gumdrop worker loop on
 * an ephemeral port: bind, goodbye-on-loop at stop, send paths, timers and
 * datagram dispatch. Everything is synchronised with latches (the await
 * calls are only hang guards).
 *
 * <p>Integration test: joins the real multicast group on a booted runtime and
 * asserts the goodbye runs on a different (loop) thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MdnsListenerLifecycleIntegrationTest {

    private static final long GUARD_SECONDS = 20;

    /** Server that records where its callbacks run. */
    private static final class RecordingServer extends MdnsServer {
        final CountDownLatch goodbye = new CountDownLatch(1);
        final CountDownLatch datagram = new CountDownLatch(1);
        final AtomicReference<Thread> goodbyeThread = new AtomicReference<Thread>();
        final AtomicReference<byte[]> received = new AtomicReference<byte[]>();
        volatile int goodbyes;

        @Override
        public void sendGoodbye(MdnsListener origin) {
            goodbyes++;
            goodbyeThread.set(Thread.currentThread());
            goodbye.countDown();
        }

        @Override
        public void handleDatagram(MdnsListener origin, ByteBuffer data,
                InetSocketAddress source) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.set(copy);
            datagram.countDown();
        }
    }

    private Gumdrop gumdrop;
    private SelectorLoop loop;

    @Before
    public void setUp() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        loop = gumdrop.nextWorkerLoop();
    }

    @After
    public void tearDown() throws Exception {
        gumdrop.shutdown();
        gumdrop.join();
    }

    private MdnsListener started(RecordingServer server) {
        MdnsListener l = new MdnsListener();
        l.setPort(0);
        if (server != null) {
            l.setServer(server);
        }
        l.start(gumdrop);
        return l;
    }

    /** Waits until everything queued on the loop so far has run. */
    private void barrier() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        };
        loop.invokeLater(r);
        assertTrue(done.await(GUARD_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void startBindsAndStopSaysGoodbyeOnLoop() throws Exception {
        RecordingServer server = new RecordingServer();
        MdnsListener l = started(server);
        assertTrue(l.isBound());
        assertSame(server, l.getServer());
        InetSocketAddress group = l.getGroupAddress();
        assertEquals("224.0.0.251", group.getAddress().getHostAddress());

        l.stop();
        assertTrue(server.goodbye.await(GUARD_SECONDS, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), server.goodbyeThread.get());
        barrier();
        assertFalse(l.isBound());
        assertEquals(1, server.goodbyes);
    }

    @Test
    public void stopWithoutServerJustCloses() throws Exception {
        MdnsListener l = started(null);
        assertTrue(l.isBound());
        l.stop();
        barrier();
        assertFalse(l.isBound());
    }

    @Test
    public void stopTwiceAnnouncesGoodbyeOnlyOnce() throws Exception {
        RecordingServer server = new RecordingServer();
        MdnsListener l = started(server);
        l.stop();
        assertTrue(server.goodbye.await(GUARD_SECONDS, TimeUnit.SECONDS));
        barrier();
        assertFalse(l.isBound());
        l.stop();
        assertEquals(1, server.goodbyes);
    }

    @Test
    public void beginShutdownThenStopAnnouncesOnceOnTheLoop() throws Exception {
        RecordingServer server = new RecordingServer();
        MdnsListener l = started(server);
        l.beginShutdown();
        assertTrue(server.goodbye.await(GUARD_SECONDS, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), server.goodbyeThread.get());
        barrier();
        l.stop();
        barrier();
        assertEquals(1, server.goodbyes);
    }

    @Test
    public void stopNeverStartedWithAndWithoutServer() {
        MdnsListener bare = new MdnsListener();
        bare.stop();
        assertFalse(bare.isBound());
        RecordingServer server = new RecordingServer();
        MdnsListener wired = new MdnsListener();
        wired.setServer(server);
        wired.stop();
        assertEquals(1, server.goodbyes);
        assertSame(Thread.currentThread(), server.goodbyeThread.get());
    }

    @Test
    public void datagramIsDispatchedToServer() throws Exception {
        RecordingServer server = new RecordingServer();
        MdnsListener l = started(server);
        int port = ((InetSocketAddress) boundAddress(l)).getPort();
        DatagramSocket sender = new DatagramSocket();
        try {
            byte[] payload = new byte[] {1, 2, 3, 4};
            DatagramPacket p = new DatagramPacket(payload, payload.length,
                    InetAddress.getLoopbackAddress(), port);
            sender.send(p);
            assertTrue(server.datagram.await(GUARD_SECONDS, TimeUnit.SECONDS));
            byte[] got = server.received.get();
            assertNotNull(got);
            assertEquals(4, got.length);
            assertEquals(3, got[2]);
        } finally {
            sender.close();
            l.stop();
            barrier();
        }
    }

    @Test
    public void sendToDeliversUnicastFromLoop() throws Exception {
        RecordingServer server = new RecordingServer();
        final MdnsListener l = started(server);
        final DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        try {
            peer.setSoTimeout((int) (GUARD_SECONDS * 1000));
            final InetSocketAddress dest = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), peer.getLocalPort());
            Runnable send = new Runnable() {
                @Override
                public void run() {
                    byte[] b = new byte[] {9, 8, 7};
                    l.sendTo(ByteBuffer.wrap(b), dest);
                }
            };
            loop.invokeLater(send);
            byte[] buf = new byte[16];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            peer.receive(in);
            assertEquals(3, in.getLength());
            assertEquals(8, buf[1]);
        } finally {
            peer.close();
            l.stop();
            barrier();
        }
    }

    @Test
    public void sendToGroupAndTimerRunOnLoop() throws Exception {
        RecordingServer server = new RecordingServer();
        final MdnsListener l = started(server);
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Runnable work = new Runnable() {
            @Override
            public void run() {
                try {
                    Runnable cb = new Runnable() {
                        @Override
                        public void run() {
                        }
                    };
                    MdnsListener.TimerHandleWrapper h = l.scheduleTimer(60000, cb);
                    h.cancel();
                    byte[] b = new byte[] {0};
                    l.sendToGroup(ByteBuffer.wrap(b));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    ran.countDown();
                }
            }
        };
        loop.invokeLater(work);
        assertTrue(ran.await(GUARD_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());
        l.stop();
        barrier();
    }

    private static Object boundAddress(MdnsListener l) throws Exception {
        java.lang.reflect.Field f = MdnsListener.class.getDeclaredField("endpoint");
        f.setAccessible(true);
        Object ep = f.get(l);
        return ((org.bluezoo.gumdrop.UdpEndpoint) ep).getLocalAddress();
    }
}
