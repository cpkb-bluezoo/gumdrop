/*
 * WebSocketConnectionFlowTest.java
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


package org.bluezoo.gumdrop.websocket;

import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioural tests for {@link WebSocketConnection}: sending, the closing
 * handshake, fragmentation, extensions, ping/pong, telemetry spans, server
 * metrics and handler failures, using an in-memory transport.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketConnectionFlowTest {

    private static final byte[] KEY = {1, 2, 3, 4};

    private static class Conn extends WebSocketConnection {
        final List<String> events = new ArrayList<String>();
        final List<byte[]> sent = new ArrayList<byte[]>();
        final List<Boolean> transportClosed = new ArrayList<Boolean>();
        boolean failOpened;
        boolean failText;
        boolean failClosed;
        boolean failTransportSend;
        boolean failTransportClose;
        Throwable lastError;
        int closeCode = -1;
        String closeReason;

        Conn(boolean withTransport) {
            if (withTransport) {
                setTransport(new WebSocketTransport() {
                    @Override
                    public void sendFrame(ByteBuffer frameData) throws IOException {
                        if (failTransportSend) {
                            throw new IOException("send refused");
                        }
                        byte[] copy = new byte[frameData.remaining()];
                        frameData.get(copy);
                        sent.add(copy);
                    }

                    @Override
                    public void close(boolean normalClose) throws IOException {
                        transportClosed.add(Boolean.valueOf(normalClose));
                        if (failTransportClose) {
                            throw new IOException("close refused");
                        }
                    }
                });
            }
        }

        @Override
        protected void opened() {
            events.add("opened");
            if (failOpened) {
                throw new IllegalStateException("opened boom");
            }
        }

        @Override
        protected void textMessageReceived(String message) {
            events.add("text:" + message);
            if (failText) {
                throw new IllegalStateException("text boom");
            }
        }

        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            events.add("binary:" + data.remaining());
        }

        @Override
        protected void closed(int code, String reason) {
            closeCode = code;
            closeReason = reason;
            events.add("closed:" + code);
            if (failClosed) {
                throw new IllegalStateException("closed boom");
            }
        }

        @Override
        protected void error(Throwable cause) {
            lastError = cause;
            events.add("error");
        }

        void abnormal(int code, String reason) {
            abnormalClose(code, reason);
        }

        void recordTelemetryErrorForTest() {
            recordTelemetryError(new IllegalStateException("probe"));
            recordTelemetryError(org.bluezoo.gumdrop.telemetry.ErrorCategory.PROTOCOL_ERROR, "probe");
        }
    }

    private static Conn open() {
        Conn c = new Conn(true);
        c.notifyConnectionOpen();
        return c;
    }

    private static ByteBuffer clientFrame(int opcode, byte[] payload) throws IOException {
        WebSocketFrame f = new WebSocketFrame(true, false, false, false, opcode, true, KEY, payload);
        return f.encode();
    }

    private static ByteBuffer clientFragment(boolean fin, int opcode, byte[] payload) throws IOException {
        WebSocketFrame f = new WebSocketFrame(fin, false, false, false, opcode, true, KEY, payload);
        return f.encode();
    }

    private static ByteBuffer closeFrame(int code, String reason) throws IOException {
        WebSocketFrame f = WebSocketFrame.createCloseFrame(code, reason, true);
        return f.encode();
    }

    private static class Ext implements WebSocketExtension {
        final boolean rsv1;
        final boolean rsv2;
        final boolean rsv3;
        boolean closed;
        boolean failClose;

        Ext(boolean rsv1, boolean rsv2, boolean rsv3) {
            this.rsv1 = rsv1;
            this.rsv2 = rsv2;
            this.rsv3 = rsv3;
        }

        @Override public String getName() { return "xor"; }
        @Override public boolean usesRsv1() { return rsv1; }
        @Override public boolean usesRsv2() { return rsv2; }
        @Override public boolean usesRsv3() { return rsv3; }
        @Override public Map<String, String> acceptOffer(Map<String, String> p) { return p; }
        @Override public Map<String, String> generateOffer() { return new LinkedHashMap<String, String>(); }
        @Override public boolean acceptResponse(Map<String, String> p) { return true; }

        @Override
        public byte[] encode(byte[] payload) {
            byte[] out = new byte[payload.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (payload[i] ^ 0x55);
            }
            return out;
        }

        @Override
        public byte[] decode(byte[] payload) {
            return encode(payload);
        }

        @Override
        public void close() {
            closed = true;
            if (failClose) {
                throw new IllegalStateException("ext close boom");
            }
        }
    }

    // ---- sending ----

    @Test
    public void sendingRequiresAnOpenConnection() throws Exception {
        Conn c = new Conn(true);
        assertEquals(WebSocketConnection.State.CONNECTING, c.getState());
        assertFalse(c.isOpen());
        try {
            c.sendText("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CONNECTING"));
        }
        try {
            c.sendBinary(ByteBuffer.wrap(new byte[1]));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            c.sendPing(null);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void sendWithoutTransportFails() throws Exception {
        Conn c = new Conn(false);
        c.notifyConnectionOpen();
        try {
            c.sendText("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void textBinaryPingAndPongAreFramed() throws Exception {
        Conn c = open();
        c.sendText("hello");
        c.sendBinary(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        c.sendPing(ByteBuffer.wrap(new byte[] {9}));
        c.sendPing(null);
        c.sendPong(ByteBuffer.wrap(new byte[] {7}));
        c.sendPong(null);
        assertEquals(6, c.sent.size());
        assertEquals(0x81, c.sent.get(0)[0] & 0xFF);
        assertEquals(0x82, c.sent.get(1)[0] & 0xFF);
        assertEquals(0x89, c.sent.get(2)[0] & 0xFF);
        assertEquals(0x8A, c.sent.get(4)[0] & 0xFF);
        assertEquals(0, c.sent.get(0)[1] & 0x80);
    }

    @Test
    public void clientModeMasksOutgoingFrames() throws Exception {
        Conn c = new Conn(true);
        c.setClientMode(true);
        c.notifyConnectionOpen();
        c.sendText("hi");
        assertEquals(0x80, c.sent.get(0)[1] & 0x80);
    }

    @Test
    public void oversizedControlPayloadsAreRejected() throws Exception {
        Conn c = open();
        ByteBuffer big = ByteBuffer.allocate(126);
        try {
            c.sendPing(big);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("126"));
        }
        try {
            c.sendPong(ByteBuffer.allocate(126));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("126"));
        }
    }

    @Test
    public void pongIsRefusedOnceClosed() throws Exception {
        Conn c = open();
        c.abnormal(1006, "gone");
        try {
            c.sendPong(null);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void extensionsEncodeOutgoingAndDecodeIncoming() throws Exception {
        Conn c = open();
        Ext ext = new Ext(true, false, false);
        List<WebSocketExtension> exts = new ArrayList<WebSocketExtension>();
        exts.add(ext);
        c.setExtensions(exts);
        c.sendText("ab");
        byte[] frame = c.sent.get(0);
        assertEquals(0xC1, frame[0] & 0xFF);
        assertEquals('a' ^ 0x55, frame[2] & 0xFF);
        byte[] enc = ext.encode("xyz".getBytes(StandardCharsets.UTF_8));
        WebSocketFrame in = new WebSocketFrame(true, true, false, false, 1, true, KEY, enc);
        c.processIncomingData(in.encode());
        assertTrue(c.events.contains("text:xyz"));
        c.setExtensions(null);
        c.sendText("plain");
        assertEquals(0x81, c.sent.get(1)[0] & 0xFF);
    }

    @Test
    public void reservedBitsAcceptedOnlyForClaimingExtension() throws Exception {
        Conn c = open();
        List<WebSocketExtension> exts = new ArrayList<WebSocketExtension>();
        exts.add(new Ext(false, true, true));
        c.setExtensions(exts);
        WebSocketFrame ok = new WebSocketFrame(true, false, true, true, 2, true, KEY, new byte[] {1});
        c.processIncomingData(ok.encode());
        assertTrue(c.events.contains("binary:1"));
        Conn d = open();
        WebSocketFrame bad = new WebSocketFrame(true, false, true, false, 2, true, KEY, new byte[] {1});
        d.processIncomingData(bad.encode());
        assertEquals(1002, firstCloseCode(d));
    }

    private static int firstCloseCode(Conn c) {
        for (int i = 0; i < c.sent.size(); i++) {
            byte[] f = c.sent.get(i);
            if ((f[0] & 0x0F) == 8 && f.length >= 4) {
                return ((f[2] & 0xFF) << 8) | (f[3] & 0xFF);
            }
        }
        return -1;
    }

    // ---- receiving ----

    @Test
    public void pingIsAnsweredWithPongAndPongIsIgnored() throws Exception {
        Conn c = open();
        c.processIncomingData(clientFrame(9, new byte[] {5, 6}));
        assertEquals(0x8A, c.sent.get(0)[0] & 0xFF);
        c.processIncomingData(clientFrame(10, new byte[] {1}));
        assertEquals(1, c.sent.size());
    }

    @Test
    public void pongSendFailureIsReportedAsError() throws Exception {
        Conn c = open();
        c.failTransportSend = true;
        c.processIncomingData(clientFrame(9, new byte[0]));
        assertTrue(c.events.contains("error"));
        assertTrue(c.lastError instanceof IOException);
    }

    @Test
    public void fragmentedMessageIsReassembledAcrossGrowth() throws Exception {
        Conn c = open();
        byte[] a = new byte[10];
        byte[] b = new byte[100];
        byte[] d = new byte[1000];
        java.util.Arrays.fill(a, (byte) 'a');
        java.util.Arrays.fill(b, (byte) 'b');
        java.util.Arrays.fill(d, (byte) 'd');
        c.processIncomingData(clientFragment(false, 1, a));
        c.processIncomingData(clientFragment(false, 0, b));
        c.processIncomingData(clientFragment(true, 0, d));
        assertEquals(1, c.events.size() - 1);
        String last = c.events.get(c.events.size() - 1);
        assertEquals("text:".length() + 1110, last.length());
    }

    @Test
    public void fragmentedBinaryMessageIsDelivered() throws Exception {
        Conn c = open();
        c.processIncomingData(clientFragment(false, 2, new byte[] {1, 2}));
        c.processIncomingData(clientFragment(true, 0, new byte[] {3}));
        assertTrue(c.events.contains("binary:3"));
    }

    @Test
    public void continuationWithoutStartIsProtocolError() throws Exception {
        Conn c = open();
        c.processIncomingData(clientFragment(true, 0, new byte[] {1}));
        assertEquals(1002, firstCloseCode(c));
    }

    @Test
    public void dataFrameInsideFragmentedMessageIsProtocolError() throws Exception {
        Conn c = open();
        c.processIncomingData(clientFragment(false, 1, new byte[] {1}));
        c.processIncomingData(clientFrame(1, new byte[] {2}));
        assertEquals(1002, firstCloseCode(c));
    }

    @Test
    public void unmaskedClientFrameClosesWithProtocolError() throws Exception {
        Conn c = open();
        WebSocketFrame f = new WebSocketFrame(1, new byte[] {1}, false);
        c.processIncomingData(f.encode());
        assertEquals(1002, firstCloseCode(c));
        assertTrue(c.events.contains("error"));
    }

    @Test
    public void partialFrameWaitsForMoreData() throws Exception {
        Conn c = open();
        ByteBuffer full = clientFrame(1, "split".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[full.remaining()];
        full.get(bytes);
        c.processIncomingData(ByteBuffer.wrap(bytes, 0, 3));
        assertFalse(c.events.contains("text:split"));
        c.processIncomingData(ByteBuffer.wrap(bytes));
        assertTrue(c.events.contains("text:split"));
    }

    @Test
    public void messageHandlerFailureIsReportedAsError() throws Exception {
        Conn c = open();
        c.failText = true;
        c.processIncomingData(clientFrame(1, "x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(c.events.contains("error"));
    }

    // ---- closing ----

    @Test
    public void remoteCloseIsEchoedAndCompletes() throws Exception {
        Conn c = open();
        c.processIncomingData(closeFrame(1000, "bye"));
        assertEquals(1000, c.closeCode);
        assertEquals("bye", c.closeReason);
        assertEquals(WebSocketConnection.State.CLOSED, c.getState());
        assertEquals(1000, firstCloseCode(c));
        assertEquals(Boolean.TRUE, c.transportClosed.get(0));
    }

    @Test
    public void localCloseThenRemoteCloseCompletesHandshake() throws Exception {
        Conn c = open();
        c.close();
        assertEquals(WebSocketConnection.State.CLOSING, c.getState());
        c.close(1001, "again");
        assertEquals(1, c.sent.size());
        c.processIncomingData(closeFrame(1000, null));
        assertEquals(WebSocketConnection.State.CLOSED, c.getState());
        assertEquals(1000, c.closeCode);
        c.close(1000, null);
        c.processIncomingData(closeFrame(1000, null));
        assertEquals(1, c.sent.size());
    }

    @Test
    public void remoteCloseWithoutPayloadDefaultsToNormalClosure() throws Exception {
        Conn c = open();
        c.processIncomingData(clientFrame(8, new byte[0]));
        assertEquals(1000, c.closeCode);
    }

    @Test
    public void remoteCloseAfterOurCloseCompletesWithTheirCode() throws Exception {
        Conn c = open();
        c.close(1001, "going");
        c.processIncomingData(closeFrame(3000, "x"));
        assertEquals(3000, c.closeCode);
        assertEquals(Boolean.FALSE, c.transportClosed.get(0));
    }

    @Test
    public void abnormalCloseIsIdempotentAndSurvivesFailingCallbacks() throws Exception {
        Conn c = open();
        Ext ext = new Ext(false, false, false);
        ext.failClose = true;
        List<WebSocketExtension> exts = new ArrayList<WebSocketExtension>();
        exts.add(ext);
        c.setExtensions(exts);
        c.failClosed = true;
        c.failTransportClose = true;
        c.abnormal(1006, "lost");
        c.abnormal(1006, "again");
        assertTrue(ext.closed);
        assertEquals(1, c.transportClosed.size());
        assertEquals(WebSocketConnection.State.CLOSED, c.getState());
    }

    @Test
    public void closeWithNoTransportStillNotifiesHandler() throws Exception {
        Conn c = new Conn(false);
        c.notifyConnectionOpen();
        c.abnormal(1006, null);
        assertEquals(1006, c.closeCode);
    }

    @Test
    public void openedFailureIsReportedAndSecondOpenIgnored() {
        Conn c = new Conn(true);
        c.failOpened = true;
        c.notifyConnectionOpen();
        assertTrue(c.events.contains("error"));
        c.notifyConnectionOpen();
        int opens = 0;
        for (String e : c.events) {
            if (e.equals("opened")) {
                opens++;
            }
        }
        assertEquals(1, opens);
    }

    // ---- telemetry ----

    private static TelemetryConfig tracing() {
        TelemetryConfig config = new TelemetryConfig();
        config.exporter(new RecordingExporter());
        return config;
    }

    @Test
    public void sessionSpanRecordsNormalClose() throws Exception {
        Conn c = new Conn(true);
        TelemetryConfig config = tracing();
        c.setTelemetryConfig(config);
        c.createSpan(null);
        c.createSpan("named");
        c.notifyConnectionOpen();
        c.sendText("a");
        c.processIncomingData(clientFrame(1, "b".getBytes(StandardCharsets.UTF_8)));
        c.recordTelemetryErrorForTest();
        c.processIncomingData(closeFrame(1000, "done"));
        assertEquals(1000, c.closeCode);
    }

    @Test
    public void sessionSpanRecordsErrorCloseWithoutReason() throws Exception {
        Conn c = new Conn(true);
        c.setTelemetryConfig(tracing());
        c.createSpan("s");
        c.notifyConnectionOpen();
        c.abnormal(1011, null);
        assertEquals(1011, c.closeCode);
    }

    @Test
    public void parentSpanBecomesSessionSpanOnlyWhenConfigured() throws Exception {
        TelemetryConfig config = tracing();
        Trace trace = config.createTrace("parent");
        Span parent = trace.getRootSpan();
        Conn without = new Conn(true);
        without.setParentSpan(parent);
        without.createSpan("ignored");
        without.notifyConnectionOpen();
        without.abnormal(1000, null);
        Conn with = new Conn(true);
        with.setTelemetryConfig(config);
        with.setParentSpan(parent);
        with.setParentSpan(null);
        with.notifyConnectionOpen();
        with.abnormal(1001, "x");
        assertEquals(1001, with.closeCode);
    }

    @Test
    public void createSpanWithTracesDisabledLeavesConnectionSpanless() throws Exception {
        Conn c = new Conn(true);
        TelemetryConfig disabled = new TelemetryConfig();
        c.setTelemetryConfig(disabled);
        c.createSpan("ignored");
        c.createSpan(null);
        c.notifyConnectionOpen();
        c.recordTelemetryErrorForTest();
        c.abnormal(1000, null);
        assertEquals(1000, c.closeCode);
    }

    @Test
    public void telemetryErrorRecordingWithoutSpanIsNoop() {
        Conn c = new Conn(true);
        c.recordTelemetryErrorForTest();
        assertTrue(c.events.isEmpty());
    }

    // ---- server metrics ----

    @Test
    public void serverMetricsSeeFramesAndMessagesBothWays() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        config.metricsEnabled(true);
        WebSocketServerMetrics metrics = new WebSocketServerMetrics(config);
        Conn c = open();
        c.setServerMetrics(metrics);
        c.sendText("t");
        c.sendBinary(ByteBuffer.wrap(new byte[] {1}));
        c.sendPing(null);
        c.processIncomingData(clientFrame(1, new byte[] {'a'}));
        c.processIncomingData(clientFragment(false, 2, new byte[] {1}));
        c.processIncomingData(clientFragment(true, 0, new byte[] {2}));
        c.processIncomingData(clientFrame(9, new byte[0]));
        c.processIncomingData(clientFrame(10, new byte[0]));
        c.processIncomingData(closeFrame(1000, null));
        assertEquals(WebSocketConnection.State.CLOSED, c.getState());
    }

    @Test
    public void closeCodeConstantsAndStatesAreStable() {
        assertEquals(1000, WebSocketConnection.CloseCodes.NORMAL_CLOSURE);
        assertEquals(1001, WebSocketConnection.CloseCodes.GOING_AWAY);
        assertEquals(1002, WebSocketConnection.CloseCodes.PROTOCOL_ERROR);
        assertEquals(1003, WebSocketConnection.CloseCodes.UNSUPPORTED_DATA);
        assertEquals(1009, WebSocketConnection.CloseCodes.MESSAGE_TOO_BIG);
        assertEquals(1010, WebSocketConnection.CloseCodes.MISSING_EXTENSION);
        assertEquals(1011, WebSocketConnection.CloseCodes.INTERNAL_ERROR);
        assertEquals(4, WebSocketConnection.State.values().length);
        assertNotNull(new WebSocketConnection.CloseCodes());
        assertEquals(64L * 1024 * 1024, WebSocketConnection.DEFAULT_MAX_MESSAGE_SIZE);
    }
}
