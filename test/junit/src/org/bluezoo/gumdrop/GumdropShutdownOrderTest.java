/*
 * GumdropShutdownOrderTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

/**
 * The sequence of an orderly and an abort shutdown as seen by an
 * application: servers hear {@code beginShutdown} first (orderly only),
 * connections are then closed while the application is still live, and
 * {@code stop} (application teardown) comes last, after every
 * {@code disconnected()} callback and without touching the network.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropShutdownOrderTest {

    private static final long HANG_GUARD_MS = 60000L;

    private Gumdrop gumdrop;
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());

    @After
    public void tearDown() {
        if (gumdrop != null) {
            gumdrop.shutdownNow();
        }
    }

    private final class AppServer implements Server {
        volatile boolean stopped;
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch bound = new CountDownLatch(1);
        volatile int port;
        final TcpListener listener = new TcpListener() {
            @Override
            protected ProtocolHandler createHandler() {
                return new ProtocolHandler() {
                    @Override
                    public void connected(Endpoint endpoint) {
                        connected.countDown();
                    }

                    @Override
                    public void receive(ByteBuffer data) {
                        data.position(data.limit());
                    }

                    @Override
                    public void securityEstablished(SecurityInfo info) {
                    }

                    @Override
                    public void disconnected() {
                        events.add(stopped ? "disconnected(app torn down)" : "disconnected(app live)");
                    }

                    @Override
                    public void error(Exception cause) {
                    }
                };
            }

            @Override
            protected void applyBoundTcpPort(int p) {
                port = p;
                bound.countDown();
            }

            @Override
            public String getDescription() {
                return "order-test";
            }

            @Override
            public int getPort() {
                return 0;
            }
        };

        AppServer() {
            listener.addresses(InetAddress.getLoopbackAddress());
        }

        @Override
        public List getListeners() {
            return Collections.singletonList(listener);
        }

        @Override
        public void start(Gumdrop g) {
            listener.start(g);
        }

        @Override
        public void beginShutdown() {
            boolean refused = false;
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        (int) HANG_GUARD_MS);
            } catch (IOException e) {
                refused = true;
            } finally {
                try {
                    probe.close();
                } catch (IOException e) {
                    // nothing to do
                }
            }
            events.add(refused ? "beginShutdown(accept stopped)" : "beginShutdown(still accepting)");
        }

        @Override
        public void stop() {
            stopped = true;
            events.add("stop");
        }
    }

    private Socket bootWithConnection(AppServer server) throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        gumdrop.addServer(server);
        assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
        assertTrue(server.bound.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port);
        assertTrue(server.connected.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        return client;
    }

    @Test
    public void orderlyShutdownClosesConnectionsBeforeApplicationTeardown() throws Exception {
        AppServer server = new AppServer();
        Socket client = bootWithConnection(server);
        try {
            gumdrop.shutdown();
            assertEquals(3, events.size());
            assertEquals("beginShutdown(accept stopped)", events.get(0));
            assertEquals("disconnected(app live)", events.get(1));
            assertEquals("stop", events.get(2));
        } finally {
            client.close();
        }
    }

    @Test
    public void abortSkipsBeginShutdownButStillClosesBeforeTeardown() throws Exception {
        AppServer server = new AppServer();
        Socket client = bootWithConnection(server);
        try {
            gumdrop.shutdownNow();
            assertEquals(2, events.size());
            assertEquals("disconnected(app live)", events.get(0));
            assertEquals("stop", events.get(1));
        } finally {
            client.close();
        }
    }
}
