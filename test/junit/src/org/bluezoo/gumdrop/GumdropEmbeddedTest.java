/*
 * GumdropEmbeddedTest.java
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Gumdrop#embedded}: a runtime with a caller-supplied loop and
 * executor that never starts a thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropEmbeddedTest {

    private static Set<Thread> liveThreads() {
        return new HashSet<Thread>(Thread.getAllStackTraces().keySet());
    }

    /** Names of runtime threads started since {@code before}. */
    private static List<String> runtimeThreadsSince(Set<Thread> before) {
        List<String> names = new ArrayList<String>();
        Set<Thread> now = liveThreads();
        for (Thread t : now) {
            if (before.contains(t)) {
                continue;
            }
            String name = t.getName();
            if (name.startsWith("SelectorLoop") || name.startsWith("gumdrop")
                    || name.startsWith("Gumdrop") || name.startsWith("ScheduledTimer")
                    || name.startsWith("AcceptSelectorLoop")) {
                names.add(name);
            }
        }
        return names;
    }

    private static final class CapturingExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }

    private static final class RecordingServer implements Server {
        int startCount;
        int stopCount;

        @Override
        @SuppressWarnings("rawtypes")
        public List getListeners() {
            return new ArrayList();
        }

        @Override
        public void start(Gumdrop gumdrop) {
            startCount++;
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }

    /** Dispatches results straight back onto the calling thread. */
    private static final class CallerDispatch implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class Result<T> implements StorageExecutor.Callback<T>,
            CryptoExecutor.Callback<T> {
        T value;
        Throwable error;
        Thread completedOn;

        @Override
        public void completed(T result) {
            value = result;
            completedOn = Thread.currentThread();
        }

        @Override
        public void failed(Throwable e) {
            error = e;
            completedOn = Thread.currentThread();
        }
    }

    private static final class Probe implements Callable<String> {
        Thread workedOn;
        final boolean fail;

        Probe(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String call() {
            workedOn = Thread.currentThread();
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return "done";
        }
    }

    @Test
    public void lifecycleStartsNoThreads() throws Exception {
        Set<Thread> before = liveThreads();
        Gumdrop gumdrop = TestGumdrop.create();
        RecordingServer server = new RecordingServer();
        gumdrop.addServer(server);
        gumdrop.start();
        assertTrue(gumdrop.isStarted());
        assertEquals(1, server.startCount);
        assertTrue(gumdrop.isReady());
        gumdrop.shutdown();
        gumdrop.join();
        assertFalse(gumdrop.isStarted());
        assertEquals(1, server.stopCount);
        List<String> started = runtimeThreadsSince(before);
        assertTrue("threads started: " + started, started.isEmpty());
    }

    @Test
    public void servicesAreAvailableBeforeStart() {
        Gumdrop gumdrop = TestGumdrop.create();
        assertFalse(gumdrop.isStarted());
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        assertTrue(loop instanceof InlineSelectorLoop);
        assertSame(loop, gumdrop.nextWorkerLoop());
        assertSame(gumdrop, loop.getGumdrop());
        assertNotNull(gumdrop.getStorageExecutor());
        assertNotNull(gumdrop.getCryptoExecutor());
    }

    @Test
    public void storageWorkRunsOnCallingThread() {
        Gumdrop gumdrop = TestGumdrop.create();
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        gumdrop.getStorageExecutor().submit(new CallerDispatch(), probe, result);
        assertSame(Thread.currentThread(), probe.workedOn);
        assertEquals("done", result.value);
        assertSame(Thread.currentThread(), result.completedOn);
    }

    @Test
    public void storageFailureIsReportedInline() {
        Gumdrop gumdrop = TestGumdrop.create();
        Result<String> result = new Result<String>();
        gumdrop.getStorageExecutor().submit(new CallerDispatch(), new Probe(true), result);
        assertTrue(result.error instanceof IllegalStateException);
        assertNull(result.value);
    }

    @Test
    public void cryptoWorkRunsOnCallingThread() {
        Gumdrop gumdrop = TestGumdrop.create();
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        gumdrop.getCryptoExecutor().submit(new CallerDispatch(), probe, result);
        assertSame(Thread.currentThread(), probe.workedOn);
        assertEquals("done", result.value);
    }

    @Test
    public void cryptoFailureIsReportedInline() {
        Gumdrop gumdrop = TestGumdrop.create();
        Result<String> result = new Result<String>();
        gumdrop.getCryptoExecutor().submit(new CallerDispatch(), new Probe(true), result);
        assertTrue(result.error instanceof IllegalStateException);
    }

    @Test
    public void capturedWorkRunsOnlyWhenTheTestRunsIt() {
        CapturingExecutor offload = new CapturingExecutor();
        Gumdrop gumdrop = TestGumdrop.create(offload);
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        gumdrop.getStorageExecutor().submit(new CallerDispatch(), probe, result);
        assertEquals(1, offload.tasks.size());
        assertNull(probe.workedOn);
        assertNull(result.value);
        offload.tasks.get(0).run();
        assertEquals("done", result.value);
    }

    @Test
    public void executorsSurviveRestart() throws Exception {
        Gumdrop gumdrop = TestGumdrop.create();
        gumdrop.start();
        gumdrop.shutdown();
        assertNotNull(gumdrop.getStorageExecutor());
        gumdrop.start();
        assertTrue(gumdrop.isStarted());
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        gumdrop.getCryptoExecutor().submit(new CallerDispatch(), probe, result);
        assertEquals("done", result.value);
        gumdrop.shutdown();
    }

    @Test
    public void timersNeverFireAndCanBeCancelled() {
        Set<Thread> before = liveThreads();
        Gumdrop gumdrop = TestGumdrop.create();
        boolean[] fired = new boolean[1];
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                fired[0] = true;
            }
        };
        TimerHandle handle = gumdrop.scheduleTimer(null, 0L, callback);
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertFalse(fired[0]);
        List<String> started = runtimeThreadsSince(before);
        assertTrue("threads started: " + started, started.isEmpty());
    }

    @Test
    public void activeClientsDoNotTriggerAutoShutdown() {
        Set<Thread> before = liveThreads();
        Gumdrop gumdrop = TestGumdrop.create();
        gumdrop.start();
        ClientEndpoint client = new ClientEndpoint(new TcpTransportFactory(),
                gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(client);
        assertEquals(1, gumdrop.getActiveClients().size());
        gumdrop.removeClient(client);
        assertTrue(gumdrop.getActiveClients().isEmpty());
        assertTrue(gumdrop.isStarted());
        List<String> started = runtimeThreadsSince(before);
        assertTrue("threads started: " + started, started.isEmpty());
        gumdrop.shutdown();
    }

    @Test
    public void acceptLoopIsNotSupported() {
        Gumdrop gumdrop = TestGumdrop.create();
        try {
            gumdrop.ensureAcceptLoop();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertNull(gumdrop.getAcceptLoop());
        }
    }

    @Test
    public void rejectsNullArguments() {
        try {
            Gumdrop.embedded(null, new CapturingExecutor());
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            Gumdrop.embedded(new InlineSelectorLoop(), null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    private static final class RejectingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("full");
        }
    }

    @Test
    public void rejectedStorageWorkFailsTheCallback() {
        StorageExecutor storage = new StorageExecutor(new RejectingExecutor());
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        storage.submit(new CallerDispatch(), probe, result);
        assertNull(probe.workedOn);
        assertTrue(result.error instanceof RejectedExecutionException);
        assertEquals(0, storage.pendingCount());
        storage.shutdown();
    }

    @Test
    public void rejectedCryptoWorkFailsTheCallback() {
        CryptoExecutor crypto = new CryptoExecutor(new RejectingExecutor());
        Probe probe = new Probe(false);
        Result<String> result = new Result<String>();
        crypto.submit(new CallerDispatch(), probe, result);
        assertNull(probe.workedOn);
        assertTrue(result.error instanceof RejectedExecutionException);
        assertEquals(0, crypto.pendingCount());
        crypto.shutdown();
    }

    @Test
    public void executorsRejectANullExecutor() {
        try {
            new StorageExecutor((Executor) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new CryptoExecutor((Executor) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }
}
