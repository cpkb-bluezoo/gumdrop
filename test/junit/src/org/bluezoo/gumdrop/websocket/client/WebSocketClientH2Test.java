/*
 * WebSocketClientH2Test.java
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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.junit.After;
import org.junit.Test;

/**
 * Drives {@link WebSocketClient} through the RFC 8441 (WebSocket over
 * HTTP/2) path and its transport-selection branches, using h2c prior
 * knowledge over an in-memory endpoint and hand-built HTTP/2 frames.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientH2Test {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    /** Client whose endpoint is an in-memory recorder. */
    private static final class InMemoryClient extends WebSocketClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        WebSocketClientProtocolHandler handler;
        int connects;

        InMemoryClient(String host) {
            super(host, 80);
            dnsHttpsRecordEnabled(false);
        }

        InMemoryClient(String socketPath, boolean unused) {
            super(socketPath);
        }

        @Override
        void connectEndpointForTesting(WebSocketClientProtocolHandler ph)
                throws IOException {
            connects++;
            handler = ph;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }

        void feed(byte[] bytes) {
            handler.receive(ByteBuffer.wrap(bytes));
        }
    }

    private static byte[] frame(int type, int flags, int stream, byte[] payload) {
        byte[] out = new byte[9 + payload.length];
        out[0] = (byte) (payload.length >> 16);
        out[1] = (byte) (payload.length >> 8);
        out[2] = (byte) payload.length;
        out[3] = (byte) type;
        out[4] = (byte) flags;
        out[5] = (byte) (stream >> 24);
        out[6] = (byte) (stream >> 16);
        out[7] = (byte) (stream >> 8);
        out[8] = (byte) stream;
        System.arraycopy(payload, 0, out, 9, payload.length);
        return out;
    }

    private static byte[] settingsWithConnectProtocol() {
        return frame(4, 0, 0, new byte[] {0, 8, 0, 0, 0, 1});
    }

    private static byte[] settingsWithoutConnectProtocol() {
        return frame(4, 0, 0, new byte[0]);
    }

    private static byte[] bytes(WebSocketFrame f) {
        ByteBuffer b = f.encode();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private InMemoryClient h2Client() {
        InMemoryClient c = new InMemoryClient("ws.example");
        c.versions(HttpVersion.HTTP_2_0, HttpVersion.HTTP_1_1);
        c.h2WithPriorKnowledge(true);
        return c;
    }

    @Test
    public void serverWithoutConnectProtocolIsReportedAsError() {
        InMemoryClient c = h2Client();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        assertTrue(h.errors.isEmpty());
        c.feed(settingsWithoutConnectProtocol());
        assertEquals(1, h.errors.size());
        assertTrue(h.errors.get(0).getMessage().contains("Extended CONNECT"));
    }

    @Test
    public void extendedConnectOpensWebSocketAndDeliversMessages() throws IOException {
        InMemoryClient c = h2Client();
        c.subprotocol("chat");
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        c.feed(settingsWithConnectProtocol());
        // :status 200, END_HEADERS
        c.feed(frame(1, 0x4, 1, new byte[] {(byte) 0x88}));
        assertEquals(1, h.openedCount);
        assertTrue(c.isOpen());

        c.feed(frame(0, 0, 1, bytes(WebSocketFrame.createTextFrame("hi", false))));
        c.feed(frame(0, 0, 1, bytes(WebSocketFrame.createBinaryFrame(
                ByteBuffer.wrap(new byte[] {1, 2}), false))));
        assertEquals(Arrays.asList("hi"), h.texts);
        assertEquals(1, h.binaries.size());

        c.feed(frame(0, 0, 1, bytes(WebSocketFrame.createCloseFrame(1000, "bye", false))));
        assertEquals(Arrays.asList(1000), h.closeCodes);
        c.close();
        assertFalse(c.isOpen());
    }

    @Test
    public void extendedConnectRefusedReportsError() {
        InMemoryClient c = h2Client();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        c.feed(settingsWithConnectProtocol());
        // :status 403 is not in the HPACK static table as a bare index
        // (static index 13 = :status 404)
        c.feed(frame(1, 0x4, 1, new byte[] {(byte) 0x8D}));
        assertEquals(0, h.openedCount);
        assertEquals(1, h.errors.size());
    }

    @Test
    public void streamResetAfterOpenReportsClosure() {
        InMemoryClient c = h2Client();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/ws", h);
        c.feed(settingsWithConnectProtocol());
        c.feed(frame(1, 0x4, 1, new byte[] {(byte) 0x88}));
        // DATA with END_STREAM
        c.feed(frame(0, 0x1, 1, new byte[0]));
        assertEquals(Arrays.asList(1001), h.closeCodes);
    }

    // ---- transport selection ----

    @Test
    public void unixSocketGoesStraightToTcp() {
        InMemoryClient c = new InMemoryClient("/tmp/none.sock", true);
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        assertEquals(1, c.connects);
    }

    @Test
    public void literalHostSkipsDiscovery() {
        InMemoryClient c = new InMemoryClient("127.0.0.1");
        c.dnsHttpsRecordEnabled(true);
        c.connect(null, "/", new RecordingWebSocketEventHandler());
        assertEquals(1, c.connects);
    }

    /** Resolver that answers every HTTPS query from a canned outcome. */
    private static final class StubResolver extends DnsResolver {
        final boolean fail;
        int queries;

        StubResolver(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void queryHTTPS(String name, DnsQueryCallback callback) {
            queries++;
            if (fail) {
                callback.onError("SERVFAIL");
                return;
            }
            DnsMessage empty = new DnsMessage(1, 0x8180,
                    new ArrayList<DnsQuestion>(),
                    new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>());
            callback.onResponse(empty);
        }
    }

    @Test
    public void dnsErrorFallsBackToTcp() {
        InMemoryClient c = new InMemoryClient("ws.example");
        c.dnsHttpsRecordEnabled(true);
        StubResolver resolver = new StubResolver(true);
        c.dnsResolver(resolver);
        c.selectorLoop(new InlineSelectorLoop());
        c.connect(null, "/", new RecordingWebSocketEventHandler());
        assertEquals(1, resolver.queries);
        assertEquals(1, c.connects);
    }

    @Test
    public void dnsAnswerWithoutH3FallsBackToTcp() {
        InMemoryClient c = new InMemoryClient("ws.example");
        c.dnsHttpsRecordEnabled(true);
        StubResolver resolver = new StubResolver(false);
        c.dnsResolver(resolver);
        c.selectorLoop(new InlineSelectorLoop());
        c.connect(null, "/", new RecordingWebSocketEventHandler());
        assertEquals(1, resolver.queries);
        assertEquals(1, c.connects);
    }

    @Test
    public void localhostSkipsDiscovery() {
        InMemoryClient c = new InMemoryClient("localhost");
        c.dnsHttpsRecordEnabled(true);
        c.connect(null, "/", new RecordingWebSocketEventHandler());
        assertEquals(1, c.connects);
    }
}
