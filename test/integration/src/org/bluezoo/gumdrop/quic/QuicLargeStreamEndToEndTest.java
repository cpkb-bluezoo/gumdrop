/*
 * QuicLargeStreamEndToEndTest.java
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

package org.bluezoo.gumdrop.quic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.BeforeClass;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TestTlsFiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression test for issue #505: the send path put every queued stream
 * chunk that flow control allowed into one packet, so a large response
 * left as a single datagram far beyond the maximum datagram size (RFC
 * 9000 section 14) and, past the UDP limit, was not delivered at all.
 *
 * <p>The server answers a small request with a response of hundreds of
 * kilobytes, written in one call, over an in-memory datagram path that
 * records the size of every datagram either side sends.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-14">RFC 9000 section 14</a>
 */
public class QuicLargeStreamEndToEndTest {

    private static final String ALPN = "gumdrop-test";
    private static final int RESPONSE_LENGTH = 300000;
    private static final int MAX_DATAGRAM_SIZE = 1200;

    @BeforeClass
    public static void requireTlsFiles() {
        TestTlsFiles.assumeAvailable();
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + (i >>> 8));
        }
        return bytes;
    }

    /** Hands datagrams straight to the peer engine, recording their sizes. */
    private static final class RecordingPath implements QuicDatagramPath {
        private final InetSocketAddress localAddress;
        private QuicEngine peer;
        private final List<byte[]> pending = new ArrayList<byte[]>();
        private final AtomicInteger largest = new AtomicInteger();
        private volatile boolean open = true;
        private volatile boolean recording;

        RecordingPath(InetSocketAddress localAddress) {
            this.localAddress = localAddress;
        }

        synchronized void setPeer(QuicEngine peer) {
            this.peer = peer;
            for (byte[] bytes : pending) {
                peer.receivePathDatagram(bytes, localAddress);
            }
            pending.clear();
        }

        @Override
        public synchronized int send(SocketAddress address, ByteBuffer packet) {
            if (!open) {
                return 0;
            }
            byte[] bytes = new byte[packet.remaining()];
            packet.get(bytes);
            if (recording && bytes.length > largest.get()) {
                largest.set(bytes.length);
            }
            if (peer == null) {
                pending.add(bytes);
            } else {
                peer.receivePathDatagram(bytes, localAddress);
            }
            return bytes.length;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    public void testLargeResponseIsSplitIntoDatagramSizedPackets() throws Exception {
        SelectorLoop loop = new SelectorLoop(0);
        loop.start();
        QuicEngine serverEngine = null;
        QuicEngine clientEngine = null;
        try {
            QuicTransportFactory serverFactory = new QuicTransportFactory();
            serverFactory.setApplicationProtocols(ALPN);
            serverFactory.setCertFile(TestTlsFiles.certFile());
            serverFactory.setKeyFile(TestTlsFiles.keyFile());
            serverFactory.start();

            QuicTransportFactory clientFactory = new QuicTransportFactory();
            clientFactory.setApplicationProtocols(ALPN);
            clientFactory.setVerifyPeer(false);
            clientFactory.setVerifyHostname(false);
            clientFactory.start();

            final RecordingPath serverPath = new RecordingPath(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 4433));
            final RecordingPath clientPath = new RecordingPath(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final byte[] response = pattern(RESPONSE_LENGTH);

            final AtomicReference<Endpoint> serverStream = new AtomicReference<Endpoint>();
            serverEngine = serverFactory.createServerEngine(serverPath, new StreamAcceptHandler() {
                @Override
                public ProtocolHandler acceptStream(Endpoint stream) {
                    return new ProtocolHandler() {
                        @Override
                        public void connected(Endpoint endpoint) {
                            serverStream.set(endpoint);
                        }

                        @Override
                        public void receive(ByteBuffer data) {
                        }

                        @Override
                        public void securityEstablished(SecurityInfo info) {
                        }

                        @Override
                        public void readFinished() {
                            Endpoint endpoint = serverStream.get();
                            // Handshake flights are not stream data; only
                            // what the response itself causes is measured.
                            serverPath.recording = true;
                            clientPath.recording = true;
                            endpoint.send(ByteBuffer.wrap(response));
                            endpoint.close();
                        }

                        @Override
                        public void disconnected() {
                        }

                        @Override
                        public void error(Exception cause) {
                            fail("Server stream error: " + cause);
                        }
                    };
                }
            }, loop);
            clientPath.setPeer(serverEngine);

            final CountDownLatch clientFin = new CountDownLatch(1);
            final byte[] received = new byte[RESPONSE_LENGTH];
            final AtomicInteger receivedLength = new AtomicInteger();
            clientEngine = clientFactory.connect(clientPath, serverPath.localAddress, new ProtocolHandler() {
                @Override
                public void connected(Endpoint endpoint) {
                    endpoint.send(ByteBuffer.wrap(new byte[] { 'g', 'e', 't' }));
                    endpoint.close();
                }

                @Override
                public void receive(ByteBuffer data) {
                    int n = data.remaining();
                    int at = receivedLength.get();
                    if (at + n <= received.length) {
                        data.get(received, at, n);
                    }
                    receivedLength.addAndGet(n);
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                }

                @Override
                public void readFinished() {
                    clientFin.countDown();
                }

                @Override
                public void disconnected() {
                }

                @Override
                public void error(Exception cause) {
                    fail("Client stream error: " + cause);
                }
            }, loop, TestTlsFiles.SERVER_NAME);
            serverPath.setPeer(clientEngine);

            assertTrue("the whole response must arrive within 30s (got " + receivedLength.get() + " bytes)",
                    clientFin.await(30, TimeUnit.SECONDS));
            assertEquals(RESPONSE_LENGTH, receivedLength.get());
            org.junit.Assert.assertArrayEquals("response bytes must arrive intact, in order", response, received);
            assertTrue("no datagram may exceed " + MAX_DATAGRAM_SIZE + " bytes (RFC 9000 section 14): largest "
                    + serverPath.largest.get(), serverPath.largest.get() <= MAX_DATAGRAM_SIZE);
            assertTrue("client datagrams too: " + clientPath.largest.get(),
                    clientPath.largest.get() <= MAX_DATAGRAM_SIZE);
        } finally {
            loop.shutdown();
            loop.awaitQuiesce(2000);
            if (clientEngine != null) {
                clientEngine.close();
            }
            if (serverEngine != null) {
                serverEngine.close();
            }
        }
    }
}
