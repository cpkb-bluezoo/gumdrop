/*
 * TlsHandshakeAsyncOffloadTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Concurrency regressions for {@link TlsHandshakeAsyncOffload}, shared by
 * TCP, DTLS, and QUIC record engines.
 */
public class TlsHandshakeAsyncOffloadTest {

    private Gumdrop gumdrop;

    @Before
    public void setUp() {
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
    }

    @After
    public void tearDown() {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test(timeout = 10000)
    public void testIsBusyStaysTrueWhileCompletionHandlerStartsAFollowUpBatch() throws Exception {
        final TlsHandshakeAsyncOffload offload = newOffload();
        final CountDownLatch completionRan = new CountDownLatch(1);
        final AtomicBoolean busyWhenFollowUpDecided = new AtomicBoolean();

        TlsHandshakeAsyncOffload.BatchProcessor noopBatch = new TlsHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
            }
        };

        offload.submit(noopBatch, new TlsHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                busyWhenFollowUpDecided.set(offload.isBusy());
                offload.submit(noopBatch, new TlsHandshakeAsyncOffload.CompletionHandler() {
                    @Override
                    public boolean onBatchDone() {
                        return false;
                    }
                }, noopFailure());
                completionRan.countDown();
                return true;
            }
        }, noopFailure());

        assertTrue("completion handler should have run", completionRan.await(5, TimeUnit.SECONDS));
        assertTrue("isBusy() must still report true while the completion handler is "
                + "synchronously starting a follow-up batch", busyWhenFollowUpDecided.get());
    }

    @Test(timeout = 10000)
    public void testPendingWorkCannotRaceCompletionHandlerDrain() throws Exception {
        final TlsHandshakeAsyncOffload offload = newOffload();
        final CountDownLatch onBatchDoneEntered = new CountDownLatch(1);
        final CountDownLatch contenderStarted = new CountDownLatch(1);
        final CountDownLatch onBatchDoneMayFinish = new CountDownLatch(1);
        final AtomicBoolean onBatchDoneFinished = new AtomicBoolean();
        final AtomicBoolean contenderObservedFinished = new AtomicBoolean();

        TlsHandshakeAsyncOffload.CompletionHandler blockingHandler =
                new TlsHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                onBatchDoneEntered.countDown();
                try {
                    assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
                    assertTrue(onBatchDoneMayFinish.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onBatchDoneFinished.set(true);
                return false;
            }
        };

        offload.submit(new TlsHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
            }
        }, blockingHandler, noopFailure());

        assertTrue("completion handler should have started", onBatchDoneEntered.await(5, TimeUnit.SECONDS));

        Thread contender = new Thread(new Runnable() {
            @Override
            public void run() {
                contenderStarted.countDown();
                synchronized (offload.lock()) {
                    contenderObservedFinished.set(onBatchDoneFinished.get());
                }
            }
        });
        contender.start();
        onBatchDoneMayFinish.countDown();
        contender.join(5000);

        assertTrue("a concurrent caller acquiring offload.lock() must observe onBatchDone() "
                + "as already finished", contenderObservedFinished.get());
    }

    @Test(timeout = 10000)
    public void testIsBusyClearsOnceNoFollowUpBatchIsSubmitted() throws Exception {
        final TlsHandshakeAsyncOffload offload = newOffload();
        final CountDownLatch completionRan = new CountDownLatch(1);

        offload.submit(new TlsHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
            }
        }, new TlsHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                completionRan.countDown();
                return false;
            }
        }, noopFailure());

        assertTrue("completion handler should have run", completionRan.await(5, TimeUnit.SECONDS));
        assertFalse("isBusy() must clear once the completion handler reports no follow-up batch",
                offload.isBusy());
    }

    private static TlsHandshakeAsyncOffload newOffload() {
        return new TlsHandshakeAsyncOffload(new Executor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        });
    }

    private static TlsHandshakeAsyncOffload.FailureHandler noopFailure() {
        return new TlsHandshakeAsyncOffload.FailureHandler() {
            @Override
            public void failed(Throwable error) {
            }
        };
    }
}
