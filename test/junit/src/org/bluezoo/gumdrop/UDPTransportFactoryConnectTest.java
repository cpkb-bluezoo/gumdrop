/*
 * UDPTransportFactoryConnectTest.java
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

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Regression test for {@link UDPTransportFactory#connect}: a datagram
 * sent synchronously from within the client {@link ProtocolHandler}'s
 * {@code connected()} callback must actually reach the peer.
 *
 * <p>{@link SelectorLoop#registerDatagram} defers the real {@code
 * channel.register(...)} (and the {@link Endpoint}'s {@code
 * SelectionKey} assignment) to the loop's own thread, via {@code
 * pendingRegistrations}. {@code connect()} used to call {@code
 * handler.connected(endpoint)} synchronously, on the calling thread,
 * immediately after queuing that registration -- so a handler sending
 * data straight away raced the loop thread actually processing the
 * registration. {@link SelectorLoop#requestDatagramWrite} silently
 * no-ops when the endpoint's {@code SelectionKey} isn't set yet
 * ({@code handler.getSelectionKey()} returns null), so the datagram
 * was queued but never actually flushed -- lost with no error.
 * {@link TCPTransportFactory#connect} already avoids the equivalent
 * race by dispatching through {@code SelectorLoop.invokeLater}; this
 * verifies the UDP factory does the same.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UDPTransportFactoryConnectTest {

    @After
    public void tearDown() {
        // Deliberately not shutting down the shared Gumdrop singleton --
        // other test classes in the same JVM may depend on it staying up.
    }

    /**
     * Mirrors the exact call shape that exposed this race in {@code
     * DNSService.proxyToUpstream}: {@code Gumdrop.start()} (a
     * brand-new worker thread that has not yet reached its first
     * {@code select()}) immediately followed, on the very same call
     * stack with no intervening yield point, by {@code connect()} and
     * a synchronous send from {@code connected()}. Deliberately not
     * using a JUnit {@code @Before} for the {@code Gumdrop} bootstrap:
     * the reflection/statement-wrapping overhead between {@code
     * @Before} and the {@code @Test} body was enough scheduling gap
     * for the new thread to start in time, masking the race.
     */
    @Test
    public void testDatagramSentSynchronouslyOnConnectIsDelivered() throws Exception {
        System.setProperty("gumdrop.workers", "1");
        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.start();

        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<byte[]> receivedData = new AtomicReference<>();

        UDPEndpoint server = factory.createServerEndpoint(
                InetAddress.getLoopbackAddress(), 0,
                new ProtocolHandler() {
                    @Override
                    public void connected(Endpoint endpoint) {
                    }

                    @Override
                    public void receive(ByteBuffer data) {
                        byte[] copy = new byte[data.remaining()];
                        data.get(copy);
                        receivedData.set(copy);
                        received.countDown();
                    }

                    @Override
                    public void disconnected() {
                    }

                    @Override
                    public void securityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void error(Exception cause) {
                    }
                });
        try {
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            final byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);

            UDPEndpoint client = factory.connect(
                    InetAddress.getLoopbackAddress(), port,
                    new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            // The regression: sending immediately and
                            // synchronously here used to race the
                            // SelectorLoop's deferred registration and
                            // silently drop the datagram.
                            endpoint.send(ByteBuffer.wrap(payload));
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                        }

                        @Override
                        public void disconnected() {
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                        }

                        @Override
                        public void error(Exception cause) {
                        }
                    });
            try {
                assertTrue("Server should have received the datagram sent "
                                + "synchronously from connected()",
                        received.await(5, TimeUnit.SECONDS));
                assertArrayEquals(payload, receivedData.get());
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }
}
