/*
 * WebSocketClientProtocolHandlerTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the client-side RFC 6455 handshake validation and post-upgrade
 * frame handling of {@link WebSocketClientProtocolHandler}, entirely over
 * an in-memory endpoint.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientProtocolHandlerTest {

    private static final class HttpEvents implements HttpClientHandler {
        int connected;
        int disconnected;
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void onConnected(Endpoint endpoint) {
            connected++;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }

        @Override
        public void onDisconnected() {
            disconnected++;
        }
    }

    private HttpEvents httpEvents;
    private RecordingWebSocketEventHandler ws;
    private BinaryRecordingEndpoint endpoint;
    private WebSocketClientProtocolHandler handler;
    private String key;

    @Before
    public void setUp() {
        httpEvents = new HttpEvents();
        ws = new RecordingWebSocketEventHandler();
        endpoint = new BinaryRecordingEndpoint();
        handler = new WebSocketClientProtocolHandler(httpEvents, ws,
                "localhost", 80, false);
        key = WebSocketHandshake.generateKey();
        handler.setWebSocketKey(key);
        handler.connected(endpoint);
    }

    private Headers validResponse() {
        Headers h = new Headers();
        h.add("Upgrade", "websocket");
        h.add("Connection", "Upgrade");
        h.add("Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key));
        return h;
    }

    private void upgrade() {
        assertTrue(handler.handleProtocolSwitch(
                HttpStatus.SWITCHING_PROTOCOLS, validResponse()));
    }

    @Test
    public void connectedNotifiesHttpHandler() {
        assertEquals(1, httpEvents.connected);
        assertFalse(handler.isExternallyHandled());
        assertNull(handler.getWebSocketConnection());
    }

    @Test
    public void switchWithoutKeyIsNotHandled() {
        WebSocketClientProtocolHandler h = new WebSocketClientProtocolHandler(
                httpEvents, ws, "localhost", 80, false);
        assertFalse(h.handleProtocolSwitch(HttpStatus.SWITCHING_PROTOCOLS,
                validResponse()));
        assertTrue(ws.errors.isEmpty());
    }

    @Test
    public void switchWithBadAcceptReportsError() {
        Headers h = validResponse();
        h.set("Sec-WebSocket-Accept", "bogus");
        assertFalse(handler.handleProtocolSwitch(
                HttpStatus.SWITCHING_PROTOCOLS, h));
        assertEquals(1, ws.errors.size());
        assertEquals(0, ws.openedCount);
        assertFalse(handler.isExternallyHandled());
    }

    @Test
    public void validSwitchOpensConnection() {
        upgrade();
        assertTrue(handler.isExternallyHandled());
        assertEquals(1, ws.openedCount);
        WebSocketConnection conn = handler.getWebSocketConnection();
        assertNotNull(conn);
        assertTrue(conn.isOpen());
    }

    @Test
    public void framesAfterSwitchAreDelivered() throws IOException {
        upgrade();
        handler.receive(WebSocketFrame.createTextFrame("hello", false)
                .encode());
        assertEquals(Arrays.asList("hello"), ws.texts);
        handler.receive(WebSocketFrame.createBinaryFrame(
                ByteBuffer.wrap(new byte[] {1, 2, 3}), false).encode());
        assertEquals(1, ws.binaries.size());
        assertEquals(3, ws.binaries.get(0).length);
    }

    @Test
    public void outboundFramesAreMaskedAndSentOnEndpoint() throws IOException {
        endpoint.clearWrites();
        upgrade();
        ws.session.sendText("ping-me");
        assertEquals(1, endpoint.getWrites().size());
        WebSocketFrame f = WebSocketFrame.parse(
                ByteBuffer.wrap(endpoint.getWrites().get(0)));
        assertTrue(f.isMasked());
        assertEquals("ping-me", f.getTextPayload());
    }

    @Test
    public void sessionOperationsDelegateToConnection() throws IOException {
        upgrade();
        endpoint.clearWrites();
        ws.session.sendBinary(ByteBuffer.wrap(new byte[] {9}));
        ws.session.sendPing(ByteBuffer.wrap(new byte[] {7}));
        assertEquals(2, endpoint.getWrites().size());
        assertNull(ws.session.getPrincipal());
        assertTrue(ws.session.isOpen());
        ws.session.close(1000, "done");
        assertEquals(3, endpoint.getWrites().size());
        WebSocketFrame close = WebSocketFrame.parse(
                ByteBuffer.wrap(endpoint.getWrites().get(2)));
        assertEquals(WebSocketFrame.OPCODE_CLOSE, close.getOpcode());
        assertEquals(1000, close.getCloseCode());
    }

    @Test
    public void serverCloseFrameReportsCloseCode() throws IOException {
        upgrade();
        handler.receive(WebSocketFrame.createCloseFrame(1000, "bye", false)
                .encode());
        assertEquals(Arrays.asList(1000), ws.closeCodes);
    }

    @Test
    public void transportCloseAfterSwitchReports1001() {
        upgrade();
        handler.disconnected();
        assertEquals(Arrays.asList(1001), ws.closeCodes);
        assertEquals(0, httpEvents.disconnected);
    }

    @Test
    public void transportCloseBeforeSwitchNotReportedAsWebSocketClose() {
        handler.disconnected();
        assertTrue(ws.closeCodes.isEmpty());
    }

    @Test
    public void negotiatedDeflateExtensionActivated() {
        List<WebSocketExtension> requested =
                new ArrayList<WebSocketExtension>();
        requested.add(new PerMessageDeflateExtension());
        handler.setRequestedExtensions(requested);
        Headers h = validResponse();
        h.add("Sec-WebSocket-Extensions", "permessage-deflate");
        assertTrue(handler.handleProtocolSwitch(
                HttpStatus.SWITCHING_PROTOCOLS, h));
        assertEquals(1, ws.openedCount);
    }

    @Test
    public void nullExtensionsTreatedAsEmpty() {
        handler.setRequestedExtensions(null);
        upgrade();
    }
}
