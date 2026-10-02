/*
 * SocksClientHandlerFlowsIntegrationTest.java
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

package org.bluezoo.gumdrop.socks.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.UdpTransportFactory;

import static org.junit.Assert.*;

/**
 * UDP ASSOCIATE of {@link SocksClientHandler} over a real loopback UDP
 * relay. The handshake flows that need no socket are in
 * {@code SocksClientHandlerFlowsTest} (unit).
 *
 * <p>Integration test: relays UDP through a real loopback datagram socket on a
 * booted runtime.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksClientHandlerFlowsIntegrationTest {

    private static final long GUARD_MS = 10000;

    private static final class UdpListener
            implements SocksClientHandler.UdpAssociateListener {
        final BlockingQueue<InetSocketAddress> associated =
                new LinkedBlockingQueue<InetSocketAddress>();
        final BlockingQueue<String> datagrams =
                new LinkedBlockingQueue<String>();
        final BlockingQueue<Exception> errors =
                new LinkedBlockingQueue<Exception>();

        @Override
        public void associated(InetSocketAddress relayAddress) {
            associated.add(relayAddress);
        }

        @Override
        public void receive(InetSocketAddress source, ByteBuffer payload) {
            byte[] b = new byte[payload.remaining()];
            payload.get(b);
            datagrams.add(source.getPort() + ":"
                    + new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final class LoopEndpoint extends StubEndpoint {
        private final SelectorLoop loop;

        LoopEndpoint(SelectorLoop loop) {
            this.loop = loop;
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }
    }

    private static ByteBuffer bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return ByteBuffer.wrap(b);
    }

    @Test
    public void udpAssociateRelaysDatagramsBothWays() throws Exception {
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create()
                .workerThreads(1).drainTimeoutMs(0));
        try {
            ClientEndpoint keeper = new ClientEndpoint(
                    new TcpTransportFactory(), gumdrop.nextWorkerLoop(),
                    "localhost", 1);
            gumdrop.addClient(keeper);
            LoopEndpoint control = new LoopEndpoint(gumdrop.nextWorkerLoop());
            DatagramSocket relay = new DatagramSocket(0,
                    InetAddress.getLoopbackAddress());
            relay.setSoTimeout((int) GUARD_MS);
            try {
                UdpListener listener = new UdpListener();
                final SocksClientHandler h = new SocksClientHandler(
                        new SocksClientConfig(), new UdpTransportFactory(),
                        listener);
                h.connected(control);
                int rp = relay.getLocalPort();
                h.receive(bytes(5, 0));
                h.receive(bytes(5, 0, 0, 1, 127, 0, 0, 1, rp >> 8, rp));
                InetSocketAddress assoc = listener.associated.poll(GUARD_MS,
                        TimeUnit.MILLISECONDS);
                assertNotNull(assoc);
                assertEquals(rp, assoc.getPort());

                h.sendDatagram(new InetSocketAddress("192.0.2.5", 53),
                        ByteBuffer.wrap(new byte[] {'q'}));
                byte[] buf = new byte[100];
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                relay.receive(in);
                assertEquals('q', buf[10]);

                byte[] fragment = new byte[] {0, 0, 1, 1, 10, 0, 0, 1, 0, 9,
                    'f'};
                byte[] named = new byte[] {0, 0, 0, 3, 1, 'h', 0, 9, 'n'};
                byte[] good = new byte[] {0, 0, 0, 1, 10, 0, 0, 1, 0, 9, 'g'};
                SocketAddressHolder.send(relay, in, fragment);
                SocketAddressHolder.send(relay, in, named);
                SocketAddressHolder.send(relay, in, good);
                String got = listener.datagrams.poll(GUARD_MS,
                        TimeUnit.MILLISECONDS);
                assertEquals("9:g", got);

                h.disconnected();
            } finally {
                relay.close();
            }
        } finally {
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    /** Sends a datagram back to the sender of a received packet. */
    private static final class SocketAddressHolder {
        static void send(DatagramSocket s, DatagramPacket from, byte[] data)
                throws IOException {
            DatagramPacket p = new DatagramPacket(data, data.length,
                    from.getSocketAddress());
            s.send(p);
        }
    }
}
