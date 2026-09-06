/*
 * AcceptSelectorLoopRawAcceptorTest.java
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

import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Regression test for {@link AcceptSelectorLoop}'s {@link
 * AcceptSelectorLoop.RawAcceptHandler} accept path.
 *
 * <p>{@code ServerSocketChannel.accept()} always returns a channel in
 * blocking mode, regardless of the listening channel's own mode.
 * {@link AcceptSelectorLoop} previously called {@code
 * sc.configureBlocking(true)} in this path -- a no-op that left the
 * channel blocking -- before handing it to a {@code RawAcceptHandler},
 * even though the handler runs on the single accept thread shared by
 * every listener in the process, where any blocking call would stall
 * acceptance everywhere else. This test proves the channel now arrives
 * non-blocking.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AcceptSelectorLoopRawAcceptorTest {

    @Test
    public void testRawAcceptorReceivesNonBlockingChannel() throws Exception {
        AcceptSelectorLoop loop = new AcceptSelectorLoop(null);
        loop.start();
        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress("localhost", 0));
            int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Boolean> blocking = new AtomicReference<>();

            loop.registerRawAcceptor(ssc,
                    new AcceptSelectorLoop.RawAcceptHandler() {
                        @Override
                        public void accepted(SocketChannel sc) {
                            blocking.set(sc.isBlocking());
                            latch.countDown();
                        }
                    });

            SocketChannel client = SocketChannel.open();
            try {
                client.connect(new InetSocketAddress("localhost", port));

                assertTrue("RawAcceptHandler.accepted() should have "
                                + "been invoked",
                        latch.await(5, TimeUnit.SECONDS));
                assertFalse("A channel handed to a RawAcceptHandler "
                                + "must be non-blocking -- it shares "
                                + "the single process-wide accept "
                                + "thread with every other listener",
                        blocking.get());
            } finally {
                client.close();
            }
        } finally {
            loop.shutdown();
            loop.join();
        }
    }

}
