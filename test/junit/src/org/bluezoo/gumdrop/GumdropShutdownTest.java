/*
 * GumdropShutdownTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * The orderly and abort shutdown of a whole {@link Gumdrop} runtime: the
 * accept socket is released before any connection is closed, every
 * connection is closed by the loop that owns it (never by the shutdown
 * caller), concurrent and repeated shutdowns coordinate, and
 * {@link Gumdrop#shutdownNow()} escalates a shutdown that is draining.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropShutdownTest {

    private static final long HANG_GUARD_MS = 60000L;

    private Gumdrop gumdrop;
    private final AtomicReference<Throwable> background = new AtomicReference<Throwable>();

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdownNow();
        }
        assertNull(background.get());
    }

    /** Records what happens to each accepted connection. */
    private static final class Recorder implements ProtocolHandler {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicReference<Thread> disconnectThread = new AtomicReference<Thread>();
        final AtomicBoolean listenerRefusedAtClose = new AtomicBoolean();
        final int probePort;

        Recorder(int probePort) {
            this.probePort = probePort;
        }

        @Override
        public void connected(Endpoint endpoint) {
            connected.countDown();
        }

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
            disconnects.incrementAndGet();
            disconnectThread.set(Thread.currentThread());
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), probePort),
                        (int) HANG_GUARD_MS);
                listenerRefusedAtClose.set(false);
            } catch (IOException e) {
                listenerRefusedAtClose.set(true);
            } finally {
                try {
                    probe.close();
                } catch (IOException e) {
                    // nothing to do
                }
            }
            disconnected.countDown();
        }

        @Override
        public void error(Exception cause) {
            disconnected();
        }
    }

    private static final class TestListener extends TcpListener {
        volatile int boundPort;
        volatile Recorder lastRecorder;
        final CountDownLatch bound = new CountDownLatch(1);
        final CountDownLatch created = new CountDownLatch(1);

        @Override
        protected ProtocolHandler createHandler() {
            Recorder r = new Recorder(boundPort);
            lastRecorder = r;
            created.countDown();
            return r;
        }

        @Override
        protected void applyBoundTcpPort(int port) {
            boundPort = port;
            bound.countDown();
        }

        @Override
        public String getDescription() {
            return "shutdown-test";
        }

        @Override
        public int getPort() {
            return 0;
        }
    }

    private TestListener listener;

    private Socket bootWithOneConnection(long drainMs) throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(drainMs));
        listener = new TestListener();
        listener.addresses(InetAddress.getLoopbackAddress());
        gumdrop.addListener(listener);
        assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
        assertTrue(listener.bound.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.boundPort);
        client.setSoTimeout((int) HANG_GUARD_MS);
        Recorder r = waitForRecorder();
        assertTrue(r.connected.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        return client;
    }

    private Recorder waitForRecorder() throws Exception {
        assertTrue(listener.created.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        Recorder r = listener.lastRecorder;
        assertNotNull(r);
        return r;
    }

    private Thread background(final Runnable action, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } catch (Throwable e) {
                    background.set(e);
                }
            }
        }, name);
        t.start();
        return t;
    }

    @Test
    public void acceptStopsBeforeConnectionsCloseAndTheLoopClosesThem() throws Exception {
        Socket client = bootWithOneConnection(0L);
        Recorder r = listener.lastRecorder;
        try {
            gumdrop.shutdown();

            assertTrue(r.disconnected.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertEquals(1, r.disconnects.get());
            assertTrue("the listening socket is released before any connection is closed",
                    r.listenerRefusedAtClose.get());
            Thread closer = r.disconnectThread.get();
            assertNotSame("a connection is never closed by the shutdown caller", Thread.currentThread(), closer);
            assertTrue(closer.getName(), closer.getName().startsWith("SelectorLoop-"));
            InputStream in = client.getInputStream();
            assertEquals("the peer sees the close", -1, in.read());
            assertFalse(gumdrop.isStarted());
        } finally {
            client.close();
        }
    }

    @Test
    public void listeningPortIsReleasedWhileConnectionsStillDrain() throws Exception {
        Socket client = bootWithOneConnection(HANG_GUARD_MS);
        final CountDownLatch draining = new CountDownLatch(1);
        final AtomicBoolean rebound = new AtomicBoolean();
        final AtomicBoolean ready = new AtomicBoolean(true);
        final AtomicBoolean drainingFlag = new AtomicBoolean();
        final int port = listener.boundPort;
        gumdrop.drainWaitObserver = new Runnable() {
            @Override
            public void run() {
                ready.set(gumdrop.isReady());
                drainingFlag.set(gumdrop.isDraining());
                try {
                    ServerSocket again = new ServerSocket();
                    again.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    again.close();
                    rebound.set(true);
                } catch (IOException e) {
                    rebound.set(false);
                }
                draining.countDown();
            }
        };
        Thread coordinator = background(new Runnable() {
            @Override
            public void run() {
                gumdrop.shutdown();
            }
        }, "orderly-shutdown");
        try {
            assertTrue(draining.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertFalse("not ready as soon as shutdown begins", ready.get());
            assertTrue(drainingFlag.get());
            assertTrue("the accept socket is really released when draining starts", rebound.get());
            assertEquals(0, listener.lastRecorder.disconnects.get());
        } finally {
            gumdrop.shutdownNow();
            coordinator.join(HANG_GUARD_MS);
            client.close();
        }
    }

    @Test
    public void shutdownNowEscalatesAShutdownThatIsDraining() throws Exception {
        Socket client = bootWithOneConnection(HANG_GUARD_MS);
        Recorder r = listener.lastRecorder;
        final CountDownLatch draining = new CountDownLatch(1);
        gumdrop.drainWaitObserver = new Runnable() {
            @Override
            public void run() {
                draining.countDown();
            }
        };
        Thread coordinator = background(new Runnable() {
            @Override
            public void run() {
                gumdrop.shutdown();
            }
        }, "orderly-shutdown");
        try {
            assertTrue(draining.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertEquals("still draining: connection untouched", 0, r.disconnects.get());

            gumdrop.shutdownNow();

            assertFalse("shutdownNow returns only once shut down", gumdrop.isStarted());
            coordinator.join(HANG_GUARD_MS);
            assertFalse(coordinator.isAlive());
            assertEquals(1, r.disconnects.get());
        } finally {
            client.close();
        }
    }

    @Test
    public void shutdownNowWithoutPriorShutdownAborts() throws Exception {
        Socket client = bootWithOneConnection(HANG_GUARD_MS);
        Recorder r = listener.lastRecorder;
        try {
            gumdrop.shutdownNow();

            assertFalse(gumdrop.isStarted());
            assertEquals(1, r.disconnects.get());
            assertEquals(-1, client.getInputStream().read());
            gumdrop.shutdownNow();
            gumdrop.shutdown();
        } finally {
            client.close();
        }
    }

    @Test
    public void concurrentShutdownCallsAllReturnOnlyAfterCompletion() throws Exception {
        Socket client = bootWithOneConnection(0L);
        Recorder r = listener.lastRecorder;
        final int callers = 4;
        final CyclicBarrier barrier = new CyclicBarrier(callers);
        final AtomicInteger startedAtReturn = new AtomicInteger();
        Thread[] threads = new Thread[callers];
        for (int i = 0; i < callers; i++) {
            threads[i] = background(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        background.set(e);
                        return;
                    }
                    gumdrop.shutdown();
                    if (gumdrop.isStarted()) {
                        startedAtReturn.incrementAndGet();
                    }
                }
            }, "concurrent-shutdown-" + i);
        }
        try {
            for (int i = 0; i < callers; i++) {
                threads[i].join(HANG_GUARD_MS);
                assertFalse(threads[i].isAlive());
            }
            assertEquals("no caller returns while the runtime is still shutting down",
                    0, startedAtReturn.get());
            assertEquals("each connection is closed exactly once", 1, r.disconnects.get());
        } finally {
            client.close();
        }
    }
}
