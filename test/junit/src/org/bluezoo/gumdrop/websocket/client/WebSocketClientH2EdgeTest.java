/*
 * WebSocketClientH2EdgeTest.java
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


package org.bluezoo.gumdrop.websocket.client;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.After;
import org.junit.Test;

import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Extended CONNECT (RFC 8441) request construction in {@link WebSocketClient}
 * when no sub-protocol and no extension is offered: the optional request
 * headers must be left out.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientH2EdgeTest {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    /** Client whose endpoint is an in-memory recorder driven by a synchronous loop. */
    private static final class InMemoryClient extends WebSocketClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        WebSocketClientProtocolHandler handler;

        InMemoryClient(java.net.InetAddress address) {
            super(address, 80);
            setDnsHttpsRecordEnabled(true);
        }

        InMemoryClient() {
            super("ws.example", 80);
            setDnsHttpsRecordEnabled(false);
            setH2Enabled(true);
            setH2WithPriorKnowledge(true);
        }

        @Override
        void connectEndpointForTesting(WebSocketClientProtocolHandler ph) throws IOException {
            handler = ph;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }

        void feed(byte[] bytes) {
            handler.receive(ByteBuffer.wrap(bytes));
        }

        String wire() {
            return new String(endpoint.getAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] settingsWithConnectProtocol() {
        byte[] payload = new byte[] {0, 8, 0, 0, 0, 1};
        byte[] out = new byte[9 + payload.length];
        out[2] = (byte) payload.length;
        out[3] = 4;
        System.arraycopy(payload, 0, out, 9, payload.length);
        return out;
    }

    @Test
    public void extendedConnectWithoutSubprotocolOrExtensionsOffersNeitherHeader() {
        InMemoryClient c = new InMemoryClient();
        c.setDeflateEnabled(false);
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        c.feed(settingsWithConnectProtocol());
        String wire = c.wire().toLowerCase();
        assertTrue(wire, wire.startsWith("pri * http/2.0"));
        assertTrue("a request was sent after the preface", c.endpoint.getAllBytes().length > 60);
        assertFalse(wire, wire.contains("permessage-deflate"));
        assertFalse(wire, wire.contains("sec-websocket-protocol"));
        assertEquals(0, h.errors.size());
    }

    @Test
    public void emptySubprotocolIsNotOffered() {
        InMemoryClient c = new InMemoryClient();
        c.setDeflateEnabled(false);
        c.setSubprotocol("");
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        c.feed(settingsWithConnectProtocol());
        assertFalse(c.wire().toLowerCase().contains("sec-websocket-protocol"));
    }

    @Test
    public void literalAddressGoesStraightToTheTcpPath() {
        InMemoryClient c = new InMemoryClient(java.net.InetAddress.getLoopbackAddress());
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        assertTrue(c.handler != null);
        String wire = c.wire();
        assertTrue(wire, wire.startsWith("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\n"));
        assertEquals(0, h.errors.size());
    }
}
