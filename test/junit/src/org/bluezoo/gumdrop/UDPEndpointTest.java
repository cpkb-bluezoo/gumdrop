/*
 * UDPEndpointTest.java
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

import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link UdpEndpoint} buffer and pending-queue behaviour
 * (issue #193). No sockets or Gumdrop runtime: loopback coverage is in
 * {@link UdpEndpointIntegrationTest} ({@code ant integration-test-loopback}).
 */
public class UDPEndpointTest {

    private static final class NoopHandler implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void disconnected() { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void error(Exception cause) { }
    }

    @Test
    public void testInitAllocatesPooledDirectNetIn() {
        UdpEndpoint endpoint = new UdpEndpoint(new NoopHandler());
        endpoint.init();
        try {
            assertNotNull(endpoint.netIn);
            assertTrue("netIn must be a direct buffer",
                    endpoint.netIn.isDirect());
        } finally {
            endpoint.close();
        }
    }

    @Test
    public void testNetInReleasedToPoolOnClose() {
        UdpEndpoint endpoint = new UdpEndpoint(new NoopHandler());
        endpoint.init();
        ByteBuffer netIn = endpoint.netIn;
        int capacity = netIn.capacity();
        endpoint.close();
        assertNull("netIn must be cleared once released", endpoint.netIn);

        ByteBuffer reacquired = DirectByteBufferPool.acquire(capacity);
        try {
            assertSame("close() must release netIn back to the pool",
                    netIn, reacquired);
        } finally {
            DirectByteBufferPool.release(reacquired);
        }
    }

    @Test
    public void testPendingDatagramBuffersReleasedOnClose() {
        UdpEndpoint endpoint = new UdpEndpoint(new NoopHandler());
        endpoint.init();
        ByteBuffer pending = ByteBufferPool.acquire(128);
        pending.put(new byte[64]);
        pending.flip();
        int capacity = pending.capacity();
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 9);

        assertTrue(endpoint.enqueuePendingDatagram(pending, dest));
        assertFalse(endpoint.pendingDatagrams.isEmpty());

        endpoint.close();
        assertTrue(endpoint.pendingDatagrams.isEmpty());

        ByteBuffer reacquired = ByteBufferPool.acquire(capacity);
        try {
            assertSame("close() must release queued datagram buffers",
                    pending, reacquired);
        } finally {
            ByteBufferPool.release(reacquired);
        }
    }

    @Test
    public void testPendingDatagramQueueCapClosesEndpoint() {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setMaxNetOutSize(100);

        UdpEndpoint endpoint = new UdpEndpoint(new NoopHandler());
        endpoint.setFactory(factory);
        endpoint.init();
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 9);

        ByteBuffer first = ByteBufferPool.acquire(64);
        first.put(new byte[60]);
        first.flip();
        assertTrue(endpoint.enqueuePendingDatagram(first, dest));
        assertFalse("endpoint without a bound channel is not open, but not closed yet",
                endpoint.isClosing());

        ByteBuffer second = ByteBufferPool.acquire(64);
        second.put(new byte[50]);
        second.flip();
        assertFalse(endpoint.enqueuePendingDatagram(second, dest));
        assertTrue("overflowing the pending queue must close the endpoint",
                endpoint.isClosing());
    }

    @Test
    public void testDirectBufferPoolReusesThreadLocalStash() {
        ByteBuffer buf = DirectByteBufferPool.acquire(4096);
        int capacity = buf.capacity();
        DirectByteBufferPool.release(buf);
        ByteBuffer reacquired = DirectByteBufferPool.acquire(capacity);
        try {
            assertSame("release/acquire on one thread should reuse the thread-local stash",
                    buf, reacquired);
        } finally {
            DirectByteBufferPool.release(reacquired);
        }
    }
}
