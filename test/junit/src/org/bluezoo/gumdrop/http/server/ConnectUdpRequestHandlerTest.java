/*
 * ConnectUdpRequestHandlerTest.java
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


package org.bluezoo.gumdrop.http.server;

import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.ConnectUdpTarget;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.testsupport.ResponseRecorder;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

/**
 * Request validation of {@link ConnectUdpRequestHandler} (RFC 9298):
 * everything decided before DNS resolution and the upstream UDP socket.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpRequestHandlerTest {

    private static final class State implements HttpResponse {
        final ResponseRecorder sent = new ResponseRecorder();
        boolean completed;
        HttpVersion version = HttpVersion.HTTP_3;

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return version; }
        @Override public String getScheme() { return "https"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void status(int code) { sent.status(code); }
        @Override public void header(String name, ByteBuffer rawValue) { String value = java.nio.charset.StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()).toString(); sent.header(name, value); }
        @Override public void endHeaders() { sent.endHeaders(); }
        @Override public void bodyContent(ByteBuffer data) { sent.bodyContent(); }
        @Override public void endMessage() { sent.endMessage(); completed = true; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) { }
        @Override public boolean endPushPromise() { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }

    private static final ConnectUdpPolicy ALLOW_ALL = new ConnectUdpPolicy() {
        @Override
        public boolean isTargetAllowed(InetAddress address, int port) {
            return true;
        }
    };

    private static List<Header> request(String protocol, String path, String capsule) {
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", protocol == null ? "GET" : "CONNECT"));
        if (protocol != null) {
            h.add(new Header(":protocol", protocol));
        }
        h.add(new Header(":scheme", "https"));
        h.add(new Header(":authority", "proxy.test"));
        h.add(new Header(":path", path));
        if (capsule != null) {
            h.add(new Header("capsule-protocol", capsule));
        }
        return h;
    }

    @Test
    public void testNullPolicyRejected() {
        try {
            new ConnectUdpRequestHandler(new State(), null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void testWantsDatagrams() {
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(new State(), ALLOW_ALL, 1000L);
        assertTrue(h.wantsDatagrams());
    }

    @Test
    public void testWrongProtocolIs400() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        String path = ConnectUdpTarget.encode("example.test", 53);
        MessageEvents.headers(h, HttpVersion.HTTP_3, request("websocket", path, "?1"));
        assertEquals("400", s.sent.getValue(":status"));
        assertTrue(s.completed);
    }

    @Test
    public void testMissingProtocolIs400() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        String path = ConnectUdpTarget.encode("example.test", 53);
        MessageEvents.headers(h, HttpVersion.HTTP_3, request(null, path, "?1"));
        assertEquals("400", s.sent.getValue(":status"));
    }

    @Test
    public void testMissingCapsuleProtocolIs400() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        String path = ConnectUdpTarget.encode("example.test", 53);
        MessageEvents.headers(h, HttpVersion.HTTP_3, request("connect-udp", path, null));
        assertEquals("400", s.sent.getValue(":status"));
    }

    @Test
    public void testBadTargetPathIs400() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        MessageEvents.headers(h, HttpVersion.HTTP_3, request("connect-udp", "/not/the/template", "?1"));
        assertEquals("400", s.sent.getValue(":status"));
    }

    @Test
    public void testHttp1UpgradeStyleWithoutUpgradeHeaderIs400() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        s.version = HttpVersion.HTTP_1_1;
        String path = ConnectUdpTarget.encode("example.test", 53);
        MessageEvents.headers(h, HttpVersion.HTTP_3, request("connect-udp", path, "?1"));
        assertEquals("400", s.sent.getValue(":status"));
    }

    @Test
    public void testDatagramAndFailureWithoutRelayAreIgnored() {
        State s = new State();
        ConnectUdpRequestHandler h = new ConnectUdpRequestHandler(s, ALLOW_ALL);
        h.datagramReceived(s, ByteBuffer.wrap(new byte[] {0, 1, 2}));
        h.failed(new java.io.IOException("x"));
        assertFalse(s.completed);
    }
}
