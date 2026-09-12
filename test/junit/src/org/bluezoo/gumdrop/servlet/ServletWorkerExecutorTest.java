/*
 * ServletWorkerExecutorTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Tests servlet worker dispatch uses virtual threads.
 */
public class ServletWorkerExecutorTest {

    @Test
    public void testWorkerTaskRunsOnVirtualThread() throws Exception {
        ServletService service = new ServletService();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean virtual = new AtomicBoolean();
        service.executeWorker(new Runnable() {
            @Override
            public void run() {
                virtual.set(Thread.currentThread().isVirtual());
                done.countDown();
            }
        }, null);
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(virtual.get());
    }

    @Test
    public void testWorkerThreadNaming() throws Exception {
        ServletService service = new ServletService();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean named = new AtomicBoolean();
        service.executeWorker(new Runnable() {
            @Override
            public void run() {
                named.set(Thread.currentThread().getName().startsWith("servlet-worker-"));
                done.countDown();
            }
        }, null);
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(named.get());
    }
}
