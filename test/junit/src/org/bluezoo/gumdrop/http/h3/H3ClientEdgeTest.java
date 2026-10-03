/*
 * H3ClientEdgeTest.java
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

import org.bluezoo.gumdrop.http.HeaderFields;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.junit.Test;

/**
 * Further edge cases of the HTTP/3 client ({@link Http3ClientHandler},
 * {@link H3ClientStream}) over the in-memory QUIC connection of
 * {@link H3ClientFlowTest}: GOAWAY retry semantics, response/request
 * content-coding plumbing, deferred Extended CONNECT.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ClientEdgeTest {

    private static Http3ClientHandler handlerOn(QuicConnection conn) {
        return new Http3ClientHandler(conn);
    }

    @Test
    public void testGoawayFailsOnlyTheStreamsBeyondTheLastProcessedId() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3ClientFlowTest.Rec first = new H3ClientFlowTest.Rec();
        H3ClientFlowTest.Rec second = new H3ClientFlowTest.Rec();
        long a = h.sendRequest(H3ClientFlowTest.request("GET"), first);
        long b = h.sendRequest(H3ClientFlowTest.request("GET"), second);
        assertTrue(b > a);
        h.goawayReceived(a);
        assertTrue(second.events.toString(), second.events.contains("failed"));
        assertFalse(first.events.toString(), first.events.contains("failed"));
        assertEquals(1, H3ClientFlowTest.streams(h).size());
    }

    @Test
    public void testConnectProtocolTaskRunsImmediatelyOnceSettingsArrived() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        final int[] ran = new int[1];
        Runnable task = new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        };
        h.whenConnectProtocolKnown(task);
        assertEquals(0, ran[0]);
        h.settingsReceived(new long[] {0x08, 1});
        assertEquals(1, ran[0]);
        h.whenConnectProtocolKnown(task);
        assertEquals(2, ran[0]);
    }

    @Test
    public void testAcceptEncodingIsAddedOnlyWhenTheRequestHasNone() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        h.setSendAcceptEncodingHeader(true);
        List<Header> without = new ArrayList<Header>();
        h.applyDefaultAcceptEncoding(without);
        assertEquals("br, gzip, deflate", HeaderFields.getValue(without, "accept-encoding"));
        List<Header> with = new ArrayList<Header>();
        HeaderFields.add(with, "accept-encoding", "identity");
        h.applyDefaultAcceptEncoding(with);
        assertEquals("identity", HeaderFields.getValue(with, "accept-encoding"));
        h.applyDefaultAcceptEncoding(null);
        h.setSendAcceptEncodingHeader(false);
        List<Header> off = new ArrayList<Header>();
        h.applyDefaultAcceptEncoding(off);
        assertNull(HeaderFields.getValue(off, "accept-encoding"));
    }

    @Test
    public void testUnknownResponseContentEncodingIsDeliveredUndecoded() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        h.setDecodeResponseContentCoding(true);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        h.sendRequest(H3ClientFlowTest.request("GET"), rec);
        H3ClientStream s = H3ClientFlowTest.only(h);
        H3ClientFlowTest.feed(s, H3ServerFlowTest.concat(
                H3ClientFlowTest.response("200", "content-encoding", "bogus"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2, 3})));
        s.readFinished();
        assertEquals(3, rec.bodyBytes);
        assertNull(s.getInboundResponseDecoder());
        assertTrue(rec.events.contains("end"));
    }

    @Test
    public void testDecoderHelpersTolerateMissingStreamsAndHeaders() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        h.setDecodeResponseContentCoding(true);
        h.prepareInboundResponseDecoding(null, new ArrayList<Header>());
        h.feedResponseBody(null, ByteBuffer.wrap(new byte[] {1}));
        h.finishResponseBody(null);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        h.sendRequest(H3ClientFlowTest.request("GET"), rec);
        H3ClientStream s = H3ClientFlowTest.only(h);
        h.prepareInboundResponseDecoding(s, null);
        assertNull(s.getInboundResponseDecoder());
        assertTrue(h.omitContentEncodingHeader("Content-Encoding"));
        assertFalse(h.omitContentEncodingHeader("content-type"));
        h.setDecodeResponseContentCoding(false);
        assertFalse(h.omitContentEncodingHeader("Content-Encoding"));
    }

    @Test
    public void testTruncatedGzipResponseFailsTheHandlerAtTheEnd() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        h.setDecodeResponseContentCoding(true);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        h.sendRequest(H3ClientFlowTest.request("GET"), rec);
        H3ClientStream s = H3ClientFlowTest.only(h);
        byte[] whole = H3ServerFlowTest.gzip("hello hello hello hello".getBytes("UTF-8"));
        byte[] cut = new byte[whole.length - 8];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        H3ClientFlowTest.feed(s, H3ServerFlowTest.concat(
                H3ClientFlowTest.response("200", "content-encoding", "gzip"),
                H3ServerFlowTest.dataFrame(cut)));
        s.readFinished();
        assertTrue(rec.events.toString(), rec.events.contains("failed"));
        assertFalse(rec.events.toString(), rec.events.contains("end"));
    }

    @Test
    public void testQueuedCompressedBodyIsFlushedOnceTheStreamOpens() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        Http3ClientHandler h = handlerOn(conn);
        h.setEncodeRequestBodyContentCoding(true);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        H3ClientStream cs = new H3ClientStream(h, new Decoder(4096), rec);
        cs.prepareRequest(H3ClientFlowTest.request("POST", "content-encoding", "gzip"), false);
        h.sendRequestBody(cs, ByteBuffer.wrap(new byte[300]), false);
        h.sendRequestBody(cs, ByteBuffer.wrap(new byte[] {1, 2}), true);
        conn.openStream(cs);
        assertNotNull(cs.getEndpoint());
        assertTrue(cs.getEndpoint().isClosing());
    }

    @Test
    public void testQueuedPlainBodyWithoutFinLeavesTheStreamOpen() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        Http3ClientHandler h = handlerOn(conn);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        H3ClientStream cs = new H3ClientStream(h, new Decoder(4096), rec);
        cs.prepareRequest(H3ClientFlowTest.request("POST"), false);
        h.sendRequestBody(cs, ByteBuffer.wrap(new byte[] {1, 2}), false);
        conn.openStream(cs);
        assertFalse(cs.getEndpoint().isClosing());
        H3ClientStream done = new H3ClientStream(h, new Decoder(4096), rec);
        done.prepareRequest(H3ClientFlowTest.request("POST"), false);
        h.sendRequestBody(done, ByteBuffer.wrap(new byte[] {1}), true);
        conn.openStream(done);
        assertTrue(done.getEndpoint().isClosing());
    }

    @Test
    public void testWebSocketConnectOffersTheSubprotocolAndExtensions() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        h.settingsReceived(new long[] {0x08, 1});
        List<WebSocketExtension> extensions = new ArrayList<WebSocketExtension>();
        extensions.add(new H3ServerEdgeTest.StubExtension("permessage-x", null));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        long id = h.connectWebSocket("example.com", "/ws", "chat", extensions, ws);
        assertTrue(id >= 0);
        RecordingWebSocketEventHandler plain = new RecordingWebSocketEventHandler();
        long id2 = h.connectWebSocket("example.com", "/ws", "", new ArrayList<WebSocketExtension>(), plain);
        assertTrue(id2 > id);
    }
}
