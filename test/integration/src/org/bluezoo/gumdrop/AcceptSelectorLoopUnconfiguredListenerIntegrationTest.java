/*
 * AcceptSelectorLoopUnconfiguredListenerIntegrationTest.java
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
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * A listener that has neither a port nor a UNIX socket path configured is a
 * configuration error. It must be reported as a bind failure of that one
 * listener, not kill the accept thread (an unchecked exception from the
 * bind used to end {@link AcceptSelectorLoop}, silently stopping every
 * other listener in the process).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoopUnconfiguredListenerIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    private static final class Unconfigured extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "unconfigured";
        }
    }

    private static final class Good extends TcpListener {
        final CountDownLatch bound = new CountDownLatch(1);

        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        protected void applyBoundTcpPort(int port) {
            bound.countDown();
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public String getDescription() {
            return "good";
        }
    }

    @Test
    public void unconfiguredListenerIsABindFailureAndOthersStillBind() throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        Unconfigured bad = new Unconfigured();
        bad.addresses(InetAddress.getLoopbackAddress());
        Good good = new Good();
        good.addresses(InetAddress.getLoopbackAddress());
        final CountDownLatch ready = new CountDownLatch(1);
        loop.registerListener(bad);
        loop.registerListener(good);
        loop.onReady(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
            }
        });
        loop.start();
        try {
            assertTrue(ready.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertTrue("the good listener still bound", good.bound.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertTrue("the accept thread survived", loop.isRunning());
            List<String> failures = loop.getBindFailures();
            assertEquals(1, failures.size());
            assertTrue(failures.get(0), failures.get(0).startsWith("unconfigured: "));
        } finally {
            loop.shutdown();
            loop.join(HANG_GUARD_MS);
        }
    }
}
