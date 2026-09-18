/*
 * GumdropAwaitShutdownTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Ctrl+C on Unix often interrupts a blocked {@link Gumdrop#join()} without
 * running shutdown hooks first. {@link Gumdrop#awaitShutdown()} must initiate
 * {@link Gumdrop#shutdown()} on interrupt so the runtime drains and exits cleanly.
 */
public class GumdropAwaitShutdownTest {

    private Gumdrop gumdrop;

    @After
    public void tearDown() throws InterruptedException {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void interruptDuringAwaitShutdownInitiatesShutdown() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        final CountDownLatch blocked = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    blocked.countDown();
                    gumdrop.awaitShutdown();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        }, "await-shutdown-test");
        waiter.start();
        blocked.await();
        waiter.interrupt();
        waiter.join(10_000);
        assertNull(failure.get());
        assertFalse("runtime should be shut down after interrupt", gumdrop.isStarted());
    }
}
