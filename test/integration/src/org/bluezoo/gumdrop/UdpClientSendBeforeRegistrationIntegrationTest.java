/*
 * UdpClientSendBeforeRegistrationIntegrationTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test (real loopback socket, live loop): a datagram queued by a client {@link UdpEndpoint} in the
 * same loop task that connected it (so before the loop has processed the
 * channel registration and the endpoint has a selection key) must still be
 * written once the registration is processed. The only wait is the
 * receiving socket's hang-guard timeout.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpClientSendBeforeRegistrationIntegrationTest {

    private Gumdrop gumdrop;
    private DatagramSocket receiver;

    @Before
    public void setUp() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        receiver.setSoTimeout(20000);
    }

    @After
    public void tearDown() {
        receiver.close();
        gumdrop.shutdownNow();
    }

    private static final class Quiet implements ProtocolHandler {
        @Override
        public void connected(Endpoint endpoint) {
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
    }

    @Test
    public void datagramQueuedBeforeRegistrationIsFlushed() throws Exception {
        final SelectorLoop loop = gumdrop.nextWorkerLoop();
        final byte[] payload = new byte[] {1, 2, 3, 4, 5};
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final int port = receiver.getLocalPort();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UdpTransportFactory factory = new UdpTransportFactory();
                    factory.start();
                    UdpEndpoint endpoint = factory.connect(gumdrop,
                            InetAddress.getLoopbackAddress(), port, new Quiet(), loop);
                    endpoint.send(ByteBuffer.wrap(payload));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
        byte[] buf = new byte[64];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        receiver.receive(packet);
        assertNull(String.valueOf(failure.get()), failure.get());
        byte[] got = new byte[packet.getLength()];
        System.arraycopy(buf, 0, got, 0, got.length);
        assertNotNull(got);
        assertArrayEquals(payload, got);
    }
}
