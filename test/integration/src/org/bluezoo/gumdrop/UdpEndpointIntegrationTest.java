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
            endpoint.close();
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

        endpoint.close();
        assertNull("netIn must be cleared once released", endpoint.netIn);

        ByteBuffer reacquired = DirectByteBufferPool.acquire(capacity);
        try {
            assertSame("closing the endpoint must release netIn back to the pool",
                    netIn, reacquired);
        } finally {
            DirectByteBufferPool.release(reacquired);
        }
    }

    @Test
    public void testPendingDatagramBuffersReleasedOnClose() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.start();

        UdpEndpoint endpoint = factory.createServerEndpoint(
                gumdrop, InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        ByteBuffer pending = ByteBufferPool.acquire(128);
        pending.put(new byte[64]);
        pending.flip();
        int capacity = pending.capacity();

        try {
            assertTrue("datagram must be accepted into the pending queue",
                    endpoint.enqueuePendingDatagram(pending,
                            (InetSocketAddress) endpoint.getLocalAddress()));
            assertFalse("datagram must be queued for write",
                    endpoint.pendingDatagrams.isEmpty());

            endpoint.close();
            assertTrue("pending queue must be drained on close",
                    endpoint.pendingDatagrams.isEmpty());

            ByteBuffer reacquired = ByteBufferPool.acquire(capacity);
            try {
                assertSame("close() must release queued datagram buffers",
                        pending, reacquired);
            } finally {
                ByteBufferPool.release(reacquired);
            }
        } finally {
            if (endpoint.isOpen()) {
                endpoint.close();
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
                    endpoint.enqueuePendingDatagram(first, dest));
            assertTrue("endpoint stays open under queue cap", endpoint.isOpen());

            ByteBuffer second = ByteBufferPool.acquire(64);
            second.put(new byte[50]);
            second.flip();
            assertFalse("overflowing the pending queue must reject the datagram",
                    endpoint.enqueuePendingDatagram(second, dest));
            assertFalse("overflowing the pending queue must close the endpoint",
                    endpoint.isOpen());
        } finally {
            if (endpoint.isOpen()) {
                endpoint.close();
            }
        }
    }
}
