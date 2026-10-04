/*
 * ServletResponseBackpressureTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A servlet writing its response from a worker thread must be held back
 * when it produces body data faster than the connection can send it. The
 * transport closes a connection whose outbound buffer passes its ceiling,
 * so without that the response is silently truncated.
 *
 * <p>The transport here is a model of an HTTP/1.1 connection with the
 * slowest possible peer: tasks handed to {@link HttpResponse#execute}
 * queue for a separate "loop" thread, body data given to the transport
 * stays pending, and it is only written out once somebody is waiting for
 * the connection to become writable. Pending data beyond the ceiling
 * counts as an overflow.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletResponseBackpressureTest {

    private static final int CEILING = TransportFactory.DEFAULT_MAX_NET_OUT_SIZE;
    private static final long WAIT_MS = 20000L;

    private ModelTransport transport;
    private ServletHandler handler;

    @Before
    public void setUp() {
        transport = new ModelTransport();
        Container container = new Container();
        handler = new ServletHandler(container, transport, 8192);
    }

    @After
    public void tearDown() throws InterruptedException {
        transport.shutdown();
    }

    private void write(int total, int chunkSize) {
        int left = total;
        while (left > 0) {
            int len = Math.min(left, chunkSize);
            ByteBuffer chunk = ByteBuffer.allocate(len);
            handler.writeBody(chunk, true);
            left -= len;
        }
    }

    /**
     * Body data handed over by the worker but not yet picked up by the
     * loop is unsent data too. While the loop is busy elsewhere the
     * transport reports nothing pending, and the response must still stop
     * being writable long before a ceiling's worth has built up.
     */
    @Test
    public void dataQueuedForTheLoopCountsAsUnsent() {
        final int chunkSize = 1000;
        long handedOver = 0L;
        while (handedOver < 4L * CEILING && handler.isResponseWritable()) {
            ByteBuffer chunk = ByteBuffer.allocate(chunkSize);
            handler.writeBody(chunk, true);
            handedOver += (long) chunkSize;
        }
        assertTrue("worker could hand over " + handedOver
                + " bytes without having to wait for the connection",
                handedOver < (long) CEILING);

        transport.runQueued();
        transport.writeOut();
        assertFalse("outbound buffer overflowed", transport.overflowed);
        assertEquals(handedOver, transport.delivered);
        assertTrue(handler.isResponseWritable());
    }

    @Test(timeout = WAIT_MS)
    public void workerIsHeldBelowTheOutboundCeiling() throws Exception {
        final int total = 16 * 1024 * 1024;
        transport.startLoop();
        write(total, 1000);
        transport.awaitQueued();
        assertFalse("outbound buffer overflowed", transport.overflowed);
        assertEquals((long) total, transport.delivered);
    }

    /**
     * A single write larger than the outbound ceiling must reach the
     * transport in pieces it can hold.
     */
    @Test(timeout = WAIT_MS)
    public void largeSingleWriteIsNotSentInOnePiece() throws Exception {
        final int total = CEILING + (CEILING / 2);
        transport.startLoop();
        write(total, total);
        transport.awaitQueued();
        assertFalse("outbound buffer overflowed", transport.overflowed);
        assertEquals((long) total, transport.delivered);
    }

    /**
     * A transport that never holds unsent data never reports a completed
     * write either. A worker that got ahead of the loop must not wait for
     * one.
     */
    @Test(timeout = WAIT_MS)
    public void workerDoesNotWaitForATransportHoldingNothing() throws Exception {
        final int total = 8 * 1024 * 1024;
        transport.holdsData = false;
        transport.startLoop();
        write(total, 1000);
        transport.awaitQueued();
        assertEquals((long) total, transport.delivered);
    }

    /**
     * Model of a connection. All fields other than the task queue belong
     * to the loop thread, as they would on a real connection.
     */
    private static final class ModelTransport implements HttpResponse {

        private final Object lock = new Object();
        private final List<Runnable> tasks = new ArrayList<Runnable>();
        private Thread loop;
        private boolean stopped;

        boolean holdsData = true;
        private volatile int pending;
        volatile boolean overflowed;
        volatile long delivered;

        void startLoop() {
            loop = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop();
                }
            }, "test-loop");
            loop.start();
        }

        void shutdown() throws InterruptedException {
            synchronized (lock) {
                stopped = true;
                lock.notifyAll();
            }
            if (loop != null) {
                loop.join(WAIT_MS);
            }
        }

        /** Waits until the loop has run everything queued so far. */
        void awaitQueued() throws InterruptedException {
            final CountDownLatch done = new CountDownLatch(1);
            execute(new Runnable() {
                @Override
                public void run() {
                    done.countDown();
                }
            });
            assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
        }

        /** Runs everything queued, on the calling thread. */
        void runQueued() {
            List<Runnable> batch;
            synchronized (lock) {
                batch = new ArrayList<Runnable>(tasks);
                tasks.clear();
            }
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).run();
            }
        }

        private void runLoop() {
            while (true) {
                synchronized (lock) {
                    while (tasks.isEmpty() && !stopped) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (tasks.isEmpty()) {
                        return;
                    }
                }
                runQueued();
            }
        }

        /** The socket takes everything pending: the write has completed. */
        void writeOut() {
            pending = 0;
        }

        @Override
        public void execute(Runnable task) {
            synchronized (lock) {
                tasks.add(task);
                lock.notifyAll();
            }
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            int length = data.remaining();
            delivered += (long) length;
            if (!holdsData) {
                return;
            }
            int total = pending + length;
            if (total > CEILING) {
                overflowed = true;
            }
            pending = total;
        }

        @Override
        public void onWritable(Runnable callback) {
            if (callback == null) {
                return;
            }
            if (pending == 0) {
                // nothing to write, so no write will complete: a real
                // connection never calls back here
                return;
            }
            writeOut();
            callback.run();
        }

        @Override
        public int pendingResponseBytes() {
            return pending;
        }

        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_1_1; }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void status(int code) { }
        @Override public void header(String name, ByteBuffer rawValue) { }
        @Override public void endHeaders() { }
        @Override public void endMessage() { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(HttpMethod method, String target) { }
        @Override public boolean endPushPromise() { return false; }
        @Override public void upgradeToWebSocket(String protocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }
}
