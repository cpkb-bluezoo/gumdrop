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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * A listener left on the default "all local addresses" must still come up
 * when one of those addresses cannot be bound. Containers hit this for real:
 * a freshly created interface's IPv6 link-local address is still tentative
 * (duplicate address detection pending), and binding it fails with
 * "Cannot assign requested address", which used to abort the whole listener
 * so that nothing was served at all. An address the operator named
 * explicitly stays a hard failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoopUnbindableAddressIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    /** TEST-NET-1 (RFC 5737): never assigned to a local interface. */
    private static final String UNASSIGNED = "192.0.2.1";

    private static final class Probe extends TcpListener {
        final CountDownLatch bound = new CountDownLatch(1);
        private final boolean explicit;

        Probe(boolean explicit) throws Exception {
            this.explicit = explicit;
            if (explicit) {
                addresses(InetAddress.getByName(UNASSIGNED),
                        InetAddress.getLoopbackAddress());
            }
        }

        @Override
        public Set<InetAddress> getAddresses() {
            if (explicit) {
                return super.getAddresses();
            }
            try {
                // Stands in for the enumerated NIC addresses.
                Set<InetAddress> all = new LinkedHashSet<InetAddress>();
                all.add(InetAddress.getByName(UNASSIGNED));
                all.add(InetAddress.getLoopbackAddress());
                return all;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

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
            return "probe";
        }
    }

    private List<String> bindAndCollect(Probe probe, boolean expectBound) throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        final CountDownLatch ready = new CountDownLatch(1);
        loop.registerListener(probe);
        loop.onReady(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
            }
        });
        loop.start();
        try {
            assertTrue(ready.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
            assertEquals("loopback bound", expectBound,
                    probe.bound.await(expectBound ? HANG_GUARD_MS : 500L, TimeUnit.MILLISECONDS));
            assertTrue("the accept thread survived", loop.isRunning());
            return loop.getBindFailures();
        } finally {
            loop.shutdown();
            loop.join(HANG_GUARD_MS);
        }
    }

    @Test
    public void defaultAddressesSkipAnUnbindableOne() throws Exception {
        List<String> failures = bindAndCollect(new Probe(false), true);
        assertEquals(failures.toString(), 0, failures.size());
    }

    @Test
    public void explicitUnbindableAddressStillFails() throws Exception {
        List<String> failures = bindAndCollect(new Probe(true), false);
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("probe: "));
    }
}
