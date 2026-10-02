/*
 * GumdropBindFailureIntegrationTest.java
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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A listener that cannot bind must be reported, and must not leave Gumdrop
 * reporting itself ready: {@link Gumdrop#isReady()} promises that all
 * listeners are bound. Synchronises on the accept loop's ready callback, so
 * the outcome never depends on timing.
 *
 * <p>Integration test: boots a real runtime and occupies a real port to provoke
 * a kernel bind failure (EADDRINUSE) in the accept loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropBindFailureIntegrationTest {

    private static final long HANG_GUARD_MS = 60000;

    @Test
    public void loopReportsPortInUse() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServerSocket occupier = new ServerSocket(0, 1, loopback);
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        try {
            Http2Listener listener = new Http2Listener();
            listener.port(occupier.getLocalPort());
            listener.addresses(loopback);
            loop.registerListener(listener);
            final CountDownLatch ready = new CountDownLatch(1);
            loop.onReady(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                }
            });
            loop.start();
            assertTrue(ready.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));

            List<String> failures = loop.getBindFailures();
            assertEquals(failures.toString(), 1, failures.size());
            assertTrue(failures.get(0), failures.get(0).contains("Address already in use"));
        } finally {
            occupier.close();
            loop.shutdown();
        }
    }

    @Test
    public void loopReportsNoFailureWhenBound() throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        try {
            Http2Listener listener = new Http2Listener();
            listener.port(0);
            listener.addresses(InetAddress.getLoopbackAddress());
            loop.registerListener(listener);
            final CountDownLatch ready = new CountDownLatch(1);
            loop.onReady(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                }
            });
            loop.start();
            assertTrue(ready.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertTrue(loop.getBindFailures().toString(), loop.getBindFailures().isEmpty());
        } finally {
            loop.shutdown();
        }
    }

    private static Gumdrop restartWith(Http2Listener listener) throws Exception {
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().drainTimeoutMs(0));
        gumdrop.shutdown();
        gumdrop.join();
        gumdrop.addListener(listener);
        gumdrop.start();
        return gumdrop;
    }

    @Test
    public void gumdropNotReadyWhenPortInUse() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServerSocket occupier = new ServerSocket(0, 1, loopback);
        Gumdrop gumdrop = null;
        try {
            Http2Listener listener = new Http2Listener();
            listener.port(occupier.getLocalPort());
            listener.addresses(loopback);
            gumdrop = restartWith(listener);
            assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));

            List<String> failures = gumdrop.getBindFailures();
            assertEquals(failures.toString(), 1, failures.size());
            assertTrue(failures.get(0), failures.get(0).contains("Address already in use"));
            assertFalse(gumdrop.isReady());
        } finally {
            occupier.close();
            if (gumdrop != null) {
                gumdrop.shutdown();
                gumdrop.join();
            }
        }
    }

    @Test
    public void gumdropReadyWhenAllBound() throws Exception {
        Http2Listener listener = new Http2Listener();
        listener.port(0);
        listener.addresses(InetAddress.getLoopbackAddress());
        Gumdrop gumdrop = restartWith(listener);
        try {
            assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
            assertTrue(gumdrop.getBindFailures().isEmpty());
            assertTrue(gumdrop.isReady());
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }
}
