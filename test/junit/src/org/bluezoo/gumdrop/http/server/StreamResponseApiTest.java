/*
 * StreamResponseApiTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

/**
 * Exercises the application-facing response API of {@link Stream}
 * (RFC 9110 sections 9, 15, RFC 9113 section 8.4) against a hand-written
 * {@link MockHttpConnection}: state-machine misuse, informational
 * responses, trailers, push, cancellation, flow-control hooks and
 * telemetry spans.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamResponseApiTest {

    /** Records what the application handler was told. */
    private static class Events extends DefaultHttpRequestHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            log.add("headers");
        }

        @Override
        public void requestComplete(HttpResponseState state) {
            log.add("complete");
        }

        @Override
        public void failed(HttpResponseState state, Exception cause) {
            log.add("failed");
        }
    }

    private static class Env {
        final MockHttpConnection conn = new MockHttpConnection();
        final Events events = new Events();
        Stream stream;

        Env(HttpVersion version) {
            conn.version = version;
            conn.streamHandler = new HttpStreamHandler() {
                @Override
                public HttpRequestHandler openStream(HttpResponseState state) {
                    return events;
                }
            };
        }

        Env get() {
            stream = new Stream(conn, 1);
            if (conn.version == HttpVersion.HTTP_2_0) {
                stream.addHeader(new Header(":method", "GET"));
                stream.addHeader(new Header(":scheme", "https"));
                stream.addHeader(new Header(":authority", "h.test"));
                stream.addHeader(new Header(":path", "/r"));
            } else {
                stream.addHeader(new Header(":method", "GET"));
                stream.addHeader(new Header(":path", "/r"));
                stream.addHeader(new Header("Host", "h.test"));
            }
            stream.streamEndHeaders();
            return this;
        }
    }

    private static Env h2() {
        return new Env(HttpVersion.HTTP_2_0).get();
    }

    private static Env h1() {
        return new Env(HttpVersion.HTTP_1_1).get();
    }

    private static Headers status(String code) {
        Headers h = new Headers();
        h.add(":status", code);
        return h;
    }

    // ------------------------------------------------------------------
    // informational responses

    @Test
    public void testInformationalStatusMustBeOneHundredSeries() {
        Env e = h2();
        try {
            e.stream.sendInformational(200, new Headers());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(e.conn.statuses.isEmpty());
        }
        try {
            e.stream.sendInformational(99, new Headers());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(e.conn.statuses.isEmpty());
        }
    }

    @Test
    public void testInformationalIsSentBeforeTheFinalResponse() {
        Env e = h2();
        e.stream.sendInformational(103, new Headers());
        assertEquals(Integer.valueOf(103), e.conn.statuses.get(0));
        assertFalse(e.conn.headerEndStreams.get(0).booleanValue());
    }

    @Test
    public void testInformationalAfterResponseStartedIsIllegal() {
        Env e = h2();
        e.stream.headers(status("200"));
        e.stream.startResponseBody();
        try {
            e.stream.sendInformational(103, new Headers());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, e.conn.statuses.size());
        }
    }

    @Test
    public void testInformationalIsDroppedOnHttp10() {
        Env e = new Env(HttpVersion.HTTP_1_0).get();
        e.stream.sendInformational(103, new Headers());
        assertTrue(e.conn.statuses.isEmpty());
    }

    // ------------------------------------------------------------------
    // response state machine

    @Test
    public void testBodyCallsOutOfOrderAreIllegal() {
        Env e = h2();
        try {
            e.stream.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
            fail("body before start");
        } catch (IllegalStateException expected) {
            assertTrue(e.conn.bodyEndStreams.isEmpty());
        }
        try {
            e.stream.endResponseBody();
            fail("end before start");
        } catch (IllegalStateException expected) {
            assertTrue(e.conn.bodyEndStreams.isEmpty());
        }
        e.stream.headers(status("200"));
        e.stream.startResponseBody();
        try {
            e.stream.startResponseBody();
            fail("second start");
        } catch (IllegalStateException expected) {
            assertEquals(1, e.conn.statuses.size());
        }
    }

    @Test
    public void testTrailersFollowTheBodyAndEndTheStream() {
        Env e = h2();
        e.stream.headers(status("200"));
        e.stream.startResponseBody();
        e.stream.responseBodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.ISO_8859_1)));
        e.stream.endResponseBody();
        Headers trailers = new Headers();
        trailers.add("x-checksum", "42");
        e.stream.headers(trailers);
        e.stream.complete();
        int last = e.conn.sentHeaders.size() - 1;
        assertTrue(e.conn.headerEndStreams.get(last).booleanValue());
        assertEquals("42", e.conn.sentHeaders.get(last).getValue("x-checksum"));
        assertEquals("abc", e.conn.bodyString());
    }

    @Test
    public void testCompleteIsIdempotentAndHeadersAfterItAreRejected() {
        Env e = h2();
        e.stream.headers(status("204"));
        e.stream.complete();
        int sent = e.conn.statuses.size();
        e.stream.complete();
        assertEquals(sent, e.conn.statuses.size());
        try {
            e.stream.headers(new Headers());
            fail("headers after complete");
        } catch (IllegalStateException expected) {
            assertEquals(sent, e.conn.statuses.size());
        }
    }

    @Test
    public void testCompleteOnAnAlreadyClosedStreamSendsNothing() {
        Env e = h2();
        e.stream.streamClose();
        e.stream.complete();
        assertTrue(e.conn.statuses.isEmpty());
        assertTrue(e.conn.bodyEndStreams.isEmpty());
        e.stream.complete();
        assertTrue(e.conn.statuses.isEmpty());
    }

    @Test
    public void testCompleteWithoutHeadersSendsEmptyEndOfStreamData() {
        Env e = h2();
        e.stream.complete();
        assertEquals(1, e.conn.bodyEndStreams.size());
        assertTrue(e.conn.bodyEndStreams.get(0).booleanValue());
    }

    @Test
    public void testMissingStatusDefaultsTo200AndBadStatusTo500() {
        Env e = h2();
        Headers h = new Headers();
        h.add("x-a", "b");
        e.stream.headers(h);
        e.stream.complete();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
        Env f = h2();
        f.stream.headers(status("teapot"));
        f.stream.complete();
        assertEquals(Integer.valueOf(500), f.conn.statuses.get(0));
    }

    @Test
    public void testUnstartedBodyAfterWriteFailureReportsIllegalState() {
        Env e = h2();
        e.stream.headers(status("200"));
        e.stream.startResponseBody();
        e.stream.endResponseBody();
        try {
            e.stream.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
            fail("body after end");
        } catch (IllegalStateException expected) {
            assertEquals(1, e.conn.statuses.size());
        }
    }

    // ------------------------------------------------------------------
    // datagrams and capsules

    @Test
    public void testDatagramAndCapsuleGuards() {
        Env e = h2();
        assertFalse(e.stream.sendDatagram(null));
        assertFalse(e.stream.sendDatagram(ByteBuffer.wrap(new byte[] {1})));
        assertFalse(e.stream.sendCapsule(7L, null));
        e.stream.headers(status("200"));
        e.stream.complete();
        assertFalse(e.stream.sendCapsule(7L, ByteBuffer.wrap(new byte[] {1})));
    }

    @Test
    public void testCapsuleOpensTheBodyImplicitlyAndIsSent() {
        Env e = h2();
        e.stream.headers(status("200"));
        boolean sent = e.stream.sendCapsule(7L, ByteBuffer.wrap(new byte[] {1, 2}));
        assertTrue(sent);
        assertEquals(1, e.conn.statuses.size());
        assertTrue(e.conn.body.size() >= 3);
    }

    // ------------------------------------------------------------------
    // cancel, push

    @Test
    public void testCancelResetsAnH2StreamWithCancelCode() {
        Env e = h2();
        e.stream.cancel();
        assertEquals(Integer.valueOf(8), e.conn.rstCodes.get(0));
        e.stream.complete();
        assertTrue(e.conn.statuses.isEmpty());
    }

    @Test
    public void testCancelClosesAnHttp1Connection() {
        Env e = h1();
        e.stream.cancel();
        assertEquals(1, e.conn.nullSends);
        assertTrue(e.stream.isCloseConnection());
        assertTrue(e.conn.rstCodes.isEmpty());
    }

    private static Headers pushHeaders(boolean withPath) {
        Headers h = new Headers();
        h.add(":method", "GET");
        h.add(":scheme", "https");
        h.add(":authority", "h.test");
        if (withPath) {
            h.add(":path", "/pushed");
        }
        return h;
    }

    @Test
    public void testPushIsRefusedWhenNotPossible() {
        Env http1 = h1();
        assertFalse(http1.stream.pushPromise(pushHeaders(true)));
        Env off = h2();
        off.conn.enablePush = false;
        assertFalse(off.stream.pushPromise(pushHeaders(true)));
        Env noPath = h2();
        assertFalse(noPath.stream.pushPromise(pushHeaders(false)));
        assertEquals(0, noPath.conn.pushPromises);
        Env noStream = h2();
        noStream.conn.pushedStreamCreated = false;
        assertFalse(noStream.stream.pushPromise(pushHeaders(true)));
        Env failing = h2();
        failing.conn.failSendPush = true;
        assertFalse(failing.stream.pushPromise(pushHeaders(true)));
        Env encoding = h2();
        encoding.conn.failEncodeHeaders = true;
        assertFalse(encoding.stream.pushPromise(pushHeaders(true)));
    }

    @Test
    public void testPushPromiseCreatesAReservedStream() {
        Env e = h2();
        assertTrue(e.stream.pushPromise(pushHeaders(true)));
        assertEquals(1, e.conn.pushPromises);
        assertEquals(1, e.conn.pushedStreams.size());
        assertFalse(e.conn.pushedStreams.get(0).isActive());
    }

    // ------------------------------------------------------------------
    // flow-control hooks and accessors

    @Test
    public void testWritableCallbackIsOneShot() {
        Env e = h2();
        final int[] runs = new int[1];
        Runnable cb = new Runnable() {
            @Override
            public void run() {
                runs[0]++;
            }
        };
        e.stream.onWritable(cb);
        Runnable dispatcher = e.conn.writable.get(Integer.valueOf(1));
        assertNotNull(dispatcher);
        dispatcher.run();
        assertEquals(1, runs[0]);
        assertNull(e.conn.writable.get(Integer.valueOf(1)));
        e.stream.onWritable(cb);
        e.stream.onWritable(null);
        assertNull(e.conn.writable.get(Integer.valueOf(1)));
    }

    @Test
    public void testPauseResumeAndPendingBytesDelegateToTheConnection() {
        Env e = h2();
        e.conn.pendingBytes = 77;
        e.stream.pauseRequestBody();
        e.stream.resumeRequestBody();
        assertEquals(1, e.conn.pausedCount);
        assertEquals(1, e.conn.resumedCount);
        assertEquals(77, e.stream.pendingResponseBytes());
    }

    @Test
    public void testConnectionAccessors() {
        Env e = h2();
        assertEquals("https", e.stream.getScheme());
        assertEquals(HttpVersion.HTTP_2_0, e.stream.getVersion());
        assertEquals("1", e.stream.getProtocolConnectionId());
        assertFalse(e.stream.getConnectionId().isEmpty());
        assertTrue(e.stream.isSecure());
        assertNotNull(e.stream.getRemoteAddress());
        assertNotNull(e.stream.getLocalAddress());
        assertSame(e.conn.loop, e.stream.getSelectorLoop());
        assertNull(e.stream.scheduleTimer(10L, new Runnable() {
            @Override
            public void run() {
            }
        }));
        assertNull(e.stream.getPrincipal());
        assertNull(e.stream.getTrace());
        Env one = h1();
        assertEquals("", one.stream.getProtocolConnectionId());
    }

    @Test
    public void testSecurityInfoIsOnlyExposedOnSecureConnections() {
        Env e = h2();
        SecurityInfo info = new NullInfo();
        e.conn.securityInfo = info;
        assertSame(info, e.stream.getSecurityInfo());
        Env plain = h2();
        plain.conn.secure = false;
        assertNotNull(plain.stream.getSecurityInfo());
        assertNull(plain.stream.getSecurityInfo().getProtocol());
    }

    private static class NullInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getCipherSuite() { return null; }
        @Override public int getKeySize() { return 0; }
        @Override public Certificate[] getPeerCertificates() { return null; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return null; }
        @Override public long getHandshakeDurationMs() { return 0; }
        @Override public boolean isSessionResumed() { return false; }
    }

    @Test
    public void testExecuteRunsOnTheConnectionLoop() {
        Env e = h2();
        final int[] ran = new int[1];
        e.stream.execute(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, ran[0]);
    }

    // ------------------------------------------------------------------
    // telemetry

    private static TelemetryConfig tracing() {
        TelemetryConfig config = new TelemetryConfig();
        config.setTracesEnabled(true);
        return config;
    }

    private static Env traced(String traceparent, boolean preTrace, String status) {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.conn.telemetryEnabled = true;
        e.conn.telemetryConfig = tracing();
        if (preTrace) {
            e.conn.trace = e.conn.telemetryConfig.createTrace("pre",
                    SpanKind.SERVER);
        }
        e.stream = new Stream(e.conn, 1);
        e.stream.addHeader(new Header(":method", "GET"));
        e.stream.addHeader(new Header(":scheme", "https"));
        e.stream.addHeader(new Header(":authority", "h.test"));
        e.stream.addHeader(new Header(":path", "/t"));
        e.stream.addHeader(new Header("host", "h.test"));
        e.stream.addHeader(new Header("user-agent", "probe/1"));
        if (traceparent != null) {
            e.stream.addHeader(new Header("traceparent", traceparent));
        }
        e.stream.streamEndHeaders();
        if (status != null) {
            e.stream.headers(status(status));
            e.stream.complete();
        }
        return e;
    }

    @Test
    public void testSpanIsStartedAndEndedWithOkStatus() {
        Env e = traced(null, false, "200");
        assertNotNull(e.conn.trace);
        String tp = e.conn.sentHeaders.get(0).getValue("traceparent");
        assertNotNull(tp);
    }

    @Test
    public void testSpanContinuesAnIncomingTraceparent() {
        Env e = traced("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", false, "200");
        String tp = e.conn.sentHeaders.get(0).getValue("traceparent");
        assertTrue(tp, tp.startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));
    }

    @Test
    public void testSpanReusesTheConnectionTrace() {
        Env e = traced(null, true, "200");
        Trace t = e.conn.trace;
        assertNotNull(t);
        String tp = e.conn.sentHeaders.get(0).getValue("traceparent");
        assertNotNull(tp);
    }

    @Test
    public void testClientAndServerErrorsMarkTheSpanFailed() {
        Env client = traced(null, false, "404");
        assertEquals(Integer.valueOf(404), client.conn.statuses.get(0));
        Env server = traced(null, false, "500");
        assertEquals(Integer.valueOf(500), server.conn.statuses.get(0));
        Env odd = traced(null, false, "499");
        assertEquals(Integer.valueOf(499), odd.conn.statuses.get(0));
    }

    @Test
    public void testClosingAnOpenSpanNormallyOrAbnormally() {
        Env normal = traced(null, false, null);
        normal.stream.streamClose(true);
        assertTrue(normal.stream.isClosed());
        Env abnormal = traced(null, false, null);
        abnormal.stream.streamAbort(new IOException("gone"));
        assertTrue(abnormal.stream.isClosed());
        assertTrue(abnormal.events.log.contains("failed"));
    }

    @Test
    public void testMetricsRecordRequestStartAndCompletion() {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.conn.metrics = new HttpServerMetrics(tracing());
        e.get();
        e.stream.headers(status("200"));
        e.stream.complete();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
    }
}
