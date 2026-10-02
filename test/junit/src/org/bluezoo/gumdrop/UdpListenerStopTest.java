/*
 * UdpListenerStopTest.java
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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * {@link UdpListener#stop()} must not close its {@link UdpEndpoint} on the
 * caller's thread: the endpoint belongs to a selector loop, so the close is
 * handed to that loop and runs on its thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpListenerStopTest {

    private static final long HANG_GUARD_MS = 60000L;

    private Gumdrop gumdrop;

    @After
    public void tearDown() {
        if (gumdrop != null) {
            gumdrop.shutdownNow();
        }
    }

    private static final class Probe implements ProtocolHandler {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Thread> closeThread = new AtomicReference<Thread>();

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
            closeThread.set(Thread.currentThread());
            closed.countDown();
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static final class ProbeListener extends UdpListener {
        final Probe probe = new Probe();

        @Override
        protected ProtocolHandler createProtocolHandler() {
            return probe;
        }

        @Override
        public String getDescription() {
            return "udp-stop-test";
        }

        @Override
        public int getPort() {
            return 0;
        }
    }

    @Test
    public void stopClosesTheEndpointOnItsLoopThread() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        ProbeListener listener = new ProbeListener();
        listener.start(gumdrop);

        listener.stop();

        assertTrue(listener.probe.closed.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        Thread closer = listener.probe.closeThread.get();
        assertNotSame("endpoint closed by the caller", Thread.currentThread(), closer);
        assertTrue(closer.getName(), closer.getName().startsWith("SelectorLoop-"));
    }
}
