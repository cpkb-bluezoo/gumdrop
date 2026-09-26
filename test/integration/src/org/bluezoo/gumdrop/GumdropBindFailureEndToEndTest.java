/*
 * GumdropBindFailureEndToEndTest.java
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

import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A listener that cannot bind must not leave Gumdrop reporting itself ready:
 * {@link Gumdrop#isReady()} promises that all listeners are bound.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropBindFailureEndToEndTest {

    /**
     * Listeners added after boot are bound asynchronously on the accept loop,
     * so wait for the outcome: a recorded failure, or the port accepting.
     */
    private static void awaitBindOutcome(Gumdrop gumdrop, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!gumdrop.getBindFailures().isEmpty()) {
                return;
            }
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100);
                return;
            } catch (java.io.IOException notYet) {
                Thread.sleep(20);
            }
        }
        fail("the listener neither bound nor reported a bind failure within 5s");
    }

    @Test
    public void portInUseIsReportedAndGumdropIsNotReady() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServerSocket occupier = new ServerSocket(0, 1, loopback);
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().drainTimeoutMs(0));
        try {
            gumdrop.addListener(new Http2Listener().port(occupier.getLocalPort()).addresses(loopback));
            awaitBindOutcome(gumdrop, occupier.getLocalPort());

            List<String> failures = gumdrop.getBindFailures();
            assertEquals(failures.toString(), 1, failures.size());
            assertTrue(failures.get(0), failures.get(0).contains("Address already in use"));
            assertFalse("a listener that failed to bind must not count as ready", gumdrop.isReady());
        } finally {
            occupier.close();
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void successfulBindIsReadyWithNoFailures() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, loopback)) {
            port = free.getLocalPort();
        }
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().drainTimeoutMs(0));
        try {
            gumdrop.addListener(new Http2Listener().port(port).addresses(loopback));
            awaitBindOutcome(gumdrop, port);

            assertTrue(gumdrop.getBindFailures().isEmpty());
            assertTrue(gumdrop.isReady());
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }
}
