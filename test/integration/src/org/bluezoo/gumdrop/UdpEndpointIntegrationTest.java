/*
 * UdpEndpointIntegrationTest.java
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

import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.util.DirectByteBufferPool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Loopback integration tests for {@link UdpEndpoint}: real datagram
 * channels, selector registration, and Gumdrop lifecycle. Pure buffer-
 * pool / queue logic lives in {@code org.bluezoo.gumdrop.UDPEndpointTest} ({@code ant test}).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointIntegrationTest {

    private Gumdrop gumdrop;

    private static final class NoopHandler implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void disconnected() { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void error(Exception cause) { }
    }

    @Before
    public void setUp() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
    }

    /** The pending-datagram queue belongs to the endpoint's loop. */
    private static boolean enqueueOnLoop(final UdpEndpoint endpoint, final ByteBuffer data,
            final InetSocketAddress dest) {
        final boolean[] result = new boolean[1];
        IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
            @Override
            public void run() {
                result[0] = endpoint.enqueuePendingDatagram(data, dest);
            }
        });
        return result[0];
    }

    @After
    public void tearDown() throws InterruptedException {
        gumdrop.shutdown();
        gumdrop.join();
    }

    @Test
    public void testNetInIsAPooledDirectBuffer() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();

        UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        try {
            assertNotNull(endpoint.netIn);
            assertTrue("netIn must be a direct buffer, not a heap allocation",
                    endpoint.netIn.isDirect());
        } finally {
            IntegrationLoop.close(endpoint);
        }
    }

    @Test
    public void testNetInIsReturnedToThePoolOnClose() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();

        UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        ByteBuffer netIn = endpoint.netIn;
        int capacity = netIn.capacity();

        // The buffer pools are per thread: close and reacquire on the loop
        // that owns the endpoint, where the buffer is released.
        final int cap = capacity;
        final ByteBuffer[] reacquired = new ByteBuffer[1];
        final UdpEndpoint ep = endpoint;
        IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
            @Override
            public void run() {
                ep.close();
                reacquired[0] = DirectByteBufferPool.acquire(cap);
            }
        });
        assertNull("netIn must be cleared once released", endpoint.netIn);
        try {
            assertSame("closing the endpoint must release netIn back to the pool",
                    netIn, reacquired[0]);
        } finally {
            final ByteBuffer toRelease = reacquired[0];
            IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
                @Override
                public void run() {
                    DirectByteBufferPool.release(toRelease);
                }
            });
        }
    }

    @Test
    public void testPendingDatagramBuffersReleasedOnClose() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();

        UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        final ByteBuffer[] acquired = new ByteBuffer[1];
        IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
            @Override
            public void run() {
                acquired[0] = ByteBufferPool.acquire(128);
            }
        });
        ByteBuffer pending = acquired[0];
        pending.put(new byte[64]);
        pending.flip();
        int capacity = pending.capacity();

        try {
            assertTrue("datagram must be accepted into the pending queue",
                    enqueueOnLoop(endpoint, pending,
                            (InetSocketAddress) endpoint.getLocalAddress()));
            assertFalse("datagram must be queued for write",
                    endpoint.pendingDatagrams.isEmpty());

            // The buffer pools are per thread: close and reacquire on the
            // loop that owns the endpoint, where the buffers are released.
            final int cap = capacity;
            final ByteBuffer[] reacquired = new ByteBuffer[1];
            final UdpEndpoint ep = endpoint;
            IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
                @Override
                public void run() {
                    ep.close();
                    reacquired[0] = ByteBufferPool.acquire(cap);
                }
            });
            assertTrue("pending queue must be drained on close",
                    endpoint.pendingDatagrams.isEmpty());
            try {
                assertSame("close() must release queued datagram buffers",
                        pending, reacquired[0]);
            } finally {
                final ByteBuffer toRelease = reacquired[0];
                IntegrationLoop.run(endpoint.getSelectorLoop(), new Runnable() {
                    @Override
                    public void run() {
                        ByteBufferPool.release(toRelease);
                    }
                });
            }
        } finally {
            if (endpoint.isOpen()) {
                IntegrationLoop.close(endpoint);
            }
        }
    }

    @Test
    public void testPendingDatagramQueueCapClosesEndpoint() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setMaxNetOutSize(100);
        factory.start();

        UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        InetSocketAddress dest = (InetSocketAddress) endpoint.getLocalAddress();

        try {
            ByteBuffer first = ByteBufferPool.acquire(64);
            first.put(new byte[60]);
            first.flip();
            assertTrue("first datagram must fit under queue cap",
                    enqueueOnLoop(endpoint, first, dest));
            assertTrue("endpoint stays open under queue cap", endpoint.isOpen());

            ByteBuffer second = ByteBufferPool.acquire(64);
            second.put(new byte[50]);
            second.flip();
            assertFalse("overflowing the pending queue must reject the datagram",
                    enqueueOnLoop(endpoint, second, dest));
            assertFalse("overflowing the pending queue must close the endpoint",
                    endpoint.isOpen());
        } finally {
            if (endpoint.isOpen()) {
                IntegrationLoop.close(endpoint);
            }
        }
    }
}
