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

import org.bluezoo.gumdrop.util.DirectByteBufferPool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #193: {@link UDPEndpoint#netIn} previously
 * used a plain heap {@code ByteBuffer.allocate(...)} rather than a
 * pooled direct buffer like {@link TCPEndpoint}'s read/write path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UDPEndpointTest {

    private Gumdrop gumdrop;

    /** No-op handler; these tests only exercise endpoint setup/teardown. */
    private static final class NoopHandler implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void disconnected() { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void error(Exception cause) { }
    }

    @Before
    public void setUp() {
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
    }

    @After
    public void tearDown() {
        // Deliberately not shutting down the shared Gumdrop singleton --
        // other test classes in the same JVM may depend on it staying up,
        // matching AsyncDiskOffloadBoundaryTest's convention.
    }

    @Test
    public void testNetInIsAPooledDirectBuffer() throws Exception {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();

        UDPEndpoint endpoint = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        try {
            ByteBuffer netIn = getNetIn(endpoint);
            assertNotNull(netIn);
            assertTrue("netIn must be a direct buffer, not a heap allocation",
                    netIn.isDirect());
        } finally {
            endpoint.close();
        }
    }

    @Test
    public void testNetInIsReturnedToThePoolOnClose() throws Exception {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();

        UDPEndpoint endpoint = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0, new NoopHandler());
        ByteBuffer netIn = getNetIn(endpoint);
        int capacity = netIn.capacity();

        endpoint.close();
        assertNull("netIn must be cleared once released", getNetIn(endpoint));

        // If close() actually released the buffer back to the pool, the
        // very next same-size acquire on this thread must hand back the
        // exact same instance rather than allocating a fresh one.
        ByteBuffer reacquired = DirectByteBufferPool.acquire(capacity);
        try {
            assertSame("closing the endpoint must release netIn back to the pool",
                    netIn, reacquired);
        } finally {
            DirectByteBufferPool.release(reacquired);
        }
    }

    private static ByteBuffer getNetIn(UDPEndpoint endpoint) throws Exception {
        Field field = UDPEndpoint.class.getDeclaredField("netIn");
        field.setAccessible(true);
        return (ByteBuffer) field.get(endpoint);
    }
}
