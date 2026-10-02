/*
 * AcceptSelectorLoopShutdownIntegrationTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

/**
 * The shutdown contract of {@link AcceptSelectorLoop}: stopping acceptance
 * releases the listening sockets before the call returns while the loop
 * keeps running, and when the loop exits it closes everything it owns on
 * its own thread, including work offered after it has terminated.
 *
 * <p>Integration test: needs a live accept-loop thread and real listening
 * sockets (rebinding a released port is the observable).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoopShutdownIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    private static final AcceptSelectorLoop.RawAcceptHandler NOOP = new AcceptSelectorLoop.RawAcceptHandler() {
        @Override
        public void accepted(SocketChannel sc) {
        }
    };

    private AcceptSelectorLoop loop;

    @After
    public void tearDown() throws Exception {
        if (loop != null) {
            loop.shutdown();
            loop.join();
        }
    }

    private static final class BoundListener extends TcpListener {
        volatile int boundPort;
        final CountDownLatch bound = new CountDownLatch(1);

        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        protected void applyBoundTcpPort(int port) {
            boundPort = port;
            bound.countDown();
        }

        @Override
        public String getDescription() {
            return "accept-shutdown-test";
        }

        @Override
        public int getPort() {
            return 0;
        }
    }

    /** Starts the loop and returns once its selector is open and it has run its first pass. */
    private void startAndAwaitSelector() throws InterruptedException {
        final CountDownLatch ready = new CountDownLatch(1);
        loop.onReady(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
            }
        });
        loop.start();
        await(ready);
    }

    private static ServerSocketChannel rawListener() throws IOException {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return ssc;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("hang guard", latch.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void stopAcceptingReleasesListenerSocketsAndKeepsTheLoopRunning() throws Exception {
        loop = new AcceptSelectorLoop(null);
        BoundListener listener = new BoundListener();
        listener.addresses(InetAddress.getLoopbackAddress());
        loop.registerListener(listener);
        final CountDownLatch ready = new CountDownLatch(1);
        loop.onReady(new Runnable() {
            @Override
            public void run() {
                ready.countDown();
            }
        });
        loop.start();
        await(ready);
        await(listener.bound);

        loop.stopAccepting();

        ServerSocket again = new ServerSocket();
        try {
            again.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.boundPort));
        } finally {
            again.close();
        }
        assertTrue("the loop keeps serving raw acceptors and closes", loop.isRunning());
    }

    @Test
    public void shutdownClosesRawAcceptorsOnTheLoopThread() throws Exception {
        loop = new AcceptSelectorLoop(null);
        startAndAwaitSelector();
        ServerSocketChannel ssc = rawListener();
        loop.registerRawAcceptor(ssc, NOOP);
        ServerSocketChannel other = rawListener();
        loop.closeRawAcceptor(other);
        assertTrue("registered", loop.isRegistered(ssc));

        loop.shutdown();
        loop.join();

        assertFalse("owned acceptors are closed when the loop exits", ssc.isOpen());
    }

    @Test
    public void registrationOfferedAfterTerminationIsClosed() throws Exception {
        loop = new AcceptSelectorLoop(null);
        loop.start();
        loop.shutdown();
        loop.join();

        ServerSocketChannel late = rawListener();
        loop.registerRawAcceptor(late, NOOP);

        assertFalse("a raw acceptor offered to a terminated loop is closed", late.isOpen());
    }

    @Test
    public void closeRequestAfterTerminationStillClosesTheChannel() throws Exception {
        loop = new AcceptSelectorLoop(null);
        loop.start();
        loop.shutdown();
        loop.join();

        ServerSocketChannel ssc = rawListener();
        loop.closeRawAcceptor(ssc);

        assertFalse(ssc.isOpen());
        assertEquals(0, loop.getBindFailures().size());
    }
}
