/*
 * FailingServerSocketChannel.java
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
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

/**
 * A {@link ServerSocketChannel} with no operating system socket behind it
 * whose {@code bind} always fails, so a test can check that code which opened
 * the channel closes it again when binding fails.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FailingServerSocketChannel extends ServerSocketChannel {

    private int closeCount;
    private int bindCount;

    /** Creates the channel. */
    public FailingServerSocketChannel() {
        super(SelectorProvider.provider());
    }

    /**
     * Returns how many times the close hook ran.
     *
     * @return the count
     */
    public int getCloseCount() {
        return closeCount;
    }

    /**
     * Returns how many times bind was attempted.
     *
     * @return the count
     */
    public int getBindCount() {
        return bindCount;
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        bindCount++;
        throw new IOException("bind refused");
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
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
    public ServerSocket socket() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketChannel accept() throws IOException {
        return null;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return null;
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        closeCount++;
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
    }
}
