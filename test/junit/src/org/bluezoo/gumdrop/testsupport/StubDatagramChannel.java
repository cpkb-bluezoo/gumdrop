/*
 * StubDatagramChannel.java
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
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A {@link DatagramChannel} with no operating system socket behind it, for
 * unit tests that need an endpoint to hold a channel and send through it
 * without any network I/O. Sent datagrams are recorded; a send can be made
 * to fail.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class StubDatagramChannel extends DatagramChannel {

    /** One datagram handed to {@link #send} or {@link #write}. */
    public static final class Sent {
        private final byte[] bytes;
        private final SocketAddress destination;

        Sent(byte[] bytes, SocketAddress destination) {
            this.bytes = bytes;
            this.destination = destination;
        }

        /**
         * Returns the datagram bytes.
         *
         * @return the payload
         */
        public byte[] getBytes() {
            return bytes;
        }

        /**
         * Returns the destination, or null for a connected write.
         *
         * @return the destination
         */
        public SocketAddress getDestination() {
            return destination;
        }
    }

    private final SocketAddress local;
    private final List<Sent> sent = new ArrayList<Sent>();
    private boolean failOnSend;
    private boolean failOnClose;

    /**
     * Creates a stub channel.
     *
     * @param local the local address it reports
     */
    public StubDatagramChannel(SocketAddress local) {
        super(SelectorProvider.provider());
        this.local = local;
    }

    /**
     * Makes every send throw {@link IOException}.
     *
     * @param fail true to fail
     */
    public void setFailOnSend(boolean fail) {
        this.failOnSend = fail;
    }

    /**
     * Makes closing the channel throw {@link IOException}.
     *
     * @param fail true to fail
     */
    public void setFailOnClose(boolean fail) {
        this.failOnClose = fail;
    }

    /**
     * Returns the datagrams sent so far.
     *
     * @return the recorded datagrams
     */
    public List<Sent> getSent() {
        return sent;
    }

    @Override
    public DatagramChannel bind(SocketAddress address) throws IOException {
        return this;
    }

    @Override
    public <T> DatagramChannel setOption(SocketOption<T> name, T value) throws IOException {
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
    public DatagramSocket socket() {
        return null;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public DatagramChannel connect(SocketAddress remote) throws IOException {
        return this;
    }

    @Override
    public DatagramChannel disconnect() throws IOException {
        return this;
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return null;
    }

    @Override
    public SocketAddress receive(ByteBuffer dst) throws IOException {
        return null;
    }

    @Override
    public int send(ByteBuffer src, SocketAddress target) throws IOException {
        if (failOnSend) {
            throw new IOException("stub send failure");
        }
        byte[] b = new byte[src.remaining()];
        src.get(b);
        sent.add(new Sent(b, target));
        return b.length;
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
        if (failOnSend) {
            throw new IOException("stub write failure");
        }
        byte[] b = new byte[src.remaining()];
        src.get(b);
        sent.add(new Sent(b, null));
        return b.length;
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
    public MembershipKey join(InetAddress group, NetworkInterface interf) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public MembershipKey join(InetAddress group, NetworkInterface interf, InetAddress source)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        if (failOnClose) {
            throw new IOException("stub close failure");
        }
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        // blocking mode has no effect on a stub
    }
}
