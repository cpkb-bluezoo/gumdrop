/*
 * StorageExecutorTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Unit tests for {@link StorageExecutor}: results and failures must be
 * delivered back on the endpoint's loop thread (never a storage thread), and
 * a saturated pool must fail fast rather than block the caller.
 */
public class StorageExecutorTest {

    private static final String LOOP_THREAD_NAME = "test-loop-thread";

    private LoopEndpoint endpoint;

    @Before
    public void setUp() {
        endpoint = new LoopEndpoint();
    }

    @After
    public void tearDown() {
        endpoint.shutdown();
    }

    @Test(timeout = 10000)
    public void testResultDeliveredOnLoopThread() throws Exception {
        StorageExecutor exec = new StorageExecutor(2, 16);
        try {
            final AtomicReference<String> result = new AtomicReference<String>();
            final AtomicReference<String> callbackThread =
                    new AtomicReference<String>();
            final AtomicReference<String> workThread =
                    new AtomicReference<String>();
            final CountDownLatch done = new CountDownLatch(1);

            exec.submit(endpoint, new Callable<String>() {
                @Override
                public String call() {
                    workThread.set(Thread.currentThread().getName());
                    return "hello";
                }
            }, new StorageExecutor.Callback<String>() {
                @Override
                public void completed(String r) {
                    result.set(r);
                    callbackThread.set(Thread.currentThread().getName());
                    done.countDown();
                }
                @Override
                public void failed(Throwable error) {
                    done.countDown();
                }
            });

            assertTrue("callback not invoked", done.await(5, TimeUnit.SECONDS));
            assertEquals("hello", result.get());
            assertEquals("callback must run on the loop thread",
                    LOOP_THREAD_NAME, callbackThread.get());
            assertTrue("blocking work must run on a storage thread, was "
                    + workThread.get(),
                    workThread.get().startsWith("gumdrop-storage-"));
        } finally {
            exec.shutdown();
        }
    }

    @Test(timeout = 10000)
    public void testExceptionDeliveredAsFailure() throws Exception {
        StorageExecutor exec = new StorageExecutor(2, 16);
        try {
            final AtomicReference<Throwable> error =
                    new AtomicReference<Throwable>();
            final AtomicReference<String> callbackThread =
                    new AtomicReference<String>();
            final CountDownLatch done = new CountDownLatch(1);

            exec.submit(endpoint, new Callable<String>() {
                @Override
                public String call() throws IOException {
                    throw new IOException("boom");
                }
            }, new StorageExecutor.Callback<String>() {
                @Override
                public void completed(String r) {
                    done.countDown();
                }
                @Override
                public void failed(Throwable t) {
                    error.set(t);
                    callbackThread.set(Thread.currentThread().getName());
                    done.countDown();
                }
            });

            assertTrue("callback not invoked", done.await(5, TimeUnit.SECONDS));
            assertNotNull("failure not propagated", error.get());
            assertTrue(error.get() instanceof IOException);
            assertEquals("boom", error.get().getMessage());
            assertEquals("failure callback must run on the loop thread",
                    LOOP_THREAD_NAME, callbackThread.get());
        } finally {
            exec.shutdown();
        }
    }

    @Test(timeout = 10000)
    public void testSaturatedPoolFailsFast() throws Exception {
        // One thread, queue of one: the third submission has nowhere to go and
        // must be reported as a failure rather than run on the caller thread.
        StorageExecutor exec = new StorageExecutor(1, 1);
        final CountDownLatch block = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        try {
            // Occupy the single worker thread.
            exec.submit(endpoint, new Callable<Void>() {
                @Override
                public Void call() throws InterruptedException {
                    started.countDown();
                    block.await(5, TimeUnit.SECONDS);
                    return null;
                }
            }, new NoopCallback<Void>());
            assertTrue(started.await(5, TimeUnit.SECONDS));

            // Fill the single queue slot.
            exec.submit(endpoint, new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            }, new NoopCallback<Void>());

            // This one must be rejected -> failed() with RejectedExecution.
            final AtomicReference<Throwable> error =
                    new AtomicReference<Throwable>();
            final CountDownLatch rejected = new CountDownLatch(1);
            final AtomicReference<String> callerThread =
                    new AtomicReference<String>(
                            Thread.currentThread().getName());
            final AtomicReference<Boolean> ranOnCaller =
                    new AtomicReference<Boolean>(Boolean.FALSE);
            exec.submit(endpoint, new Callable<Void>() {
                @Override
                public Void call() {
                    // Must never run: rejected before execution.
                    ranOnCaller.set(Boolean.TRUE);
                    return null;
                }
            }, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void r) {
                    rejected.countDown();
                }
                @Override
                public void failed(Throwable t) {
                    error.set(t);
                    rejected.countDown();
                }
            });

            assertTrue("rejection callback not invoked",
                    rejected.await(5, TimeUnit.SECONDS));
            assertTrue("expected RejectedExecutionException, got " + error.get(),
                    error.get() instanceof RejectedExecutionException);
            assertFalse("rejected work must not execute", ranOnCaller.get());
        } finally {
            block.countDown();
            exec.shutdown();
        }
    }

    private static final class NoopCallback<T>
            implements StorageExecutor.Callback<T> {
        @Override public void completed(T result) { }
        @Override public void failed(Throwable error) { }
    }

    /**
     * Minimal {@link Endpoint} that models a SelectorLoop by running every
     * {@link #execute} task on a single dedicated thread named
     * {@value #LOOP_THREAD_NAME}, so tests can assert callback thread affinity.
     */
    private static final class LoopEndpoint implements Endpoint {

        private final ExecutorService loop = Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, LOOP_THREAD_NAME);
                    }
                });

        void shutdown() {
            loop.shutdownNow();
        }

        @Override
        public void execute(Runnable task) {
            loop.execute(task);
        }

        @Override
        public void send(ByteBuffer data) { }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isClosing() {
            return false;
        }

        @Override
        public void close() { }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public void startTLS() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pauseRead() { }

        @Override
        public void resumeRead() { }

        @Override
        public void onWriteReady(Runnable callback) { }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return null;
        }

        @Override
        public Trace getTrace() {
            return null;
        }

        @Override
        public void setTrace(Trace trace) { }

        @Override
        public boolean isTelemetryEnabled() {
            return false;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return null;
        }
    }
}
