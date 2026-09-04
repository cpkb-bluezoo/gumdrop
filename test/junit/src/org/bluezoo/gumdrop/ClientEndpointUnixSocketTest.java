/*
 * ClientEndpointUnixSocketTest.java
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

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real-socket regression test for issue #404: {@link ClientEndpoint} and
 * {@link TCPTransportFactory} previously had no way to address a UNIX
 * domain socket at all -- only the {@code String host, int port} /
 * {@code InetAddress host, int port} constructors existed, mirroring only
 * half of what {@link TCPListener#setPath} already supported on the
 * server side.
 *
 * <p>The server side of each test here is a plain JDK {@link
 * ServerSocketChannel} bound with {@link StandardProtocolFamily#UNIX},
 * deliberately independent of gumdrop's own {@code AcceptSelectorLoop} --
 * this test is about the client's new connect path, not the (separately
 * already-working) server accept path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEndpoint
 * @see TCPTransportFactory
 */
public class ClientEndpointUnixSocketTest {

    private Path socketPath;
    private SelectorLoop selectorLoop;

    @Before
    public void setUp() throws Exception {
        Path tmp = Files.createTempFile("gumdrop-uds-test", ".sock");
        Files.delete(tmp); // bind() requires the path not already exist
        socketPath = tmp;
        selectorLoop = new SelectorLoop(0);
        selectorLoop.start();
    }

    @After
    public void tearDown() throws Exception {
        selectorLoop.shutdown();
        Files.deleteIfExists(socketPath);
    }

    /**
     * A minimal blocking UNIX domain socket echo server on a background
     * thread: accepts one connection, echoes every byte received back to
     * the client, until the client closes its side.
     */
    private Thread startEchoServer(final ServerSocketChannel serverChannel) {
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (SocketChannel accepted = serverChannel.accept()) {
                    ByteBuffer buf = ByteBuffer.allocate(4096);
                    int n;
                    while ((n = accepted.read(buf)) >= 0) {
                        if (n > 0) {
                            buf.flip();
                            accepted.write(buf);
                            buf.clear();
                        }
                    }
                } catch (java.io.IOException e) {
                    // Server channel closed by tearDown/test completion -- expected.
                }
            }
        }, "uds-echo-server");
        serverThread.setDaemon(true);
        serverThread.start();
        return serverThread;
    }

    /**
     * {@link TCPListener#setPath} has advertised UNIX domain socket
     * support on the server side since it was added, but nothing ever
     * exercised a real accepted UNIX domain socket connection through
     * {@link TCPEndpoint#init} end to end -- it shared the exact same
     * unconditional {@code channel.socket()} call the client path above
     * hit, so a real server accept over a UNIX domain socket was equally
     * broken. This drives {@link TCPTransportFactory#createServerEndpoint}
     * (what {@link TCPListener#newEndpoint} calls for every accepted
     * connection) directly against a real accepted UNIX domain socket
     * channel, independent of the full {@code AcceptSelectorLoop}/{@code
     * Listener} bootstrap, to confirm the fix covers this path too.
     */
    @Test
    public void testServerCreateEndpointOverUnixSocket() throws Exception {
        try (ServerSocketChannel serverChannel =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));

            final byte[] payload = "server-side-uds".getBytes(StandardCharsets.US_ASCII);
            final CountDownLatch serverReceivedLatch = new CountDownLatch(1);
            final byte[][] serverReceived = new byte[1][];
            final Exception[] serverError = new Exception[1];

            Thread serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (SocketChannel accepted = serverChannel.accept()) {
                        accepted.configureBlocking(false);
                        TCPTransportFactory serverFactory = new TCPTransportFactory();
                        serverFactory.start();
                        org.bluezoo.gumdrop.TCPEndpoint endpoint =
                                serverFactory.createServerEndpoint(accepted, new ProtocolHandler() {
                                    @Override
                                    public void connected(Endpoint e) {
                                    }

                                    @Override
                                    public void receive(ByteBuffer data) {
                                        byte[] bytes = new byte[data.remaining()];
                                        data.get(bytes);
                                        serverReceived[0] = bytes;
                                        serverReceivedLatch.countDown();
                                    }

                                    @Override
                                    public void disconnected() {
                                    }

                                    @Override
                                    public void securityEstablished(SecurityInfo info) {
                                    }

                                    @Override
                                    public void error(Exception cause) {
                                        serverError[0] = cause;
                                        serverReceivedLatch.countDown();
                                    }
                                });
                        endpoint.setSelectorLoop(selectorLoop);
                        selectorLoop.register(accepted, endpoint);
                        // Block this thread only to keep the accepted
                        // channel (and its try-with-resources) alive while
                        // the SelectorLoop thread reads from it.
                        serverReceivedLatch.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        serverError[0] = e;
                        serverReceivedLatch.countDown();
                    }
                }
            }, "uds-raw-server-endpoint");
            serverThread.setDaemon(true);
            serverThread.start();

            // A plain blocking client connect -- deliberately independent
            // of gumdrop's own client code, so this test's proof that the
            // *server* accept path now initialises correctly does not
            // depend on the client fix also being correct.
            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(socketPath));
                client.write(ByteBuffer.wrap(payload));

                assertTrue("server should receive the payload within 5s",
                        serverReceivedLatch.await(5, TimeUnit.SECONDS));
                if (serverError[0] != null) {
                    fail("unexpected server-side error: " + serverError[0]);
                }
                assertArrayEquals(payload, serverReceived[0]);
            }
            serverThread.join(5000);
        }
    }

    @Test
    public void testConnectWithExplicitSelectorLoopEchoesData() throws Exception {
        try (ServerSocketChannel serverChannel =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            startEchoServer(serverChannel);

            TCPTransportFactory factory = new TCPTransportFactory();
            factory.start();

            final byte[] payload = "hello-unix-socket".getBytes(StandardCharsets.US_ASCII);
            final CountDownLatch echoLatch = new CountDownLatch(1);
            final byte[][] received = new byte[1][];
            final Exception[] error = new Exception[1];

            ClientEndpoint client = new ClientEndpoint(
                    factory, selectorLoop, socketPath.toString());
            assertNotNull(client.getPath());
            assertTrue(client.getPath().equals(socketPath.toString()));

            client.connect(new ProtocolHandler() {
                @Override
                public void connected(Endpoint endpoint) {
                    endpoint.send(ByteBuffer.wrap(payload));
                }

                @Override
                public void receive(ByteBuffer data) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    received[0] = bytes;
                    echoLatch.countDown();
                }

                @Override
                public void disconnected() {
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                }

                @Override
                public void error(Exception cause) {
                    error[0] = cause;
                    echoLatch.countDown();
                }
            });

            assertTrue("echo should arrive within 5s", echoLatch.await(5, TimeUnit.SECONDS));
            if (error[0] != null) {
                fail("unexpected error: " + error[0]);
            }
            assertArrayEquals(payload, received[0]);

            client.close();
        }
    }

    @Test
    public void testConnectStandaloneWithoutExplicitSelectorLoop() throws Exception {
        try (ServerSocketChannel serverChannel =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            startEchoServer(serverChannel);

            TCPTransportFactory factory = new TCPTransportFactory();
            factory.start();

            final byte[] payload = "standalone".getBytes(StandardCharsets.US_ASCII);
            final CountDownLatch echoLatch = new CountDownLatch(1);
            final byte[][] received = new byte[1][];
            final Exception[] error = new Exception[1];

            // No explicit SelectorLoop -- obtained automatically from
            // Gumdrop, exactly as the String/InetAddress-host constructors
            // already do.
            ClientEndpoint client = new ClientEndpoint(factory, socketPath.toString());

            client.connect(new ProtocolHandler() {
                @Override
                public void connected(Endpoint endpoint) {
                    endpoint.send(ByteBuffer.wrap(payload));
                }

                @Override
                public void receive(ByteBuffer data) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    received[0] = bytes;
                    echoLatch.countDown();
                }

                @Override
                public void disconnected() {
                }

                @Override
                public void securityEstablished(SecurityInfo info) {
                }

                @Override
                public void error(Exception cause) {
                    error[0] = cause;
                    echoLatch.countDown();
                }
            });

            assertTrue("echo should arrive within 5s", echoLatch.await(5, TimeUnit.SECONDS));
            if (error[0] != null) {
                fail("unexpected error: " + error[0]);
            }
            assertArrayEquals(payload, received[0]);

            client.close();
        }
    }

    /**
     * Unlike a TCP connect (whose failure -- e.g. ECONNREFUSED -- is
     * typically asynchronous, reported later via {@code finishConnect()}
     * off an {@code OP_CONNECT} event), a UNIX domain socket connect to a
     * path with no listener (ENOENT) fails synchronously inside {@code
     * SocketChannel.connect()} itself. {@link ClientEndpoint#connect}
     * already documents this case ({@code @throws IOException if the
     * connection cannot be initiated}) and its TCP path behaves the same
     * way for a synchronous failure -- this is not a UDS-specific gap.
     */
    @Test
    public void testConnectToMissingSocketThrowsIOException() throws Exception {
        Path missing = socketPath; // never bound by any server in this test
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.start();

        ClientEndpoint client = new ClientEndpoint(
                factory, selectorLoop, missing.toString());
        try {
            client.connect(new ProtocolHandler() {
                @Override
                public void connected(Endpoint endpoint) {
                    fail("should not connect to a socket with no listener");
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
                    fail("connect() should throw synchronously for ENOENT, not call error()");
                }
            });
            fail("connect() should have thrown IOException for a socket with no listener");
        } catch (java.io.IOException expected) {
            // expected
        }
    }
}
