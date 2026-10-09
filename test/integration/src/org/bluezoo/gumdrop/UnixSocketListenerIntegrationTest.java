/*
 * UnixSocketListenerIntegrationTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * A {@link TcpListener} bound to a UNIX domain socket path, as a booted
 * {@link Gumdrop} runs it: the accept loop binds the path (replacing a stale
 * socket file left by an earlier run), accepts a real UNIX connection that is
 * echoed on a worker loop, and removes the socket file when the listener is
 * stopped. Every wait is a latch; the server side runs on its own loops.
 *
 * <p>Integration test: needs a live accept loop, worker loops and a real
 * UNIX domain socket file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UnixSocketListenerIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    @Rule
    public Timeout hangGuard = Timeout.seconds(90);

    private Gumdrop gumdrop;
    private Path socketPath;

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdownNow();
        }
        if (socketPath != null) {
            Files.deleteIfExists(socketPath);
        }
    }

    private static final class Echo implements ProtocolHandler {
        volatile Endpoint endpoint;
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void receive(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            endpoint.send(ByteBuffer.wrap(copy));
        }

        @Override
        public void connected(Endpoint ep) {
            endpoint = ep;
            connected.countDown();
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
            disconnected.countDown();
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static final class UnixListener extends TcpListener {
        final Echo handler = new Echo();

        @Override
        protected ProtocolHandler createHandler() {
            return handler;
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public String getDescription() {
            return "unix-live-test";
        }
    }

    private UnixListener start(boolean staleFile) throws Exception {
        socketPath = Files.createTempFile("gumdrop-uds-live", ".sock");
        if (!staleFile) {
            Files.delete(socketPath);
        }
        UnixListener listener = new UnixListener();
        listener.path(socketPath);
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        gumdrop.addListener(listener);
        assertTrue(gumdrop.awaitStartupComplete(HANG_GUARD_MS));
        return listener;
    }

    private static String echoOnce(Path path, String text) throws Exception {
        try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(path))) {
            byte[] out = text.getBytes(StandardCharsets.UTF_8);
            client.write(ByteBuffer.wrap(out));
            ByteBuffer in = ByteBuffer.allocate(out.length);
            while (in.hasRemaining()) {
                if (client.read(in) < 0) {
                    break;
                }
            }
            in.flip();
            return StandardCharsets.UTF_8.decode(in).toString();
        }
    }

    @Test
    public void unixConnectionIsAcceptedAndEchoed() throws Exception {
        UnixListener listener = start(false);
        assertEquals("ping", echoOnce(socketPath, "ping"));
        assertTrue(listener.handler.disconnected.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void staleSocketFileIsReplacedOnBind() throws Exception {
        UnixListener listener = start(true);
        assertEquals("again", echoOnce(socketPath, "again"));
        assertTrue(listener.handler.connected.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void stoppingTheListenerRemovesTheSocketFile() throws Exception {
        start(false);
        assertTrue(Files.exists(socketPath));
        gumdrop.shutdown();
        gumdrop.join();
        assertFalse(Files.exists(socketPath));
    }
}
