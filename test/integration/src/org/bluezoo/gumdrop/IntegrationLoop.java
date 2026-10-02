/*
 * IntegrationLoop.java
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test helper for the architecture rule that all network I/O of a
 * connection happens on that connection's own {@link SelectorLoop}. A test
 * thread must never call {@code send}, {@code sendTo} or {@code close} on
 * an endpoint directly; it runs the call on the endpoint's loop with this
 * helper and waits (as a hang guard only) for it to have been performed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IntegrationLoop {

    private static final long GUARD_SECONDS = 10;

    private IntegrationLoop() {
    }

    /**
     * Runs the task on the given loop and waits for it to complete. If the
     * loop is not running (already shut down) the task runs inline, since
     * there is no loop thread left to race.
     *
     * @param loop the loop owning the connection
     * @param task the work to perform
     */
    public static void run(SelectorLoop loop, final Runnable task) {
        Thread loopThread = null;
        if (loop != null) {
            loopThread = loop.getThread();
        }
        if (loopThread == null || !loopThread.isAlive()
                || loopThread == Thread.currentThread()) {
            task.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(GUARD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the endpoint on its own loop.
     *
     * @param endpoint the endpoint to close
     */
    public static void close(final Endpoint endpoint) {
        run(endpoint.getSelectorLoop(), new Runnable() {
            @Override
            public void run() {
                endpoint.close();
            }
        });
    }

    /**
     * Sends on the endpoint on its own loop.
     *
     * @param endpoint the endpoint
     * @param data the data to send
     */
    public static void send(final Endpoint endpoint, final java.nio.ByteBuffer data) {
        run(endpoint.getSelectorLoop(), new Runnable() {
            @Override
            public void run() {
                endpoint.send(data);
            }
        });
    }
}
