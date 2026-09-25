/*
 * QuicConnectionTeardownTest.java
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

package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.junit.Test;

/**
 * Closing a connection notifies every open stream's handler. Handlers may
 * react by touching the connection (closing their own or a sibling stream,
 * which retires it), so teardown must not iterate the live stream table.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicConnectionTeardownTest {

    private static final class RecordingHandler implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        Runnable onDisconnected;

        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void disconnected() {
            events.add("disconnected");
            if (onDisconnected != null) {
                onDisconnected.run();
            }
        }
        @Override public void error(Exception cause) {
            events.add("error");
        }
    }

    @Test
    public void handlerRetiringSiblingStreamDuringTeardownIsTolerated() throws Exception {
        QuicEngine engine = new QuicEngine(new QuicTransportFactory(), true);
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
        peer.setInitialMaxData(1000000);
        byte[] cid = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 4433);
        QuicConnection conn = new QuicConnection(engine, false, addr, addr, cid, cid, cid,
                new TransportParameters(), new byte[32],
                org.bluezoo.gumdrop.quic.packet.QuicVersion.V1);
        conn.seedRememberedTransportParameters(peer);

        int streamCount = 6;
        final RecordingHandler[] handlers = new RecordingHandler[streamCount];
        final Endpoint[] endpoints = new Endpoint[streamCount];
        for (int i = 0; i < streamCount; i++) {
            handlers[i] = new RecordingHandler();
            endpoints[i] = conn.openStream(handlers[i]);
        }
        // Every stream is half-closed by the peer, so closing our side
        // retires it from the connection's stream table.
        for (int i = 0; i < streamCount; i++) {
            ((QuicStreamEndpoint) endpoints[i]).markPeerFinished();
        }
        // The first stream torn down closes all the others, retiring them.
        handlers[0].onDisconnected = new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i < endpoints.length; i++) {
                    endpoints[i].close();
                }
            }
        };
        conn.close();
        for (int i = 0; i < streamCount; i++) {
            assertEquals("stream " + i + " " + handlers[i].events, "disconnected", handlers[i].events.get(0));
        }
    }
}
