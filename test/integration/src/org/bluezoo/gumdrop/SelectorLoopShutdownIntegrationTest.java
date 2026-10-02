/*
 * SelectorLoopShutdownIntegrationTest.java
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * The shutdown contract of {@link SelectorLoop}: a loop closes everything it
 * owns on its own thread (orderly with goodbyes, or abort without), exits
 * only when nothing remains open or a hard deadline passes, runs its queued
 * work once more before exiting, and fails deterministically for work
 * offered after it has terminated. Everything is synchronised with latches;
 * the hard deadline is driven by an injected clock.
 *
 * <p>Integration test: needs live selector-loop threads, real datagram channels
 * and loopback TCP pairs: it asserts which thread closes what and the
 * shutdown state machine across threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoopShutdownIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    private final List<SelectorLoop> loops = new ArrayList<SelectorLoop>();
    private final List<DatagramChannel> channels = new ArrayList<DatagramChannel>();

    @After
    public void tearDown() throws Exception {
        for (SelectorLoop loop : loops) {
            loop.shutdownNow();
            loop.awaitQuiesce(HANG_GUARD_MS);
        }
        for (DatagramChannel channel : channels) {
            channel.close();
        }
    }

    /** A controllable clock for the hard deadline. */
    private static final class ClockedLoop extends SelectorLoop {
        final AtomicLong now = new AtomicLong(1000L);

        ClockedLoop() {
            super(77);
        }

        @Override
        long clockMillis() {
            return now.get();
        }
    }

    /**
     * Owned handler that records how and on which thread it was asked to
     * close. When {@code stubborn} it ignores an orderly close (a peer that
     * never drains); when {@code stuck} it ignores every close.
     */
    private static final class FakeHandler implements ChannelHandler {
        final boolean stubborn;
        final boolean stuck;
        final List<Boolean> modes = Collections.synchronizedList(new ArrayList<Boolean>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
        final CountDownLatch orderlyAsked = new CountDownLatch(1);
        final CountDownLatch anyAsked = new CountDownLatch(1);
        volatile SelectionKey key;
        volatile SelectorLoop loop;

        FakeHandler(boolean stubborn, boolean stuck) {
            this.stubborn = stubborn;
            this.stuck = stuck;
        }

        @Override
        public Type getChannelType() {
            return Type.DATAGRAM_SERVER;
        }

        @Override
        public SelectionKey getSelectionKey() {
            return key;
        }

        @Override
        public void setSelectionKey(SelectionKey key) {
            this.key = key;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public void setSelectorLoop(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public void closeForShutdown(boolean orderly) {
            modes.add(Boolean.valueOf(orderly));
            threads.add(Thread.currentThread());
            if (orderly) {
                orderlyAsked.countDown();
            }
            anyAsked.countDown();
            if (stuck) {
                return;
            }
            if (orderly && stubborn) {
                return;
            }
            SelectionKey k = key;
            try {
                k.channel().close();
            } catch (IOException e) {
                // closing a datagram channel does not fail here
            }
            k.cancel();
        }
    }

    private SelectorLoop startedLoop() {
        SelectorLoop loop = new SelectorLoop(70 + loops.size());
        loops.add(loop);
        loop.start();
        return loop;
    }

    private DatagramChannel openChannel() throws IOException {
        DatagramChannel dc = DatagramChannel.open();
        dc.configureBlocking(false);
        channels.add(dc);
        return dc;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("hang guard", latch.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    /** Returns once everything queued before this call has run on the loop. */
    private static void barrier(SelectorLoop loop) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        boolean accepted = loop.tryInvokeLater(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        });
        assertTrue(accepted);
        await(done);
    }

    private FakeHandler register(SelectorLoop loop, boolean stubborn, boolean stuck) throws Exception {
        FakeHandler handler = new FakeHandler(stubborn, stuck);
        DatagramChannel dc = openChannel();
        loop.registerDatagram(dc, handler);
        barrier(loop);
        assertNotNull("registered", handler.getSelectionKey());
        return handler;
    }

    private static void awaitTerminated(SelectorLoop loop) {
        assertTrue("loop must terminate", loop.awaitQuiesce(HANG_GUARD_MS));
    }

    @Test
    public void orderlyShutdownClosesOwnedHandlersOnTheLoopThread() throws Exception {
        SelectorLoop loop = startedLoop();
        FakeHandler a = register(loop, false, false);
        FakeHandler b = register(loop, false, false);

        loop.shutdown();
        awaitTerminated(loop);

        Thread loopThread = loop.getThread();
        assertEquals(1, a.modes.size());
        assertEquals(1, b.modes.size());
        assertEquals(Boolean.TRUE, a.modes.get(0));
        assertEquals(Boolean.TRUE, b.modes.get(0));
        assertSame(loopThread, a.threads.get(0));
        assertSame(loopThread, b.threads.get(0));
        assertNotSame(Thread.currentThread(), a.threads.get(0));
    }

    @Test
    public void abortClosesWithoutGoodbyes() throws Exception {
        SelectorLoop loop = startedLoop();
        FakeHandler a = register(loop, false, false);

        loop.shutdownNow();
        awaitTerminated(loop);

        assertEquals(1, a.modes.size());
        assertEquals(Boolean.FALSE, a.modes.get(0));
        assertSame(loop.getThread(), a.threads.get(0));
    }

    @Test
    public void loopDoesNotExitWhileAnOrderlyCloseIsStillPending() throws Exception {
        SelectorLoop loop = startedLoop();
        FakeHandler stubborn = register(loop, true, false);

        loop.shutdown();
        await(stubborn.orderlyAsked);

        assertTrue("a handler that has not closed keeps the loop alive", loop.isRunning());
        assertTrue(stubborn.getSelectionKey().isValid());
    }

    @Test
    public void abortEscalatesAnOrderlyShutdownInProgress() throws Exception {
        SelectorLoop loop = startedLoop();
        FakeHandler stubborn = register(loop, true, false);

        loop.shutdown();
        await(stubborn.orderlyAsked);
        loop.shutdownNow();
        awaitTerminated(loop);

        assertEquals(2, stubborn.modes.size());
        assertEquals(Boolean.TRUE, stubborn.modes.get(0));
        assertEquals(Boolean.FALSE, stubborn.modes.get(1));
        assertFalse(stubborn.getSelectionKey().isValid());
    }

    @Test
    public void hardDeadlineForcesAHandlerThatRefusesToClose() throws Exception {
        ClockedLoop loop = new ClockedLoop();
        loops.add(loop);
        loop.setCloseDeadlineMs(5000L);
        loop.start();
        FakeHandler stuck = register(loop, true, true);
        final SelectionKey key = stuck.getSelectionKey();
        final DatagramChannel dc = (DatagramChannel) key.channel();

        loop.shutdown();
        await(stuck.orderlyAsked);
        assertTrue("before the deadline the loop keeps waiting", loop.isRunning());
        assertTrue(dc.isOpen());

        loop.now.addAndGet(5000L);
        loop.wakeup();
        awaitTerminated(loop);

        assertFalse("the loop closes what refused to close", dc.isOpen());
        assertFalse(key.isValid());
        assertEquals(Boolean.FALSE, stuck.modes.get(stuck.modes.size() - 1));
    }

    @Test
    public void queuedTaskRunsBeforeTheLoopExits() throws Exception {
        final SelectorLoop loop = startedLoop();
        final CountDownLatch inTask = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                inTask.countDown();
                try {
                    release.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        await(inTask);
        boolean accepted = loop.tryInvokeLater(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });
        assertTrue(accepted);
        loop.shutdown();
        release.countDown();
        awaitTerminated(loop);

        assertEquals("a task queued before exit must run exactly once", 1, ran.get());
    }

    @Test
    public void everyOfferedTaskEitherRunsOrIsRejected() throws Exception {
        final SelectorLoop loop = startedLoop();
        final AtomicInteger ran = new AtomicInteger();
        int accepted = 0;
        loop.shutdown();
        for (int i = 0; i < 2000; i++) {
            boolean ok = loop.tryInvokeLater(new Runnable() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }
            });
            if (ok) {
                accepted++;
            }
        }
        awaitTerminated(loop);
        assertEquals("accepted tasks all run, rejected ones never do", accepted, ran.get());
    }

    @Test
    public void invokeLaterAfterTerminationIsRejectedAndNeverRuns() throws Exception {
        SelectorLoop loop = startedLoop();
        loop.shutdown();
        awaitTerminated(loop);

        final AtomicInteger ran = new AtomicInteger();
        boolean accepted = loop.tryInvokeLater(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertFalse(accepted);
        assertEquals(0, ran.get());
    }

    @Test
    public void registrationAfterTerminationClosesTheHandler() throws Exception {
        SelectorLoop loop = startedLoop();
        loop.shutdown();
        awaitTerminated(loop);

        FakeHandler late = new FakeHandler(false, false);
        DatagramChannel dc = openChannel();
        loop.registerDatagram(dc, late);

        assertFalse("channel offered to a terminated loop must be closed", dc.isOpen());
        assertEquals(1, late.modes.size());
        assertEquals(Boolean.FALSE, late.modes.get(0));
    }

    @Test
    public void registrationQueuedDuringClosingIsClosedToo() throws Exception {
        SelectorLoop loop = startedLoop();
        FakeHandler stubborn = register(loop, true, false);
        loop.shutdown();
        await(stubborn.orderlyAsked);

        FakeHandler late = new FakeHandler(false, false);
        DatagramChannel dc = openChannel();
        loop.registerDatagram(dc, late);
        await(late.anyAsked);
        assertFalse("a connection arriving while closing is closed, not served", dc.isOpen());
        loop.shutdownNow();
        awaitTerminated(loop);
    }

    @Test
    public void shutdownIsIdempotent() throws Exception {
        SelectorLoop loop = startedLoop();
        // Stubborn: it ignores the orderly close, so the loop must still
        // hold it when the abort arrives. A handler that obeyed the orderly
        // close could be gone before the abort is processed, which would
        // make "the abort is the last word" depend on thread timing.
        FakeHandler a = register(loop, true, false);

        loop.shutdown();
        loop.shutdown();
        loop.shutdownNow();
        loop.shutdownNow();
        awaitTerminated(loop);
        loop.shutdown();

        assertTrue("closed at most once per mode", a.modes.size() >= 1 && a.modes.size() <= 2);
        assertEquals("the abort that was requested is the last word",
                Boolean.FALSE, a.modes.get(a.modes.size() - 1));
    }

    /** Server-side TCP endpoint on a live loop whose peer is a plain socket. */
    private static final class TcpPair {
        final TcpEndpoint endpoint;
        final SocketChannel peer;
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicReference<Thread> disconnectThread = new AtomicReference<Thread>();

        TcpPair(SelectorLoop loop) throws Exception {
            ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            peer = SocketChannel.open(server.getLocalAddress());
            SocketChannel accepted = server.accept();
            server.close();
            accepted.configureBlocking(false);
            final AtomicReference<Thread> thread = disconnectThread;
            final CountDownLatch gone = disconnected;
            endpoint = new TcpEndpoint(new ProtocolHandler() {
                @Override
                public void receive(ByteBuffer data) {
                    data.position(data.limit());
                }

                @Override
                public void connected(Endpoint ep) {
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                }

                @Override
                public void disconnected() {
                    thread.set(Thread.currentThread());
                    gone.countDown();
                }

                @Override
                public void error(Exception cause) {
                }
            });
            endpoint.setChannel(accepted);
            endpoint.init();
            loop.register(accepted, endpoint);
        }
    }

    private static String readAll(SocketChannel peer) throws IOException {
        peer.socket().setSoTimeout((int) HANG_GUARD_MS);
        InputStream in = peer.socket().getInputStream();
        StringBuilder sb = new StringBuilder();
        int b = in.read();
        while (b != -1) {
            sb.append((char) b);
            b = in.read();
        }
        return sb.toString();
    }

    private void queueAndShutdown(final SelectorLoop loop, final TcpPair pair, final boolean abort)
            throws Exception {
        barrier(loop);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                pair.endpoint.send(ByteBuffer.wrap(new byte[] {'b', 'y', 'e'}));
                if (abort) {
                    loop.shutdownNow();
                } else {
                    loop.shutdown();
                }
            }
        });
    }

    @Test
    public void orderlyTcpCloseFlushesQueuedOutputThenCloses() throws Exception {
        SelectorLoop loop = startedLoop();
        TcpPair pair = new TcpPair(loop);
        try {
            queueAndShutdown(loop, pair, false);
            String received = readAll(pair.peer);
            awaitTerminated(loop);
            assertEquals("goodbye bytes are delivered before the close", "bye", received);
            await(pair.disconnected);
            assertSame(loop.getThread(), pair.disconnectThread.get());
        } finally {
            pair.peer.close();
        }
    }

    @Test
    public void abortTcpCloseDropsQueuedOutput() throws Exception {
        SelectorLoop loop = startedLoop();
        TcpPair pair = new TcpPair(loop);
        try {
            queueAndShutdown(loop, pair, true);
            String received = readAll(pair.peer);
            awaitTerminated(loop);
            assertEquals("abort sends no goodbyes", "", received);
            await(pair.disconnected);
            assertSame(loop.getThread(), pair.disconnectThread.get());
        } finally {
            pair.peer.close();
        }
    }
}
