/*
 * QuicLoopbackHandshakeTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.junit.Test;

/**
 * Full client/server QUIC handshakes and stream exchange over an in-memory
 * datagram pipe.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackHandshakeTest {

    static final class Rec implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final StringBuilder data = new StringBuilder();
        Endpoint endpoint;

        @Override
        public void receive(ByteBuffer d) {
            while (d.hasRemaining()) {
                data.append((char) (d.get() & 0xff));
            }
        }

        @Override
        public void connected(Endpoint e) {
            endpoint = e;
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            events.add("security");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void error(Exception cause) {
            events.add("error");
        }
    }

    @Test
    public void handshakeCompletesAndStreamEchoes() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        final Rec serverStream = new Rec();
        lb.startServer(new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(Endpoint stream) {
                return serverStream;
            }
        });
        final Rec clientStream = new Rec();
        lb.startClient(clientStream, null);
        lb.pump();
        assertNotNull(clientStream.endpoint);
        clientStream.endpoint.send(ByteBuffer.wrap("hello".getBytes("US-ASCII")));
        lb.pump();
        assertEquals("hello", serverStream.data.toString());
        assertNotNull(serverStream.endpoint);
        serverStream.endpoint.send(ByteBuffer.wrap("world".getBytes("US-ASCII")));
        lb.pump();
        assertEquals("world", clientStream.data.toString());
        assertTrue(lb.sentToServer > 0);
    }
}
