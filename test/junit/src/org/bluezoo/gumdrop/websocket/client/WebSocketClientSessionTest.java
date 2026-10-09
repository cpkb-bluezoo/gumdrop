/*
 * WebSocketClientSessionTest.java
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
import org.bluezoo.gumdrop.http.HttpVersion;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.junit.After;
import org.junit.Test;

/**
 * Session-level behaviour of the HTTP/1.1 WebSocket client once the
 * upgrade has completed: sending through the session, transport loss,
 * protocol violations, and Alt-Svc handling, over an in-memory endpoint.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientSessionTest {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    private static class MemClient extends WebSocketClient {
        int connects;
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        WebSocketClientProtocolHandler handler;

        MemClient() {
            super("ws.example", 80);
            dnsHttpsRecordEnabled(false);
            versions(HttpVersion.HTTP_1_1);
            deflateEnabled(false);
        }

        @Override
        void connectEndpointForTesting(WebSocketClientProtocolHandler ph) throws IOException {
            connects++;
            handler = ph;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }

        String key() {
            String req = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
            int i = req.toLowerCase().indexOf("sec-websocket-key:");
            int eol = req.indexOf("\r\n", i);
            return req.substring(i + "sec-websocket-key:".length(), eol).trim();
        }

        void accept() {
            String r = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                    + WebSocketHandshake.calculateAccept(key()) + "\r\n\r\n";
            handler.receive(ByteBuffer.wrap(r.getBytes(StandardCharsets.US_ASCII)));
        }

        void feed(WebSocketFrame f) {
            handler.receive(f.encode());
        }
    }

    private static MemClient opened(RecordingWebSocketEventHandler h) {
        MemClient c = new MemClient();
        c.connect(null, "/", h);
        c.accept();
        assertEquals(1, h.openedCount);
        return c;
    }

    @Test
    public void sessionSendsThroughTheEndpointOnItsLoop() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = opened(h);
        c.endpoint.clearWrites();
        h.session.sendText("hello");
        h.session.sendBinary(ByteBuffer.wrap(new byte[] {1, 2}));
        h.session.sendPing(ByteBuffer.wrap(new byte[] {3}));
        assertEquals(3, c.endpoint.getWrites().size());
        assertTrue(h.session.isOpen());
        assertNull(h.session.getPrincipal());
        h.session.close(1001, "leaving");
        assertFalse(h.session.isOpen());
    }

    @Test
    public void sessionCloseWithoutCodeStartsHandshake() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = opened(h);
        h.session.close();
        c.feed(WebSocketFrame.createCloseFrame(1000, null, false));
        assertEquals(1, h.closeCodes.size());
        assertEquals(Integer.valueOf(1000), h.closeCodes.get(0));
    }

    @Test
    public void transportLossAfterUpgradeReportsGoingAwayOnce() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = opened(h);
        c.handler.disconnected();
        assertEquals(1, h.closeCodes.size());
        assertEquals(Integer.valueOf(1001), h.closeCodes.get(0));
        assertEquals("Transport closed", h.closeReasons.get(0));
        c.handler.disconnected();
        assertEquals(1, h.closeCodes.size());
    }

    @Test
    public void maskedServerFrameIsAProtocolViolation() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = opened(h);
        WebSocketFrame masked = new WebSocketFrame(1, new byte[] {1}, true);
        c.feed(masked);
        assertEquals(1, h.errors.size());
        assertTrue(h.texts.isEmpty());
    }

    @Test
    public void disconnectBeforeUpgradeUsesHttpClientHandling() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = new MemClient();
        c.connect(null, "/", h);
        c.handler.disconnected();
        assertEquals(0, h.openedCount);
        assertFalse(c.isOpen());
    }

    @Test
    public void sendingAfterTransportLossFails() throws Exception {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        MemClient c = opened(h);
        c.handler.disconnected();
        try {
            h.session.sendText("late");
            org.junit.Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** Resolver answering every HTTPS query with canned records. */
    private static final class AnswerResolver extends DnsResolver {
        final List<DnsResourceRecord> answers;

        AnswerResolver(List<DnsResourceRecord> answers) {
            this.answers = answers;
        }

        @Override
        public void queryHTTPS(String name, DnsQueryCallback callback) {
            DnsMessage msg = new DnsMessage(1, 0x8180, new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(), new ArrayList<DnsResourceRecord>());
            callback.onResponse(msg);
        }
    }

    private static DnsResourceRecord httpsRecord(int priority, byte[] params) {
        byte[] rdata = new byte[3 + params.length];
        rdata[0] = (byte) (priority >> 8);
        rdata[1] = (byte) priority;
        rdata[2] = 0;
        System.arraycopy(params, 0, rdata, 3, params.length);
        return new DnsResourceRecord("ws.example", DnsType.HTTPS, DnsClass.IN, 60, rdata);
    }

    @Test
    public void dnsRecordsWithoutH3FallBackToTcpAfterSkippingUnusableOnes() {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(new DnsResourceRecord("ws.example", DnsType.A, DnsClass.IN, 60,
                new byte[] {127, 0, 0, 1}));
        answers.add(httpsRecord(0, new byte[0]));
        byte[] alpnH2AndEch = {0, 1, 0, 3, 2, 'h', '2', 0, 5, 0, 3, 'e', 'c', 'h'};
        answers.add(httpsRecord(1, alpnH2AndEch));
        MemClient c = new MemClient();
        c.dnsHttpsRecordEnabled(true);
        c.dnsResolver(new AnswerResolver(answers));
        c.selectorLoop(new InlineSelectorLoop());
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        assertEquals(1, c.connects);
        c.accept();
        assertEquals(1, h.openedCount);
    }

    @Test
    public void altSvcWithExplicitAlternateHostIsCached() {
        MemClient c = new MemClient();
        c.altSvcReceived("h3=\"alt.example:8443\"; ma=60");
        assertNotNull(AltSvcCache.get("ws.example", 80));
        assertEquals(8443, AltSvcCache.get("ws.example", 80).getH3Port());
    }
}
