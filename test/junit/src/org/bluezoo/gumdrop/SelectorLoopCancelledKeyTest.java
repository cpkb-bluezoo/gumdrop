/*
 * SelectorLoopCancelledKeyTest.java
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectionKey;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.Test;

/**
 * Regression tests for the window between {@code SelectionKey.isValid()} and
 * {@code interestOps(int)}: another thread can cancel the key in that window,
 * and the interest-ops change must then be treated as "endpoint closed"
 * rather than throwing {@link CancelledKeyException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoopCancelledKeyTest {

    /** Key that reports valid but is cancelled by the time interestOps is applied. */
    private static final class RacingKey extends AbstractSelectionKey {
        @Override
        public SelectableChannel channel() {
            return null;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public int interestOps() {
            return 0;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            throw new CancelledKeyException();
        }

        @Override
        public int readyOps() {
            return 0;
        }
    }

    private static final class NoopHandler implements ProtocolHandler {
        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static TcpEndpoint endpointWithRacingKey() throws IOException {
        TcpEndpoint ep = new TcpEndpoint(new NoopHandler());
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.setSelectionKey(new RacingKey());
        return ep;
    }

    @Test
    public void closeWithKeyCancelledMidRequestDoesNotThrow() throws IOException {
        TcpEndpoint ep = endpointWithRacingKey();
        ep.close();
        assertTrue(ep.isClosing());
        assertFalse(ep.isOpen());
        // second close is a no-op
        ep.close();
        assertTrue(ep.isClosing());
    }

    @Test
    public void closeWhenOutboundIdleWithKeyCancelledDoesNotThrow() throws IOException {
        TcpEndpoint ep = endpointWithRacingKey();
        ep.closeWhenOutboundIdle();
    }

    @Test
    public void requestWriteWithKeyCancelledDoesNotThrow() throws IOException {
        TcpEndpoint ep = endpointWithRacingKey();
        SelectorLoop loop = new InlineSelectorLoop();
        loop.requestWrite(ep);
    }

    @Test
    public void requestAndCancelReadWithKeyCancelledDoNotThrow() throws IOException {
        TcpEndpoint ep = endpointWithRacingKey();
        SelectorLoop loop = new InlineSelectorLoop();
        loop.requestRead(ep);
        loop.cancelRead(ep);
    }
}
