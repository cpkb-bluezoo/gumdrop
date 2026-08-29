/*
 * SelectorLoopCrashIsolationTest.java
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

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for issue #366: an uncaught {@code RuntimeException}
 * while dispatching one connection's I/O must not terminate the shared
 * {@link SelectorLoop} worker thread and strand every other connection
 * on that loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoopCrashIsolationTest {

    @Before
    public void setUp() {
        System.setProperty("gumdrop.workers", "1");
        Gumdrop gumdrop = Gumdrop.getInstance();
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
    }

    @Test(timeout = 10000)
    public void runtimeExceptionOnOneDatagramHandlerDoesNotKillSelectorLoop()
            throws Exception {
        SelectorLoop loop = new SelectorLoop(99);
        loop.start();

        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();

        final CountDownLatch healthyReceived = new CountDownLatch(1);

        ProtocolHandler faulty = new ProtocolHandler() {
            @Override
            public void receive(ByteBuffer data) {
                throw new RuntimeException("deliberate dispatch failure");
            }

            @Override
            public void connected(Endpoint endpoint) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        };

        ProtocolHandler healthy = new ProtocolHandler() {
            @Override
            public void receive(ByteBuffer data) {
                healthyReceived.countDown();
            }

            @Override
            public void connected(Endpoint endpoint) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        };

        UDPEndpoint faultyEndpoint = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0, faulty, loop);
        UDPEndpoint healthyEndpoint = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0, healthy, loop);

        try {
            InetSocketAddress faultyAddress =
                    (InetSocketAddress) faultyEndpoint.getLocalAddress();
            InetSocketAddress healthyAddress =
                    (InetSocketAddress) healthyEndpoint.getLocalAddress();

            DatagramChannel client = DatagramChannel.open();
            try {
                client.send(ByteBuffer.wrap("bad".getBytes()),
                        faultyAddress);
                client.send(ByteBuffer.wrap("ok".getBytes()),
                        healthyAddress);

                assertTrue("the healthy handler must still receive after the "
                        + "faulty handler threw on the same SelectorLoop",
                        healthyReceived.await(5, TimeUnit.SECONDS));
                assertTrue("the SelectorLoop worker thread must keep running",
                        loop.isRunning());
                assertFalse("the faulty endpoint must be closed after the "
                        + "dispatch failure",
                        faultyEndpoint.isOpen());
            } finally {
                client.close();
            }
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            healthyEndpoint.close();
            faultyEndpoint.close();
        }
    }
}
