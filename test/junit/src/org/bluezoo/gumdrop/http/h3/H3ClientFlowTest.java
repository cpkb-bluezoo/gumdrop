/*
 * H3ClientFlowTest.java
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

package org.bluezoo.gumdrop.http.h3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.client.ConnectIpClientSession;
import org.bluezoo.gumdrop.http.client.ConnectIpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpSession;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.junit.Test;

/**
 * End-to-end unit tests of the HTTP/3 client request and response path
 * over an in-memory QUIC connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ClientFlowTest {

    static final class Rec extends CollectingResponseHandler {
        final List<String> events = new ArrayList<String>();
        final List<String> headers = new ArrayList<String>();
        int bodyBytes;
        boolean datagrams;
        int datagramCount;
        int capsuleCount;

        @Override
        public void ok(HttpStatus response) {
            events.add("ok");
        }

        @Override
        public void error(HttpStatus response) {
            events.add("error");
        }

        @Override
        public void header(String name, String value) {
            headers.add(name);
        }

        @Override
        public void startResponseBody() {
            events.add("start");
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            bodyBytes += data.remaining();
        }

        @Override
        public void endResponseBody() {
            events.add("end");
        }

        @Override
        public void close() {
            events.add("close");
        }

        @Override
        public void failed(Exception ex) {
            events.add("failed");
        }

        @Override
        public boolean wantsDatagrams() {
            return datagrams;
        }

        @Override
        public void datagramReceived(ByteBuffer data) {
            datagramCount++;
        }

        @Override
        public void capsuleReceived(long type, ByteBuffer value) {
            capsuleCount++;
        }
    }

    static List<Header> request(String method, String... extra) {
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", method));
        h.add(new Header(":scheme", "https"));
        h.add(new Header(":authority", "example.com"));
        h.add(new Header(":path", "/"));
        for (int i = 0; i < extra.length; i += 2) {
            h.add(new Header(extra[i], extra[i + 1]));
        }
        return h;
    }

    static void feed(H3ClientStream s, byte[] data) {
        s.receive(ByteBuffer.wrap(data));
    }

    static byte[] response(String status, String... extra) {
        String[] all = new String[extra.length + 2];
        all[0] = ":status";
        all[1] = status;
        System.arraycopy(extra, 0, all, 2, extra.length);
        return H3ServerFlowTest.headersFrame(all);
    }

    @SuppressWarnings("unchecked")
    static Map<Long, H3ClientStream> streams(Http3ClientHandler h) throws Exception {
        Field f = Http3ClientHandler.class.getDeclaredField("streams");
        f.setAccessible(true);
        return (Map<Long, H3ClientStream>) f.get(h);
    }

    static H3ClientStream only(Http3ClientHandler h) throws Exception {
        Map<Long, H3ClientStream> m = streams(h);
        assertEquals(1, m.size());
        return m.values().iterator().next();
    }

    static Http3ClientHandler client() {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        return new Http3ClientHandler(conn);
    }

    @Test
    public void testSimpleGet() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        long id = h.sendRequest(request("GET"), rec);
        assertTrue(id >= 0);
        H3ClientStream s = only(h);
        feed(s, H3ServerFlowTest.concat(
                response("200", "content-type", "text/plain", "content-length", "3"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2, 3})));
        s.readFinished();
        assertTrue(rec.events.toString(), rec.events.contains("ok"));
        assertEquals(3, rec.bodyBytes);
        assertTrue(rec.events.contains("end"));
        assertTrue(rec.events.contains("close"));
        assertTrue(rec.headers.contains("content-type"));
        assertTrue(s.isClosed());
    }

    @Test
    public void testErrorStatusAndInformational() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        feed(s, H3ServerFlowTest.concat(response("103", "link", "</a>"), response("404")));
        s.readFinished();
        assertTrue(rec.events.contains("error"));
    }

    @Test
    public void testMissingStatus() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        feed(s, H3ServerFlowTest.headersFrame("x-foo", "bar"));
        assertTrue(rec.events.contains("failed"));
    }

    @Test
    public void testBodyErrors() throws Exception {
        Http3ClientHandler h = client();
        Rec r1 = new Rec();
        h.sendRequest(request("HEAD"), r1);
        H3ClientStream s1 = only(h);
        feed(s1, H3ServerFlowTest.concat(response("200"),
                H3ServerFlowTest.dataFrame(new byte[] {1})));
        assertTrue(r1.events.contains("failed"));

        Http3ClientHandler h2 = client();
        Rec r2 = new Rec();
        h2.sendRequest(request("GET"), r2);
        H3ClientStream s2 = only(h2);
        feed(s2, H3ServerFlowTest.concat(response("200", "content-length", "1"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2})));
        assertTrue(r2.events.contains("failed"));

        Http3ClientHandler h3 = client();
        Rec r3 = new Rec();
        h3.sendRequest(request("GET"), r3);
        H3ClientStream s3 = only(h3);
        feed(s3, H3ServerFlowTest.concat(response("200", "content-length", "5"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2})));
        s3.readFinished();
        assertTrue(r3.events.contains("failed"));

        Http3ClientHandler h4 = client();
        Rec r4 = new Rec();
        h4.sendRequest(request("GET"), r4);
        H3ClientStream s4 = only(h4);
        feed(s4, response("200", "content-length", "x"));
        assertTrue(r4.events.contains("failed"));

        Http3ClientHandler h5 = client();
        Rec r5 = new Rec();
        h5.sendRequest(request("GET"), r5);
        H3ClientStream s5 = only(h5);
        feed(s5, H3ServerFlowTest.dataFrame(new byte[] {1}));

        Http3ClientHandler h6 = client();
        Rec r6 = new Rec();
        h6.sendRequest(request("GET"), r6);
        H3ClientStream s6 = only(h6);
        feed(s6, response("204"));
        s6.readFinished();
        assertTrue(r6.events.contains("close"));
    }

    @Test
    public void testFrameErrorsOnClientStream() throws Exception {
        long[] settings = new long[] {1, 1};
        List<byte[]> frames = new ArrayList<byte[]>();
        ByteBuffer b = ByteBuffer.allocate(H3Writer.settingsLength(settings));
        H3Writer.writeSettings(b, settings);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.goawayLength(4));
        H3Writer.writeGoaway(b, 4);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.maxPushIdLength(4));
        H3Writer.writeMaxPushId(b, 4);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.cancelPushLength(4));
        H3Writer.writeCancelPush(b, 4);
        frames.add(b.array());
        byte[] fv = new byte[] {'u', '=', '1'};
        b = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(0, fv.length));
        H3Writer.writePriorityUpdateRequest(b, 0, fv);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.priorityUpdatePushLength(0, fv.length));
        H3Writer.writePriorityUpdatePush(b, 0, fv);
        frames.add(b.array());
        byte[] enc = new byte[] {0, 0, (byte) 0xd1};
        b = ByteBuffer.allocate(H3Writer.pushPromiseLength(1, enc.length));
        H3Writer.writePushPromise(b, 1, enc);
        frames.add(b.array());
        frames.add(new byte[] {0x02, 0x00});
        frames.add(new byte[] {0x21, 0x00});
        for (int i = 0; i < frames.size(); i++) {
            Http3ClientHandler h = client();
            h.sendRequest(request("GET"), new Rec());
            feed(only(h), frames.get(i));
        }
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        s.frameError("bad");
        assertTrue(rec.events.contains("failed"));
        Http3ClientHandler h2 = client();
        Rec rec2 = new Rec();
        h2.sendRequest(request("GET"), rec2);
        H3ClientStream s2 = only(h2);
        s2.error(new IOException("lost"));
        assertTrue(rec2.events.contains("failed"));
        s2.onGoawayFailed(new IOException("again"));
        s2.disconnected();
    }

    @Test
    public void testQpackFailure() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        byte[] enc = new byte[] {(byte) 0x05, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(enc.length));
        H3Writer.writeHeaders(out, enc);
        feed(s, out.array());
        assertTrue(rec.events.contains("failed"));
    }

    @Test
    public void testResponseContentDecoding() throws Exception {
        Http3ClientHandler h = client();
        h.setDecodeResponseContentCoding(true);
        h.setSendAcceptEncodingHeader(true);
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        byte[] gz = H3ServerFlowTest.gzip("hello hello hello".getBytes("UTF-8"));
        feed(s, H3ServerFlowTest.concat(response("200", "content-encoding", "gzip"),
                H3ServerFlowTest.dataFrame(gz)));
        s.readFinished();
        assertEquals(17, rec.bodyBytes);
        assertFalse(rec.headers.contains("content-encoding"));

        Http3ClientHandler h2 = client();
        h2.setDecodeResponseContentCoding(true);
        Rec rec2 = new Rec();
        h2.sendRequest(request("GET"), rec2);
        H3ClientStream s2 = only(h2);
        feed(s2, H3ServerFlowTest.concat(response("200", "content-encoding", "gzip"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})));
        s2.readFinished();
        assertTrue(rec2.events.contains("failed"));

        Http3ClientHandler h3 = client();
        h3.setDecodeResponseContentCoding(false);
        h3.setSendAcceptEncodingHeader(false);
        Rec rec3 = new Rec();
        h3.sendRequest(request("GET"), rec3);
        H3ClientStream s3 = only(h3);
        feed(s3, H3ServerFlowTest.concat(response("200", "content-encoding", "gzip"),
                H3ServerFlowTest.dataFrame(new byte[] {1})));
        assertEquals(1, rec3.bodyBytes);
        assertTrue(rec3.headers.contains("content-encoding"));
    }

    @Test
    public void testRequestBodyVariants() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        long id = h.sendRequest(request("POST"), rec, false);
        h.sendRequestBody(id, ByteBuffer.wrap(new byte[] {1, 2}), false);
        h.sendRequestBody(id, ByteBuffer.wrap(new byte[] {3}), true);
        h.sendRequestBody(9999L, ByteBuffer.wrap(new byte[] {3}), true);
        H3ClientStream s = only(h);
        h.sendRequestBody(s, ByteBuffer.wrap(new byte[] {4}), true);

        Http3ClientHandler g = client();
        g.setEncodeRequestBodyContentCoding(true);
        assertTrue(g.isEncodeRequestBodyContentCoding());
        Rec rec2 = new Rec();
        long id2 = g.sendRequest(request("POST", "content-encoding", "gzip"), rec2, false);
        g.sendRequestBody(id2, ByteBuffer.wrap(new byte[500]), false);
        g.sendRequestBody(id2, ByteBuffer.allocate(0), true);
        H3ClientStream s2 = only(g);
        g.sendRequestBody(s2, ByteBuffer.wrap(new byte[] {1}), false);

        Http3ClientHandler k = client();
        Rec rec3 = new Rec();
        k.sendRequest(request("POST", "content-encoding", "gzip"), rec3, true);
    }

    @Test
    public void testQueuedBodyBeforeConnected() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        H3ClientStream cs = new H3ClientStream(h, new org.bluezoo.gumdrop.http.qpack.Decoder(4096), rec);
        cs.prepareRequest(request("POST"), false);
        h.sendRequestBody(cs, ByteBuffer.wrap(new byte[] {1, 2, 3}), true);
        assertEquals(1, cs.takePendingBody().size());
        assertTrue(cs.takePendingBodyFin());
        assertNotNull(cs.takePendingRequestHeaders());
        assertFalse(cs.takePendingRequestFin());
    }

    @Test
    public void testSettingsGoawayAndLifecycle() throws Exception {
        Http3ClientHandler h = client();
        assertFalse(h.isGoaway());
        h.setReadyCallback(new Runnable() {
            @Override
            public void run() {
            }
        });
        h.settingsReceived(new long[] {0x01, 100, 0x06, 100000, 0x08, 1});
        h.settingsReceived(new long[] {0x33, 5});
        h.settingsReceived(new long[] {0x33, 1});
        assertFalse(h.peerH3Datagram());
        assertEquals(H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE, h.getLocalMaxFieldSectionSize());
        List<Header> big = new ArrayList<Header>();
        big.add(new Header("x", "y"));
        assertFalse(h.exceedsPeerFieldSectionLimit(big));
        assertFalse(h.sendDatagram(0L, ByteBuffer.wrap(new byte[] {1})));
        assertFalse(h.sendDatagram(0L, null));
        h.priorityUpdateReceived(0L, "u=1");
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        Rec late = new Rec();
        h.sendRequest(request("GET"), late);
        final boolean[] observed = new boolean[1];
        Http3ClientHandler.goawayReceivedObserver = new Runnable() {
            @Override
            public void run() {
                observed[0] = true;
            }
        };
        try {
            h.goawayReceived(1L);
        } finally {
            Http3ClientHandler.goawayReceivedObserver = null;
        }
        assertTrue(observed[0]);
        assertTrue(h.isGoaway());
        Rec afterGoaway = new Rec();
        h.sendRequest(request("GET"), afterGoaway);
        assertTrue(afterGoaway.events.contains("failed"));
        h.close();
    }

    @Test
    public void testDeferredRequests() throws Exception {
        Http3ClientHandler h = client();
        final int[] ran = new int[1];
        h.deferUntilEstablished(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertFalse(h.isSafeToSendNow("POST"));
        assertTrue(h.isSafeToSendNow("GET"));
        h.runDeferredRequests();
        assertEquals(1, ran[0]);
        h.applyDefaultAcceptEncoding(new ArrayList<Header>());
        assertFalse(h.omitContentEncodingHeader(null));
    }

    @Test
    public void testExtendedConnectWebSocket() throws Exception {
        Http3ClientHandler h = client();
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        assertEquals(-1L, h.connectWebSocket("example.com", "/ws", "chat", null, ws));
        h.settingsReceived(new long[] {0x08, 1});
        H3ClientStream s = only(h);
        feed(s, response("200"));
        assertEquals(1, ws.openedCount);
        byte[] text = new byte[] {(byte) 0x81, 0x02, 'h', 'i'};
        feed(s, H3ServerFlowTest.dataFrame(text));
        assertEquals(1, ws.texts.size());
        ws.session.sendText("x");
        s.readFinished();
        assertEquals(1, ws.closeCodes.size());

        Http3ClientHandler h2 = client();
        RecordingWebSocketEventHandler ws2 = new RecordingWebSocketEventHandler();
        h2.settingsReceived(new long[] {0x08, 1});
        long id = h2.connectWebSocket("example.com", "/ws", null, null, ws2);
        assertTrue(id >= 0);
        feed(only(h2), response("403"));
        assertEquals(1, ws2.errors.size());

        Http3ClientHandler h3 = client();
        RecordingWebSocketEventHandler ws3 = new RecordingWebSocketEventHandler();
        h3.settingsReceived(new long[] {0x08, 0});
        assertEquals(-1L, h3.connectWebSocket("example.com", "/ws", null, null, ws3));
        assertEquals(1, ws3.errors.size());

        Http3ClientHandler h4 = client();
        RecordingWebSocketEventHandler ws4 = new RecordingWebSocketEventHandler();
        h4.goawayReceived(100L);
        assertEquals(-1L, h4.connectWebSocket("example.com", "/ws", null, null, ws4));
        assertEquals(1, ws4.errors.size());
    }

    static final class UdpHandler implements ConnectUdpEventHandler {
        int opened;
        int errors;
        int datagrams;
        int closed;

        @Override
        public void opened(ConnectUdpSession session) {
            opened++;
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            datagrams++;
        }

        @Override
        public void closed() {
            closed++;
        }

        @Override
        public void error(Throwable cause) {
            errors++;
        }
    }

    static final class IpHandler implements ConnectIpEventHandler {
        int opened;
        int errors;
        int packets;
        int closed;
        ConnectIpClientSession session;

        @Override
        public void opened(ConnectIpClientSession s) {
            opened++;
            session = s;
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            packets++;
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
        }

        @Override
        public void closed() {
            closed++;
        }

        @Override
        public void error(Throwable cause) {
            errors++;
        }
    }

    @Test
    public void testConnectUdp() throws Exception {
        Http3ClientHandler h = client();
        UdpHandler u = new UdpHandler();
        assertEquals(-1L, h.connectUdp("example.com", "10.0.0.1", 53, u));
        h.settingsReceived(new long[] {0x08, 1});
        H3ClientStream s = only(h);
        feed(s, response("200", "capsule-protocol", "?1"));
        assertEquals(1, u.opened);
        feed(s, H3ServerFlowTest.dataFrame(new byte[] {0x00, 0x04, 0x00, 1, 2, 3}));
        s.readFinished();

        Http3ClientHandler h2 = client();
        UdpHandler u2 = new UdpHandler();
        h2.settingsReceived(new long[] {0x08, 1});
        h2.connectUdp("example.com", "10.0.0.1", 53, u2);
        feed(only(h2), response("404"));
        assertEquals(1, u2.errors);

        Http3ClientHandler h3 = client();
        UdpHandler u3 = new UdpHandler();
        h3.settingsReceived(new long[] {0x08, 0});
        assertEquals(-1L, h3.connectUdp("example.com", "h", 1, u3));
        assertEquals(1, u3.errors);
        Http3ClientHandler h4 = client();
        UdpHandler u4 = new UdpHandler();
        h4.goawayReceived(100L);
        assertEquals(-1L, h4.connectUdp("example.com", "h", 1, u4));
        assertEquals(1, u4.errors);
    }

    @Test
    public void testConnectIp() throws Exception {
        Http3ClientHandler h = client();
        IpHandler i = new IpHandler();
        assertEquals(-1L, h.connectIp("example.com", "*", "*", i));
        h.settingsReceived(new long[] {0x08, 1});
        H3ClientStream s = only(h);
        feed(s, response("200", "capsule-protocol", "?1"));
        assertEquals(1, i.opened);
        s.readFinished();

        Http3ClientHandler h2 = client();
        IpHandler i2 = new IpHandler();
        h2.settingsReceived(new long[] {0x08, 1});
        h2.connectIp("example.com", "*", "*", i2);
        feed(only(h2), response("500"));
        assertEquals(1, i2.errors);

        Http3ClientHandler h3 = client();
        IpHandler i3 = new IpHandler();
        h3.settingsReceived(new long[] {0x08, 0});
        assertEquals(-1L, h3.connectIp("example.com", "*", "*", i3));
        assertEquals(1, i3.errors);
        Http3ClientHandler h4 = client();
        IpHandler i4 = new IpHandler();
        h4.goawayReceived(100L);
        assertEquals(-1L, h4.connectIp("example.com", "*", "*", i4));
        assertEquals(1, i4.errors);
    }

    @Test
    public void testCapsuleModeOnPlainRequest() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        rec.datagrams = true;
        h.sendRequest(request("CONNECT", ":protocol", "x"), rec, false);
        H3ClientStream s = only(h);
        feed(s, response("200", "capsule-protocol", "?1"));
        feed(s, H3ServerFlowTest.dataFrame(new byte[] {0x00, 0x02, 1, 2, 0x17, 0x01, 9}));
        assertEquals(1, rec.datagramCount);
        assertEquals(1, rec.capsuleCount);
        s.httpDatagramReceived(ByteBuffer.wrap(new byte[] {1}));
        assertEquals(2, rec.datagramCount);
        s.readFinished();

        Http3ClientHandler h2 = client();
        Rec rec2 = new Rec();
        h2.sendRequest(request("CONNECT", ":protocol", "x"), rec2, false);
        H3ClientStream s2 = only(h2);
        feed(s2, response("200", "capsule-protocol", "?1"));
        feed(s2, H3ServerFlowTest.dataFrame(new byte[] {0x00, 0x01, 1}));
        assertTrue(rec2.events.contains("failed"));

        Http3ClientHandler h3 = client();
        Rec rec3 = new Rec();
        h3.sendRequest(request("CONNECT", ":protocol", "x"), rec3, false);
        H3ClientStream s3 = only(h3);
        feed(s3, response("200", "capsule-protocol", "?1"));
        feed(s3, H3ServerFlowTest.dataFrame(new byte[] {0x17, 0x05, 1}));
        s3.readFinished();
        assertTrue(rec3.events.contains("failed"));

        Http3ClientHandler h4 = client();
        Rec rec4 = new Rec();
        h4.sendRequest(request("CONNECT", ":protocol", "x"), rec4, false);
        H3ClientStream s4 = only(h4);
        feed(s4, response("200", "capsule-protocol", "?1"));
        feed(s4, H3ServerFlowTest.dataFrame(new byte[] {(byte) 0xff}));
        s4.readFinished();
        assertTrue(rec4.events.contains("failed"));
        s4.httpDatagramReceived(ByteBuffer.wrap(new byte[] {1}));
    }

    @Test
    public void testStreamCloseHelpers() throws Exception {
        Http3ClientHandler h = client();
        Rec rec = new Rec();
        h.sendRequest(request("GET"), rec);
        H3ClientStream s = only(h);
        s.closeStream();
        s.closeStream();
        s.closeInboundResponseDecoder();
        s.closeRequestContentEncoder();
        assertTrue(s.isClosed());
        h.closeWithApplicationError(H3ErrorCode.H3_NO_ERROR, "done");
    }
}
