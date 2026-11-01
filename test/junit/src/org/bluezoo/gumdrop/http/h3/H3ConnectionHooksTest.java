/*
 * H3ConnectionHooksTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;

import static org.junit.Assert.*;

/**
 * The hooks the HTTP/3 handlers register on their QUIC connection: the
 * unidirectional stream acceptor, the datagram handler and, for the client,
 * the refusal of server-initiated bidirectional streams.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ConnectionHooksTest {

    private static Object field(QuicConnection conn, String name) throws Exception {
        Field f = QuicConnection.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(conn);
    }

    private static void exerciseDatagramHandler(QuicConnection conn) throws Exception {
        ProtocolHandler datagrams = (ProtocolHandler) field(conn, "datagramHandler");
        assertNotNull(datagrams);
        datagrams.connected(null);
        datagrams.receive(ByteBuffer.allocate(1));
        datagrams.securityEstablished(null);
        datagrams.error(new java.io.IOException("x"));
        datagrams.disconnected();
        // a datagram before the peer enabled them is a protocol error, and so is a malformed one
        datagrams.datagramReceived(ByteBuffer.allocate(0));
    }

    @Test
    public void clientRegistersControlStreamAcceptorAndDatagramHandler() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        new Http3ClientHandler(conn);
        StreamAcceptHandler unidirectional = (StreamAcceptHandler) field(conn, "unidirectionalStreamAcceptHandler");
        assertNotNull(unidirectional.acceptStream(null));
        exerciseDatagramHandler(conn);
    }

    @Test
    public void clientRefusesServerInitiatedBidirectionalStreams() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        new Http3ClientHandler(conn);
        StreamAcceptHandler bidirectional = (StreamAcceptHandler) field(conn, "streamAcceptHandler");
        assertNull(bidirectional.acceptStream(null));
    }

    @Test
    public void serverRegistersControlStreamAcceptorAndDatagramHandler() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        HttpStreamHandler streams = new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse stream) {
                return null;
            }
        };
        new Http3ServerHandler(conn, streams, null, null, null, false, false);
        StreamAcceptHandler unidirectional = (StreamAcceptHandler) field(conn, "unidirectionalStreamAcceptHandler");
        assertNotNull(unidirectional.acceptStream(null));
        exerciseDatagramHandler(conn);
    }
}
