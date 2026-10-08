/*
 * H3ServerEdgeTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpServerMetrics;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.junit.Test;

/**
 * Further edge cases of the HTTP/3 server request path
 * ({@link H3Stream}, {@link Http3ServerHandler}) over the in-memory QUIC
 * connection of {@link H3ServerFlowTest}: trailers, response-slot
 * arbitration between streams, WebSocket extension negotiation, telemetry
 * spans for each outcome and GOAWAY handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ServerEdgeTest {

    static class StubExtension implements WebSocketExtension {
        private final String name;
        private final Map<String, String> offer;

        StubExtension(String name, Map<String, String> offer) {
            this.name = name;
            this.offer = offer;
        }

        @Override public String getName() { return name; }
        @Override public boolean usesRsv1() { return false; }
        @Override public boolean usesRsv2() { return false; }
        @Override public boolean usesRsv3() { return false; }
        @Override public Map<String, String> acceptOffer(Map<String, String> p) { return p; }
        @Override public Map<String, String> generateOffer() { return offer; }
        @Override public boolean acceptResponse(Map<String, String> p) { return true; }
        @Override public byte[] encode(byte[] payload) { return payload; }
        @Override public byte[] decode(byte[] payload) { return payload; }
        @Override public void close() { }
    }

    private static HttpResponse respondOk(H3ServerFlowTest.Fixture f, H3Stream stream,
            boolean body) {
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.get("/"));
        stream.readFinished();
        HttpResponse state = f.rec.state;
        state.status(HttpStatus.OK.code);
        if (body) {
            state.bodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }
        return state;
    }

    private static TelemetryConfig tracing() {
        TelemetryConfig tc = new TelemetryConfig();
        tc.setTracesEnabled(true);
        return tc;
    }

    // ------------------------------------------------------------------
    // request side

    @Test
    public void testTrailersAfterTheBodyReachTheHandlerAsASecondHeadersEvent() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream stream = f.open();
        byte[] req = H3ServerFlowTest.concat(
                H3ServerFlowTest.headersFrame(":method", "POST", ":scheme", "https",
                        ":path", "/t", ":authority", "x"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2, 3}),
                H3ServerFlowTest.headersFrame("x-trailer", "done"));
        H3ServerFlowTest.feed(stream, req);
        stream.readFinished();
        int headerEvents = 0;
        for (int i = 0; i < f.rec.events.size(); i++) {
            if ("headers".equals(f.rec.events.get(i))) {
                headerEvents++;
            }
        }
        assertEquals(f.rec.events.toString(), 2, headerEvents);
        assertEquals(3, f.rec.bodyBytes);
        assertTrue(f.rec.events.contains("complete"));
    }

    @Test
    public void testConnectionGoawayRefusesStreamsBeyondTheLastAcceptedId() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        f.server.goawayReceived(0L);
        Endpoint ep = f.conn.openStream(new H3ServerFlowTest.Noop());
        ProtocolHandler refused = f.server.acceptStream(ep);
        assertNull(refused);
    }

    @Test
    public void testGoawayAfterAnAcceptedStreamSendsAGoawayInReply() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream stream = f.open();
        assertNotNull(stream);
        f.server.goawayReceived(100L);
        Endpoint ep = f.conn.openStream(new H3ServerFlowTest.Noop());
        ProtocolHandler later = f.server.acceptStream(ep);
        assertNotNull(later);
    }

    // ------------------------------------------------------------------
    // response slot arbitration (RFC 9218 non-incremental streams)

    @Test
    public void testSecondStreamsBodyIsHeldUntilTheFirstFinishes() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream a = f.open();
        HttpResponse stateA = respondOk(f, a, true);
        H3Stream b = f.open();
        HttpResponse stateB = respondOk(f, b, true);
        assertFalse(a.hasHeldBody());
        assertTrue(b.hasHeldBody());
        stateA.endMessage();
        a.error(new IOException("done with a"));
        assertFalse(b.hasHeldBody());
    }

    @Test
    public void testHeldBodiesOfOtherUrgenciesAreNotReleasedByAFinishingStream() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream a = f.open();
        H3ServerFlowTest.feed(a, H3ServerFlowTest.headersFrame(":method", "GET", ":scheme", "https",
                ":path", "/", ":authority", "x", "priority", "u=1"));
        a.readFinished();
        HttpResponse stateA = f.rec.state;
        stateA.status(HttpStatus.OK.code);
        stateA.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        H3Stream b = f.open();
        H3ServerFlowTest.feed(b, H3ServerFlowTest.headersFrame(":method", "GET", ":scheme", "https",
                ":path", "/", ":authority", "x", "priority", "u=5"));
        b.readFinished();
        HttpResponse stateB = f.rec.state;
        stateB.status(HttpStatus.OK.code);
        stateB.bodyContent(ByteBuffer.wrap(new byte[] {2}));
        assertFalse(a.hasHeldBody());
        assertFalse(b.hasHeldBody());
        a.error(new IOException("a gone"));
        assertFalse(b.hasHeldBody());
    }

    @Test
    public void testHeldBodiesAreReleasedOneAtATimeLowestStreamFirst() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream a = f.open();
        HttpResponse stateA = respondOk(f, a, true);
        H3Stream b = f.open();
        respondOk(f, b, true);
        H3Stream c = f.open();
        respondOk(f, c, true);
        assertTrue(b.hasHeldBody());
        assertTrue(c.hasHeldBody());
        stateA.endMessage();
        a.error(new IOException("a gone"));
        assertFalse(b.hasHeldBody());
        assertTrue(c.hasHeldBody());
    }

    // ------------------------------------------------------------------
    // WebSocket

    @Test
    public void testWebSocketUpgradeAdvertisesSubprotocolAndExtensions() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "CONNECT",
                ":protocol", "websocket", ":scheme", "https", ":path", "/ws", ":authority", "x"));
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("client_max_window_bits", "15");
        params.put("server_no_context_takeover", null);
        List<WebSocketExtension> extensions = new ArrayList<WebSocketExtension>();
        extensions.add(new StubExtension("permessage-x", params));
        extensions.add(new StubExtension("bare-ext", null));
        extensions.add(new StubExtension("empty-ext", new LinkedHashMap<String, String>()));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        stream.upgradeToWebSocket("chat", extensions, ws);
        assertTrue(stream.isWebSocketUpgraded());
        assertEquals(1, ws.openedCount);
    }

    @Test
    public void testWebSocketUpgradeAfterTheResponseStartedIsRejected() throws Exception {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "CONNECT",
                ":protocol", "websocket", ":scheme", "https", ":path", "/ws", ":authority", "x"));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        stream.upgradeToWebSocket(null, ws);
        try {
            stream.upgradeToWebSocket(null, new RecordingWebSocketEventHandler());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, ws.openedCount);
        }
    }

    @Test
    public void testWebSocketUpgradeWithTracingJoinsTheRequestSpan() throws Exception {
        TelemetryConfig tc = tracing();
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture(null, null, tc, false, false, null);
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "CONNECT",
                ":protocol", "websocket", ":scheme", "https", ":path", "/ws",
                ":authority", "x", "user-agent", "probe"));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        stream.upgradeToWebSocket(null, ws);
        assertEquals(1, ws.openedCount);
        assertNotNull(f.server.getTrace());
        stream.error(new IOException("lost"));
        assertEquals(1, ws.errors.size() + ws.closeCodes.size());
    }

    // ------------------------------------------------------------------
    // telemetry spans per outcome

    private static void respondWithStatus(H3ServerFlowTest.Fixture f, H3Stream stream,
            String path, HttpStatus status) {
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "GET",
                ":scheme", "https", ":path", path, ":authority", "x",
                "user-agent", "probe/1"));
        stream.readFinished();
        f.rec.state.status(status.code);
        f.rec.state.endMessage();
    }

    @Test
    public void testEachResponseStatusClassEndsItsSpan() throws Exception {
        TelemetryConfig tc = tracing();
        HttpServerMetrics metrics = new HttpServerMetrics(tc);
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture(null, metrics, tc, false, false, null);
        respondWithStatus(f, f.open(), "/ok", HttpStatus.OK);
        respondWithStatus(f, f.open(), "/missing", HttpStatus.NOT_FOUND);
        respondWithStatus(f, f.open(), "/boom", HttpStatus.INTERNAL_SERVER_ERROR);
        assertNotNull(f.server.getTrace());
        assertNotNull(f.server.getTelemetryConfig());
    }

    @Test
    public void testSpanIsEndedWhenTheRequestIsCancelledOrAborted() throws Exception {
        TelemetryConfig tc = tracing();
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture(null, null, tc, false, false, null);
        H3Stream cancelled = f.open();
        H3ServerFlowTest.feed(cancelled, H3ServerFlowTest.get("/c"));
        cancelled.cancel();
        assertNull(cancelled.getHandler());
        H3Stream mismatch = f.open();
        H3ServerFlowTest.feed(mismatch, H3ServerFlowTest.concat(
                H3ServerFlowTest.headersFrame(":method", "POST", ":scheme", "https",
                        ":path", "/m", ":authority", "x", "content-length", "5"),
                H3ServerFlowTest.dataFrame(new byte[] {1})));
        mismatch.readFinished();
        assertFalse(f.rec.events.toString(), f.rec.events.contains("complete"));
    }

    @Test
    public void testOversizedFieldSectionWithTracingEndsTheSpan() throws Exception {
        TelemetryConfig tc = tracing();
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture(null, null, tc, false, false, null);
        H3Stream stream = f.open();
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < (int) H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE; i++) {
            pad.append('x');
        }
        H3ServerFlowTest.feed(stream, largeHeadersFrame(
                ":method", "GET", ":scheme", "https", ":path", "/", ":authority", "x",
                "x-pad", pad.toString()));
        assertNull(stream.getHandler());
        assertFalse(f.rec.events.contains("headers"));
    }

    @Test
    public void testStreamErrorWithoutAnyHeadersLeavesNoHandlerCallbacks() throws Exception {
        TelemetryConfig tc = tracing();
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture(null, null, tc, false, false, null);
        H3Stream stream = f.open();
        stream.error(new IOException("reset before headers"));
        assertEquals(1, f.rec.events.size());
        assertEquals("failed", f.rec.events.get(0));
        assertNull(stream.getHandler());
    }

    private static byte[] largeHeadersFrame(String... pairs) {
        List<Header> headers = new ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        SimpleEncoder encoder = new SimpleEncoder();
        encoder.setAutoHuffman(false);
        ByteBuffer buf = ByteBuffer.allocate(16384);
        encoder.encode(buf, headers);
        buf.flip();
        byte[] enc = new byte[buf.remaining()];
        buf.get(enc);
        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(enc.length));
        H3Writer.writeHeaders(out, enc);
        return out.array();
    }
}
