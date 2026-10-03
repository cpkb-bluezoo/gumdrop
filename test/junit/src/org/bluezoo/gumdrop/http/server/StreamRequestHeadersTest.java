/*
 * StreamRequestHeadersTest.java
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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

/**
 * Drives {@link Stream}'s request-side header and body handling directly
 * against a hand-written {@link MockHttpConnection}: HTTP/1.1 Upgrade and
 * Expect handling, authentication outcomes, HPACK header-block assembly
 * and failure (RFC 9113 sections 4.3 and 6.10), pushed-stream state
 * transitions and capsule bodies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamRequestHeadersTest {

    private static class Events extends CollectingRequestHandler {
        final List<String> log = new ArrayList<String>();
        final List<ByteBuffer> bodies = new ArrayList<ByteBuffer>();
        final List<Long> capsules = new ArrayList<Long>();
        boolean datagrams;

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            log.add("headers");
        }

        @Override
        public void startRequestBody(HttpResponseState state) {
            log.add("start");
        }

        @Override
        public void requestBodyContent(HttpResponseState state, ByteBuffer data) {
            log.add("body");
        }

        @Override
        public void endRequestBody(HttpResponseState state) {
            log.add("end");
        }

        @Override
        public void requestComplete(HttpResponseState state) {
            log.add("complete");
        }

        @Override
        public boolean wantsDatagrams() {
            return datagrams;
        }

        @Override
        public void datagramReceived(HttpResponseState state, ByteBuffer data) {
            log.add("datagram");
        }

        @Override
        public void capsuleReceived(HttpResponseState state, long type, ByteBuffer value) {
            capsules.add(Long.valueOf(type));
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
                    return CollectingRequestHandler.bind(events, state);
                }
            };
        }

        Env http1(String... nameValues) {
            stream = new Stream(conn, 1);
            Headers section = new Headers();
            section.add(new Header(":method", "POST"));
            section.add(new Header(":path", "/r"));
            for (int i = 0; i + 1 < nameValues.length; i += 2) {
                section.add(new Header(nameValues[i], nameValues[i + 1]));
            }
            for (Header header : section) {
                stream.addHeader(header);
            }
            // the server hands the application the events of the header
            // section, which the protocol layer has recorded
            MessageEvents.headers(stream.eventRecorder(), section);
            stream.streamEndHeaders();
            return this;
        }
    }

    private static class Provider extends HttpAuthenticationProvider {
        boolean required = true;

        @Override protected String getAuthMethod() { return "BASIC"; }
        @Override protected String getRealmName() { return "realm"; }
        @Override protected boolean passwordMatch(String r, String u, String p) {
            return "u".equals(u) && "p".equals(p);
        }
        @Override protected String getDigestHA1(String r, String u) { return null; }
        @Override protected Realm.TokenValidationResult validateBearerToken(String t) {
            return null;
        }
        @Override protected Realm.TokenValidationResult validateOAuthToken(String t) {
            return null;
        }
        @Override public boolean isAuthenticationRequired() { return required; }
    }

    // ------------------------------------------------------------------
    // HTTP/1.1 Upgrade (RFC 9110 section 7.8) and Expect (section 10.1.1)

    @Test
    public void testUpgradeTokensAreCollectedOnlyWhenTheConnectionAsksForUpgrade() {
        Env e = new Env(HttpVersion.HTTP_1_1).http1("Connection", "keep-alive, Upgrade",
                "Upgrade", "h2c, ,websocket", "Upgrade", "foo");
        assertTrue(e.stream.hasWebSocketUpgrade());
        Env noConnection = new Env(HttpVersion.HTTP_1_1).http1("Upgrade", "websocket");
        assertFalse(noConnection.stream.hasWebSocketUpgrade());
        Env noUpgrade = new Env(HttpVersion.HTTP_1_1).http1("Connection", "Upgrade");
        assertFalse(noUpgrade.stream.hasWebSocketUpgrade());
        Env other = new Env(HttpVersion.HTTP_1_1).http1("Connection", "Upgrade", "Upgrade", "h2c");
        assertFalse(other.stream.hasWebSocketUpgrade());
        Env tokens = new Env(HttpVersion.HTTP_1_1).http1("Connection", "keep-alive,close-ish");
        assertFalse(tokens.stream.hasWebSocketUpgrade());
    }

    @Test
    public void testMalformedHttp2SettingsHeaderIsIgnored() {
        Env e = new Env(HttpVersion.HTTP_1_1).http1("Connection", "Upgrade, HTTP2-Settings",
                "Upgrade", "h2c", "HTTP2-Settings", "***not base64***");
        assertTrue(e.events.log.contains("headers"));
        assertFalse(e.stream.isClosed());
    }

    @Test
    public void testOnlyExpect100ContinueGetsAnInterimResponse() {
        Env other = new Env(HttpVersion.HTTP_1_1).http1("Content-Length", "5",
                "Expect", "something-else");
        assertTrue(other.conn.raw.isEmpty());
        Env cont = new Env(HttpVersion.HTTP_1_1).http1("Content-Length", "5",
                "Expect", " 100-Continue ");
        assertEquals(1, cont.conn.raw.size());
        Env none = new Env(HttpVersion.HTTP_1_1).http1("Content-Length", "5");
        assertTrue(none.conn.raw.isEmpty());
        Env bodiless = new Env(HttpVersion.HTTP_1_1).http1("Content-Length", "0",
                "Expect", "100-continue");
        assertTrue(bodiless.conn.raw.isEmpty());
    }

    // ------------------------------------------------------------------
    // authentication outcomes

    @Test
    public void testOptionalAuthenticationLetsAnAnonymousRequestThrough() {
        Env e = new Env(HttpVersion.HTTP_1_1);
        Provider p = new Provider();
        p.required = false;
        e.conn.authenticationProvider = p;
        e.http1();
        assertTrue(e.events.log.contains("headers"));
        assertNull(e.stream.getPrincipal());
        assertTrue(e.conn.statuses.isEmpty());
    }

    @Test
    public void testRequiredAuthenticationRejectsAnAnonymousRequest() {
        Env e = new Env(HttpVersion.HTTP_1_1);
        e.conn.authenticationProvider = new Provider();
        e.http1();
        assertEquals(Integer.valueOf(401), e.conn.statuses.get(0));
        assertFalse(e.events.log.contains("headers"));
        assertNotNull(e.conn.sentHeaders.get(0).getValue("www-authenticate"));
    }

    // ------------------------------------------------------------------
    // HTTP/2 header blocks

    private static byte[] encoded(Encoder encoder, String... nameValues) throws IOException {
        Headers h = new Headers();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            h.add(new Header(nameValues[i], nameValues[i + 1]));
        }
        ByteBuffer buf = ByteBuffer.allocate(65536);
        encoder.encode(buf, h);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static String bigValue(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + (i * 7 + i / 13) % 26));
        }
        return sb.toString();
    }

    @Test
    public void testHeaderBlockSplitAcrossManyFragmentsIsReassembled() throws Exception {
        Env e = new Env(HttpVersion.HTTP_2_0);
        Encoder encoder = new Encoder(4096, 65536);
        byte[] block = encoded(encoder, ":method", "GET", ":scheme", "https",
                ":authority", "h.test", ":path", "/big", "x-big", bigValue(20000));
        e.stream = new Stream(e.conn, 1);
        int pos = 0;
        while (pos < block.length) {
            int n = Math.min(3000, block.length - pos);
            e.stream.appendHeaderBlockFragment(ByteBuffer.wrap(block, pos, n));
            pos += n;
        }
        e.stream.streamEndHeaders();
        assertTrue(e.conn.goawayCodes.isEmpty());
        assertEquals("GET", e.stream.getHeaders().getValue(":method"));
        assertEquals(20000, e.stream.getHeaders().getValue("x-big").length());
        assertTrue(e.events.log.contains("headers"));
    }

    @Test
    public void testHeaderBlockLargerThanTheListLimitIsAProtocolError() {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.conn.maxHeaderListSize = 100;
        e.stream = new Stream(e.conn, 1);
        e.stream.appendHeaderBlockFragment(ByteBuffer.wrap(new byte[200]));
        assertEquals(Integer.valueOf(1), e.conn.goawayCodes.get(0));
    }

    @Test
    public void testUndecodableHeaderBlockIsACompressionError() {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.stream = new Stream(e.conn, 1);
        e.stream.appendHeaderBlockFragment(ByteBuffer.wrap(new byte[] {
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f}));
        e.stream.streamEndHeaders();
        assertEquals(Integer.valueOf(9), e.conn.goawayCodes.get(0));
        assertFalse(e.events.log.contains("headers"));
    }

    @Test
    public void testPushedStreamMovesFromReservedToHalfClosedLocal() {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.stream = new Stream(e.conn, 2);
        e.stream.setPushPromise();
        e.stream.addHeader(new Header(":method", "GET"));
        e.stream.addHeader(new Header(":scheme", "https"));
        e.stream.addHeader(new Header(":authority", "h.test"));
        e.stream.addHeader(new Header(":path", "/pushed"));
        e.stream.streamEndHeaders();
        assertFalse(e.stream.isActive());
        e.stream.streamEndHeaders();
        assertTrue(e.stream.isActive());
    }

    // ------------------------------------------------------------------
    // capsule request bodies

    private static Env capsuleEnv(boolean datagrams) {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.events.datagrams = datagrams;
        e.stream = new Stream(e.conn, 1);
        e.stream.addHeader(new Header(":method", "POST"));
        e.stream.addHeader(new Header(":scheme", "https"));
        e.stream.addHeader(new Header(":authority", "h.test"));
        e.stream.addHeader(new Header(":path", "/c"));
        e.stream.addHeader(new Header("capsule-protocol", "?1"));
        e.stream.streamEndHeaders();
        return e;
    }

    @Test
    public void testCapsulesAreDispatchedByType() {
        Env e = capsuleEnv(true);
        byte[] datagram = Capsule.datagram(new byte[] {0, 1, 2}).encode();
        byte[] other = new Capsule(0x17L, new byte[] {9}).encode();
        e.stream.receiveRequestBody(ByteBuffer.wrap(datagram));
        e.stream.receiveRequestBody(ByteBuffer.wrap(other));
        assertTrue(e.events.log.contains("datagram"));
        assertEquals(1, e.events.capsules.size());
        assertEquals(Long.valueOf(0x17L), e.events.capsules.get(0));
    }

    @Test
    public void testDatagramCapsuleIsDroppedWhenTheHandlerDoesNotWantDatagrams() {
        Env e = capsuleEnv(false);
        e.stream.receiveRequestBody(ByteBuffer.wrap(Capsule.datagram(new byte[] {0, 1}).encode()));
        assertFalse(e.events.log.contains("datagram"));
        assertTrue(e.events.capsules.isEmpty());
    }

    @Test
    public void testMalformedCapsuleStreamIsAnErrorResponse() {
        Env e = capsuleEnv(true);
        e.stream.receiveRequestBody(ByteBuffer.wrap(new byte[] {(byte) 0xff}));
        e.stream.streamEndRequest();
        assertEquals(Integer.valueOf(400), e.conn.statuses.get(0));
    }

    @Test
    public void testRequestBodyOverTheLimitIsRejectedOnceAndLaterDataDiscarded() {
        Env e = new Env(HttpVersion.HTTP_2_0);
        e.conn.maxRequestBodySize = 4;
        e.stream = new Stream(e.conn, 1);
        e.stream.addHeader(new Header(":method", "POST"));
        e.stream.addHeader(new Header(":scheme", "https"));
        e.stream.addHeader(new Header(":authority", "h.test"));
        e.stream.addHeader(new Header(":path", "/u"));
        e.stream.streamEndHeaders();
        ByteBuffer big = ByteBuffer.wrap(new byte[10]);
        e.stream.receiveRequestBody(big);
        assertFalse(big.hasRemaining());
        assertEquals(Integer.valueOf(413), e.conn.statuses.get(0));
        ByteBuffer more = ByteBuffer.wrap(new byte[3]);
        e.stream.receiveRequestBody(more);
        assertFalse(more.hasRemaining());
        assertEquals(1, e.conn.statuses.size());
        assertFalse(e.events.log.contains("body"));
    }
}
