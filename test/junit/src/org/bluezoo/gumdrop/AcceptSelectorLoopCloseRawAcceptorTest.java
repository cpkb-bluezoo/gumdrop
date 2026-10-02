/*
 * AcceptSelectorLoopCloseRawAcceptorTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for {@link AcceptSelectorLoop#closeRawAcceptor}: closing a
 * channel still registered with the selector only cancels its key, and the
 * JDK defers releasing the socket until the key is deregistered. The method
 * must therefore not return until the loop's selector holds no key for the
 * channel. Checked structurally, with no probe connects or port reuse.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoopCloseRawAcceptorTest {

    private static final long TIMEOUT_SECONDS = 15L;

    private static final AcceptSelectorLoop.RawAcceptHandler NOOP = new AcceptSelectorLoop.RawAcceptHandler() {
        @Override
        public void accepted(SocketChannel sc) {
        }
    };

    private static ServerSocketChannel listener() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return ssc;
    }

    /** Registration is queued; selectNow-free way to know it landed is a connect-free handshake via the loop thread. */
    private static void awaitRegistered(AcceptSelectorLoop loop, ServerSocketChannel ssc) throws Exception {
        // closeRawAcceptor of an unrelated channel is serviced after pending registrations on the loop thread
        ServerSocketChannel other = listener();
        loop.closeRawAcceptor(other);
        assertTrue("registration must have been processed", loop.isRegistered(ssc));
    }

    @Test
    public void closeFromAnotherThreadDeregistersTheKeyBeforeReturning() throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        loop.start();
        try {
            ServerSocketChannel ssc = listener();
            loop.registerRawAcceptor(ssc, NOOP);
            awaitRegistered(loop, ssc);
            loop.closeRawAcceptor(ssc);
            assertFalse("channel must be closed", ssc.isOpen());
            assertFalse("selector must hold no key for the closed channel", loop.isRegistered(ssc));
        } finally {
            loop.shutdown();
            loop.join();
        }
    }

    @Test
    public void closeWhenTheLoopIsNotRunningClosesTheChannel() throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        ServerSocketChannel ssc = listener();
        loop.closeRawAcceptor(ssc);
        assertFalse(ssc.isOpen());
        assertFalse(loop.isRegistered(ssc));
    }

    @Test
    public void closeFromTheAcceptThreadClosesInPlace() throws Exception {
        final AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        loop.start();
        try {
            final ServerSocketChannel ssc = listener();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean closed = new AtomicBoolean();
            loop.registerRawAcceptor(ssc, new AcceptSelectorLoop.RawAcceptHandler() {
                @Override
                public void accepted(SocketChannel sc) throws java.io.IOException {
                    loop.closeRawAcceptor(ssc);
                    closed.set(!ssc.isOpen());
                    done.countDown();
                    sc.close();
                }
            });
            SocketChannel client = SocketChannel.open();
            try {
                client.connect(ssc.getLocalAddress());
                assertTrue("handler must run", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertTrue("closed in place on the accept thread", closed.get());
            } finally {
                client.close();
            }
        } finally {
            loop.shutdown();
            loop.join();
        }
    }
}
