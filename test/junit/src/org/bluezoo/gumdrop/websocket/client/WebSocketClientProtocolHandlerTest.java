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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
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

    /** The fields of a valid 101 response, as name/value pairs in order. */
    private List<String[]> validResponse() {
        List<String[]> h = new ArrayList<String[]>();
        h.add(new String[] {"upgrade", "websocket"});
        h.add(new String[] {"connection", "Upgrade"});
        h.add(new String[] {"sec-websocket-accept", WebSocketHandshake.calculateAccept(key)});
        return h;
    }

    private static void replace(List<String[]> fields, String name, String value) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i)[0].equals(name)) {
                fields.get(i)[1] = value;
            }
        }
    }

    /**
     * Delivers a 101 response the way the HTTP layer does: its events to
     * protocolSwitchEvents(), then the hook.
     */
    private static boolean switchOn(WebSocketClientProtocolHandler h, List<String[]> fields) {
        HttpMessageHandler events = h.protocolSwitchEvents();
        assertNotNull("the handler must ask to see the 101 events", events);
        events.status(101);
        for (int i = 0; i < fields.size(); i++) {
            events.header(fields.get(i)[0],
                    ByteBuffer.wrap(fields.get(i)[1].getBytes(StandardCharsets.ISO_8859_1)));
        }
        events.endHeaders();
        return h.handleProtocolSwitch(HttpStatus.SWITCHING_PROTOCOLS);
    }

    private void upgrade() {
        assertTrue(switchOn(handler, validResponse()));
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
        assertFalse(switchOn(h, validResponse()));
        assertTrue(ws.errors.isEmpty());
    }

    @Test
    public void switchWithBadAcceptReportsError() {
        List<String[]> h = validResponse();
        replace(h, "sec-websocket-accept", "bogus");
        assertFalse(switchOn(handler, h));
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

    /**
     * The application may call the WebSocketSession from any thread, but
     * all I/O of the connection belongs to its selector loop: the frame
     * must be handed to the endpoint's execute(), not sent inline.
     */
    @Test
    public void outboundFramesAreRescheduledOntoTheEndpointsLoop() throws IOException {
        final BinaryRecordingEndpoint real = new BinaryRecordingEndpoint();
        final List<Runnable> queued = new ArrayList<Runnable>();
        Endpoint deferring = (Endpoint) Proxy.newProxyInstance(
                Endpoint.class.getClassLoader(), new Class<?>[] { Endpoint.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        if ("execute".equals(method.getName())) {
                            queued.add((Runnable) args[0]);
                            return null;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
        RecordingWebSocketEventHandler events = new RecordingWebSocketEventHandler();
        WebSocketClientProtocolHandler h = new WebSocketClientProtocolHandler(
                httpEvents, events, "localhost", 80, false);
        h.setWebSocketKey(key);
        h.connected(deferring);
        real.clearWrites();
        queued.clear();
        assertTrue(switchOn(h, validResponse()));

        events.session.sendText("from-worker");

        assertTrue("no write may happen on the calling thread", real.getWrites().isEmpty());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, real.getWrites().size());
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
        List<String[]> h = validResponse();
        h.add(new String[] {"sec-websocket-extensions", "permessage-deflate"});
        assertTrue(switchOn(handler, h));
        assertEquals(1, ws.openedCount);
    }

    @Test
    public void nullExtensionsTreatedAsEmpty() {
        handler.setRequestedExtensions(null);
        upgrade();
    }

    @Test
    public void switchRequiresEachOfUpgradeConnectionAndAccept() {
        String[] names = {"upgrade", "connection", "sec-websocket-accept"};
        for (int i = 0; i < names.length; i++) {
            List<String[]> h = validResponse();
            h.remove(i);
            assertFalse("missing " + names[i], switchOn(handler, h));
        }
        assertEquals(3, ws.errors.size());
        assertEquals(0, ws.openedCount);
        assertFalse(handler.isExternallyHandled());
    }

    @Test
    public void upgradeAndConnectionTokensMayBeSplitAcrossFieldLines() {
        List<String[]> h = new ArrayList<String[]>();
        h.add(new String[] {"Upgrade", "h2c"});
        h.add(new String[] {"Upgrade", "WebSocket"});
        h.add(new String[] {"Connection", "keep-alive"});
        h.add(new String[] {"Connection", "upgrade"});
        h.add(new String[] {"Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key)});
        assertTrue(switchOn(handler, h));
        assertEquals(1, ws.openedCount);
    }

    @Test
    public void eachSwitchStartsFromTheFieldsOfItsOwnResponse() {
        List<String[]> bad = validResponse();
        replace(bad, "sec-websocket-accept", "bogus");
        assertFalse(switchOn(handler, bad));
        // the rejected response's Accept must not be what the next one is judged on
        assertTrue(switchOn(handler, validResponse()));
    }
}
