/*
 * QuicConnectionTestFactory.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.quic;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Builds in-memory {@link QuicConnection} instances (no network, outbound
 * datagrams discarded) so that protocol layers above QUIC can be unit tested.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicConnectionTestFactory {

    private QuicConnectionTestFactory() {
    }

    /**
     * Creates a connection with generous peer limits.
     *
     * @param server whether the connection plays the server role
     * @return the connection
     */
    public static QuicConnection create(boolean server) {
        QuicEngine engine = new QuicEngine(new QuicTransportFactory(), true);
        engine.setSelectorLoop(new InlineSelectorLoop());
        engine.init(new QuicDatagramPath() {
            @Override
            public int send(SocketAddress address, ByteBuffer packet) {
                return packet.remaining();
            }

            @Override
            public SocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        });
        TransportParameters peer = new TransportParameters();
        peer.setInitialMaxStreamsBidi(100);
        peer.setInitialMaxStreamsUni(100);
        peer.setInitialMaxData(10000000);
        peer.setInitialMaxStreamDataBidiLocal(1000000);
        peer.setInitialMaxStreamDataBidiRemote(1000000);
        peer.setInitialMaxStreamDataUni(1000000);
        byte[] cid = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 4433);
        QuicConnection conn = new QuicConnection(engine, server, addr, addr, cid, cid, cid,
                new TransportParameters(), new byte[32], QuicVersion.V1, false);
        conn.seedRememberedTransportParameters(peer);
        return conn;
    }
}
