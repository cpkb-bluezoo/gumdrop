/*
 * StubSocketChannel.java
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

package org.bluezoo.gumdrop.testsupport;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

/**
 * A {@link SocketChannel} with no operating system socket behind it, for
 * unit tests that need an endpoint to hold a channel (so that its address,
 * close and shutdown paths run) without any network I/O. Reads and writes
 * move nothing; addresses are whatever the test configures.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class StubSocketChannel extends SocketChannel {

    private final SocketAddress local;
    private final SocketAddress remote;
    private boolean connected = true;
    private boolean socketUnsupported;
    private boolean failOnClose;
    private int closeCount;

    /**
     * Creates a connected stub channel.
     *
     * @param local the local address it reports
     * @param remote the remote address it reports
     */
    public StubSocketChannel(SocketAddress local, SocketAddress remote) {
        super(SelectorProvider.provider());
        this.local = local;
        this.remote = remote;
    }

    /**
     * Makes {@link #socket()} throw {@link UnsupportedOperationException},
     * as a UNIX domain socket channel does.
     *
     * @param unsupported true to throw
     */
    public void setSocketUnsupported(boolean unsupported) {
        this.socketUnsupported = unsupported;
    }

    /**
     * Makes closing the channel throw {@link IOException}.
     *
     * @param fail true to fail on close
     */
    public void setFailOnClose(boolean fail) {
        this.failOnClose = fail;
    }

    /**
     * Sets what {@link #isConnected()} reports.
     *
     * @param connected the connection state
     */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * Returns how many times the close hook ran.
     *
     * @return the count
     */
    public int getCloseCount() {
        return closeCount;
    }

    @Override
    public SocketChannel bind(SocketAddress address) throws IOException {
        return this;
    }

    @Override
    public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        return this;
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return null;
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return Collections.emptySet();
    }

    @Override
    public SocketChannel shutdownInput() throws IOException {
        return this;
    }

    @Override
    public SocketChannel shutdownOutput() throws IOException {
        return this;
    }

    @Override
    public Socket socket() {
        if (socketUnsupported) {
            throw new UnsupportedOperationException();
        }
        final SocketAddress l = local;
        final SocketAddress r = remote;
        return new Socket() {
            @Override
            public SocketAddress getLocalSocketAddress() {
                return l;
            }

            @Override
            public SocketAddress getRemoteSocketAddress() {
                return r;
            }

            @Override
            public void setTcpNoDelay(boolean on) {
                // nothing to tune
            }

            @Override
            public synchronized int getReceiveBufferSize() {
                return 16384;
            }
        };
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isConnectionPending() {
        return false;
    }

    @Override
    public boolean connect(SocketAddress address) throws IOException {
        return true;
    }

    @Override
    public boolean finishConnect() throws IOException {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return remote;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return 0;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return 0L;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int n = src.remaining();
        src.position(src.limit());
        return n;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return 0L;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return local;
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        closeCount++;
        if (failOnClose) {
            throw new IOException("stub close failure");
        }
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        // blocking mode has no effect on a stub
    }

    /**
     * A selection key with no selector, whose interest set the test can
     * observe.
     */
    public static final class Key extends SelectionKey {

        private final SocketChannel channel;
        private int interest;
        private boolean failOnInterest;
        private boolean cancelled;

        /**
         * Creates a valid key for the channel with the given interest set.
         *
         * @param channel the channel
         * @param interest the initial interest set
         */
        public Key(SocketChannel channel, int interest) {
            this.channel = channel;
            this.interest = interest;
        }

        /**
         * Makes the next interest-set change throw as if cancelled.
         *
         * @param fail true to throw
         */
        public void setFailOnInterest(boolean fail) {
            this.failOnInterest = fail;
        }

        @Override
        public SocketChannel channel() {
            return channel;
        }

        @Override
        public boolean isValid() {
            return !cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public int interestOps() {
            return interest;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            if (failOnInterest) {
                throw new java.nio.channels.CancelledKeyException();
            }
            interest = ops;
            return this;
        }

        @Override
        public int readyOps() {
            return 0;
        }
    }
}
