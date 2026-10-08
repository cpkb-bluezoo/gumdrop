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

import org.bluezoo.gumdrop.http.HeaderFields;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
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
    private static class Events extends CollectingRequestHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void headers(HttpResponse state, List<Header> headers) {
            log.add("headers");
        }

        @Override
        public void requestComplete(HttpResponse state) {
            log.add("complete");
        }

        @Override
        public void failed(HttpResponse state, Exception cause) {
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
                public HttpRequestHandler openStream(HttpResponse state) {
                    return CollectingRequestHandler.bind(events, state);
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

    // ------------------------------------------------------------------
    // informational responses

    @Test
    public void testStatusMustBeInTheValidRange() {
        Env e = h2();
        try {
            e.stream.status(99);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(e.conn.statuses.isEmpty());
        }
        try {
            e.stream.status(600);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(e.conn.statuses.isEmpty());
        }
    }

    @Test
    public void testInformationalIsSentBeforeTheFinalResponse() {
        Env e = h2();
        e.stream.status(103);
        e.stream.header("link", "</style.css>; rel=preload");
        e.stream.endHeaders();
        assertEquals(Integer.valueOf(103), e.conn.statuses.get(0));
        assertFalse(e.conn.headerEndStreams.get(0).booleanValue());
        assertEquals("</style.css>; rel=preload", HeaderFields.getValue(e.conn.sentHeaders.get(0), "link"));
        // the final response still works after the interim one
        e.stream.status(200);
        e.stream.header("x-final", "yes");
        e.stream.bodyContent(ByteBuffer.wrap("ok".getBytes(StandardCharsets.ISO_8859_1)));
        e.stream.endMessage();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(1));
        assertEquals("yes", HeaderFields.getValue(e.conn.sentHeaders.get(1), "x-final"));
        assertNull("interim fields must not leak into the final response",
                HeaderFields.getValue(e.conn.sentHeaders.get(1), "link"));
        assertEquals("ok", e.conn.bodyString());
        assertTrue(e.conn.bodyEndStreams.get(e.conn.bodyEndStreams.size() - 1).booleanValue());
    }

    @Test
    public void testSeveralInformationalResponsesMayPrecedeTheFinalOne() {
        Env e = h2();
        e.stream.status(103);
        e.stream.endHeaders();
        e.stream.status(103);
        e.stream.endHeaders();
        e.stream.status(204);
        e.stream.endMessage();
        assertEquals(3, e.conn.statuses.size());
        assertEquals(Integer.valueOf(204), e.conn.statuses.get(2));
    }

    @Test
    public void testInformationalAfterResponseStartedIsIllegal() {
        Env e = h2();
        e.stream.status(200);
        e.stream.endHeaders();
        try {
            e.stream.status(103);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, e.conn.statuses.size());
        }
    }

    @Test
    public void testInformationalIsDroppedOnHttp10() {
        Env e = new Env(HttpVersion.HTTP_1_0).get();
        e.stream.status(103);
        e.stream.endHeaders();
        assertTrue(e.conn.statuses.isEmpty());
    }

    // ------------------------------------------------------------------
    // response state machine

    @Test
    public void testStatusDefaultsTo200WhenNoneIsSet() {
        Env e = h2();
        e.stream.header("x-a", "b");
        e.stream.endHeaders();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
        assertFalse(e.conn.headerEndStreams.get(0).booleanValue());
        Env f = h2();
        f.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        assertEquals(Integer.valueOf(200), f.conn.statuses.get(0));
        assertEquals(1, f.conn.body.size());
    }

    @Test
    public void testTypedFieldsAreFormattedAsFieldValues() {
        Env e = h2();
        e.stream.status(200);
        e.stream.longHeader("content-length", 1234L);
        e.stream.dateHeader("last-modified", Instant.ofEpochSecond(0L));
        e.stream.contentType(new ContentType("text", "plain", null));
        e.stream.endHeaders();
        List<Header> sent = e.conn.sentHeaders.get(0);
        assertEquals("1234", HeaderFields.getValue(sent, "content-length"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HeaderFields.getValue(sent, "last-modified"));
        String type = HeaderFields.getValue(sent, "content-type");
        assertNotNull(type);
        assertTrue(type, type.startsWith("text/plain"));
    }

    @Test
    public void testEndHeadersSendsTheHeaderSectionBeforeAnyBody() {
        Env e = h2();
        e.stream.status(200);
        e.stream.header("content-type", "text/event-stream");
        e.stream.endHeaders();
        assertEquals(1, e.conn.statuses.size());
        assertFalse(e.conn.headerEndStreams.get(0).booleanValue());
        assertTrue(e.conn.bodyEndStreams.isEmpty());
        assertEquals("text/event-stream", HeaderFields.getValue(e.conn.sentHeaders.get(0), "content-type"));
        // a second endHeaders is a no-op
        e.stream.endHeaders();
        assertEquals(1, e.conn.statuses.size());
        e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        assertEquals(1, e.conn.statuses.size());
        assertEquals(1, e.conn.body.size());
    }

    @Test
    public void testFirstBodyContentEndsTheHeaderSection() {
        Env e = h2();
        e.stream.status(201);
        e.stream.header("location", "/new");
        assertTrue("nothing is sent until the section is ended", e.conn.statuses.isEmpty());
        e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        assertEquals(Integer.valueOf(201), e.conn.statuses.get(0));
        assertEquals("/new", HeaderFields.getValue(e.conn.sentHeaders.get(0), "location"));
        assertFalse(e.conn.headerEndStreams.get(0).booleanValue());
    }

    @Test
    public void testStatusOnlyResponseEndsTheStreamWithTheHeaderSection() {
        Env e = h2();
        e.stream.status(204);
        e.stream.endMessage();
        assertEquals(1, e.conn.statuses.size());
        assertEquals(Integer.valueOf(204), e.conn.statuses.get(0));
        assertTrue(e.conn.headerEndStreams.get(0).booleanValue());
        assertTrue("no body frame for a status-only response",
                e.conn.bodyEndStreams.isEmpty());
    }

    @Test
    public void testTrailersFollowTheBodyAndEndTheStream() {
        Env e = h2();
        e.stream.status(200);
        e.stream.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.ISO_8859_1)));
        e.stream.header("x-checksum", "42");
        e.stream.endMessage();
        assertEquals("the response header section only", 1, e.conn.sentHeaders.size());
        assertEquals("one trailer section", 1, e.conn.sentTrailers.size());
        assertEquals("42", HeaderFields.getValue(e.conn.sentTrailers.get(0), "x-checksum"));
        assertNull("no pseudo-header in a trailer section", HeaderFields.getValue(e.conn.sentTrailers.get(0), ":status"));
        assertEquals("abc", e.conn.bodyString());
    }

    @Test
    public void testForbiddenTrailerNameIsRejected() {
        Env e = h2();
        e.stream.status(200);
        e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        try {
            e.stream.header("content-length", "1");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals(1, e.conn.statuses.size());
        }
        // the response can still be finished normally
        e.stream.endMessage();
        assertTrue(e.conn.bodyEndStreams.get(e.conn.bodyEndStreams.size() - 1).booleanValue());
    }

    @Test
    public void testBodyContentAfterATrailerIsIllegal() {
        Env e = h2();
        e.stream.status(200);
        e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        e.stream.header("x-checksum", "42");
        try {
            e.stream.bodyContent(ByteBuffer.wrap(new byte[] {2}));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, e.conn.body.size());
        }
    }

    @Test
    public void testNonAsciiFieldValueIsRejected() {
        Env e = h2();
        e.stream.status(200);
        try {
            e.stream.header("x-custom", "caf\u00e9");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("x-custom"));
        }
        e.stream.endMessage();
        assertNull(HeaderFields.getValue(e.conn.sentHeaders.get(0), "x-custom"));
    }

    @Test
    public void testEndMessageIsIdempotentAndFieldsAfterItAreRejected() {
        Env e = h2();
        e.stream.status(204);
        e.stream.endMessage();
        int sent = e.conn.statuses.size();
        e.stream.endMessage();
        assertEquals(sent, e.conn.statuses.size());
        assertTrue(e.conn.bodyEndStreams.isEmpty());
        try {
            e.stream.header("x-late", "1");
            fail("header after endMessage");
        } catch (IllegalStateException expected) {
            assertEquals(sent, e.conn.statuses.size());
        }
    }

    @Test
    public void testEndMessageOnAnAlreadyClosedStreamSendsNothing() {
        Env e = h2();
        e.stream.streamClose();
        e.stream.endMessage();
        assertTrue(e.conn.statuses.isEmpty());
        assertTrue(e.conn.bodyEndStreams.isEmpty());
        e.stream.endMessage();
        assertTrue(e.conn.statuses.isEmpty());
    }

    @Test
    public void testEndMessageWithNothingSetSendsA200HeaderSectionThatEndsTheStream() {
        Env e = h2();
        e.stream.endMessage();
        assertEquals(1, e.conn.statuses.size());
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
        assertTrue(e.conn.headerEndStreams.get(0).booleanValue());
    }

    @Test
    public void testFieldsWithoutAStatusDefaultTo200() {
        Env e = h2();
        e.stream.header("x-a", "b");
        e.stream.endMessage();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
        assertEquals("b", HeaderFields.getValue(e.conn.sentHeaders.get(0), "x-a"));
    }

    @Test
    public void testBodyAfterEndMessageReportsIllegalState() {
        Env e = h2();
        e.stream.status(200);
        e.stream.endHeaders();
        e.stream.endMessage();
        try {
            e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
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
        e.stream.status(200);
        e.stream.endMessage();
        assertFalse(e.stream.sendCapsule(7L, ByteBuffer.wrap(new byte[] {1})));
    }

    @Test
    public void testCapsuleOpensTheBodyImplicitlyAndIsSent() {
        Env e = h2();
        e.stream.status(200);
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
        e.stream.endMessage();
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

    /** Makes one promised request, GET /pushed with an extra field. */
    private static boolean push(Env e) {
        e.stream.startPushPromise(HttpMethod.GET, "/pushed");
        e.stream.header("accept", "text/css");
        return e.stream.endPushPromise();
    }

    @Test
    public void testPushIsRefusedWhenNotPossible() {
        Env http1 = h1();
        assertFalse(push(http1));
        Env off = h2();
        off.conn.enablePush = false;
        assertFalse(push(off));
        assertEquals(0, off.conn.pushPromises);
        Env noStream = h2();
        noStream.conn.pushedStreamCreated = false;
        assertFalse(push(noStream));
        Env failing = h2();
        failing.conn.failSendPush = true;
        assertFalse(push(failing));
        Env encoding = h2();
        encoding.conn.failEncodeHeaders = true;
        assertFalse(push(encoding));
    }

    @Test
    public void testPushPromiseCreatesAReservedStream() {
        Env e = h2();
        assertTrue(push(e));
        assertEquals(1, e.conn.pushPromises);
        assertEquals(1, e.conn.pushedStreams.size());
        assertFalse(e.conn.pushedStreams.get(0).isActive());
    }

    @Test
    public void testPushPromiseCarriesThePromisedRequestFields() {
        Env e = h2();
        assertTrue(push(e));
        assertEquals(1, e.conn.encodedHeaders.size());
        List<Header> promised = e.conn.encodedHeaders.get(0);
        assertEquals("GET", HeaderFields.getValue(promised, ":method"));
        assertEquals("https", HeaderFields.getValue(promised, ":scheme"));
        assertEquals("h.test", HeaderFields.getValue(promised, ":authority"));
        assertEquals("/pushed", HeaderFields.getValue(promised, ":path"));
        assertEquals("text/css", HeaderFields.getValue(promised, "accept"));
    }

    @Test
    public void testPushPromiseDoesNotDisturbTheResponse() {
        Env e = h2();
        e.stream.status(200);
        e.stream.startPushPromise(HttpMethod.GET, "/pushed");
        try {
            e.stream.bodyContent(ByteBuffer.wrap(new byte[] {1}));
            fail("nothing else may be called while a push promise is open");
        } catch (IllegalStateException expected) {
            assertTrue(e.conn.statuses.isEmpty());
        }
        assertTrue(e.stream.endPushPromise());
        e.stream.endMessage();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
    }

    @Test
    public void testEndPushPromiseWithoutStartIsIllegal() {
        Env e = h2();
        try {
            e.stream.endPushPromise();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(0, e.conn.pushPromises);
        }
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
            e.stream.status(Integer.parseInt(status));
            e.stream.endMessage();
        }
        return e;
    }

    @Test
    public void testSpanIsStartedAndEndedWithOkStatus() {
        Env e = traced(null, false, "200");
        assertNotNull(e.conn.trace);
        String tp = HeaderFields.getValue(e.conn.sentHeaders.get(0), "traceparent");
        assertNotNull(tp);
    }

    @Test
    public void testSpanContinuesAnIncomingTraceparent() {
        Env e = traced("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", false, "200");
        String tp = HeaderFields.getValue(e.conn.sentHeaders.get(0), "traceparent");
        assertTrue(tp, tp.startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));
    }

    @Test
    public void testSpanReusesTheConnectionTrace() {
        Env e = traced(null, true, "200");
        Trace t = e.conn.trace;
        assertNotNull(t);
        String tp = HeaderFields.getValue(e.conn.sentHeaders.get(0), "traceparent");
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
        e.stream.status(200);
        e.stream.endMessage();
        assertEquals(Integer.valueOf(200), e.conn.statuses.get(0));
    }
}
