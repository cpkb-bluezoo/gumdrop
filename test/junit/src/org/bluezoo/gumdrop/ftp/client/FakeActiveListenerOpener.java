/*
 * FakeActiveListenerOpener.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.bluezoo.gumdrop.AcceptSelectorLoop;

/**
 * Hand-written mock for the active-mode (PORT/EPRT) listener seam: binds
 * nothing and registers nothing, hands out deterministic ports, records
 * each listener's close, and lets a test deliver the server's incoming
 * data connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FakeActiveListenerOpener
        implements FtpClientDataConnectionCoordinator.ActiveListenerOpener {

    /** A listener that records its close. */
    static final class Listener implements FtpClientDataConnectionCoordinator.ActiveListener {
        private final InetSocketAddress address;
        private final AcceptSelectorLoop.RawAcceptHandler onAccept;
        boolean closed;

        Listener(InetSocketAddress address, AcceptSelectorLoop.RawAcceptHandler onAccept) {
            this.address = address;
            this.onAccept = onAccept;
        }

        @Override
        public InetSocketAddress address() {
            return address;
        }

        @Override
        public void close() {
            closed = true;
        }

        /** Delivers an incoming data connection as the accept loop would. */
        void accept(SocketChannel channel) throws IOException {
            onAccept.accepted(channel);
        }
    }

    private int opened;
    private IOException failure;

    /** Makes every open fail with {@code e}. */
    void failWith(IOException e) {
        this.failure = e;
    }

    Listener last;

    @Override
    public FtpClientDataConnectionCoordinator.ActiveListener open(InetAddress local,
            AcceptSelectorLoop.RawAcceptHandler onAccept) throws IOException {
        if (failure != null) {
            throw failure;
        }
        opened++;
        last = new Listener(new InetSocketAddress(local, 40000 + opened), onAccept);
        return last;
    }

    int openCount() {
        return opened;
    }
}
