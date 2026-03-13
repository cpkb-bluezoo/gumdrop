/*
 * FilterEncodingTest.java
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

package org.bluezoo.gumdrop.ldap.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Element;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Type;
import org.bluezoo.gumdrop.ldap.asn1.BERDecoder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for search filter encoding — approximate match (~=) and
 * extensible match (:=) per RFC 4515 section 4.
 */
public class FilterEncodingTest {

    private LDAPClientProtocolHandler protocol;
    private CapturingEndpoint endpoint;

    @Before
    public void setUp() {
        NullHandler handler = new NullHandler();
        protocol = new LDAPClientProtocolHandler(handler, false);
        endpoint = new CapturingEndpoint();
        protocol.connected(endpoint);
    }

    @Test
    public void testApproximateMatchFilter() throws Exception {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(sn~=Smith)");

        protocol.search(req, new NullSearchHandler());

        ASN1Element searchReq = decodeSearchRequest();
        // The filter is child index 6 in the SearchRequest
        ASN1Element filter = searchReq.getChildren().get(6);

        // Approximate match = context tag 8 (constructed)
        int expectedTag = ASN1Type.contextTag(8, true);
        assertEquals(expectedTag, filter.getTag());
        List<ASN1Element> avaParts = filter.getChildren();
        assertEquals(2, avaParts.size());
        assertEquals("sn", avaParts.get(0).asString());
        assertEquals("Smith", avaParts.get(1).asString());
    }

    @Test
    public void testExtensibleMatchSimple() throws Exception {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(cn:caseExactMatch:=Fred)");

        protocol.search(req, new NullSearchHandler());

        ASN1Element searchReq = decodeSearchRequest();
        ASN1Element filter = searchReq.getChildren().get(6);

        int expectedTag = ASN1Type.contextTag(9, true);
        assertEquals(expectedTag, filter.getTag());

        // Should contain: matchingRule [1], type [2], matchValue [3]
        List<ASN1Element> parts = filter.getChildren();
        assertTrue(parts.size() >= 2);

        boolean foundMatchingRule = false;
        boolean foundType = false;
        boolean foundMatchValue = false;
        for (ASN1Element part : parts) {
            int tagNum = ASN1Type.getTagNumber(part.getTag());
            if (tagNum == 1) {
                assertEquals("caseExactMatch", part.asString());
                foundMatchingRule = true;
            } else if (tagNum == 2) {
                assertEquals("cn", part.asString());
                foundType = true;
            } else if (tagNum == 3) {
                assertEquals("Fred", part.asString());
                foundMatchValue = true;
            }
        }
        assertTrue(foundMatchingRule);
        assertTrue(foundType);
        assertTrue(foundMatchValue);
    }

    @Test
    public void testExtensibleMatchWithDN() throws Exception {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(cn:dn:caseExactMatch:=Fred)");

        protocol.search(req, new NullSearchHandler());

        ASN1Element searchReq = decodeSearchRequest();
        ASN1Element filter = searchReq.getChildren().get(6);

        int expectedTag = ASN1Type.contextTag(9, true);
        assertEquals(expectedTag, filter.getTag());

        boolean foundDnAttributes = false;
        for (ASN1Element part : filter.getChildren()) {
            int tagNum = ASN1Type.getTagNumber(part.getTag());
            if (tagNum == 4) {
                foundDnAttributes = true;
            }
        }
        assertTrue("dnAttributes [4] should be present", foundDnAttributes);
    }

    @Test
    public void testExtensibleMatchWithoutAttr() throws Exception {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(:caseExactMatch:=Fred)");

        protocol.search(req, new NullSearchHandler());

        ASN1Element searchReq = decodeSearchRequest();
        ASN1Element filter = searchReq.getChildren().get(6);

        int expectedTag = ASN1Type.contextTag(9, true);
        assertEquals(expectedTag, filter.getTag());

        boolean foundType = false;
        for (ASN1Element part : filter.getChildren()) {
            int tagNum = ASN1Type.getTagNumber(part.getTag());
            if (tagNum == 2) {
                foundType = true;
            }
        }
        assertFalse("type [2] should NOT be present when attr is omitted", foundType);
    }

    private ASN1Element decodeSearchRequest() throws Exception {
        assertFalse("No data sent", endpoint.sentBuffers.isEmpty());
        ByteBuffer buf = endpoint.sentBuffers.get(endpoint.sentBuffers.size() - 1);
        buf.rewind();
        BERDecoder decoder = new BERDecoder();
        decoder.receive(buf);
        ASN1Element message = decoder.next();
        assertNotNull(message);
        // message is SEQUENCE: messageID, SearchRequest (application 3)
        return message.getChildren().get(1);
    }

    // ── Stubs ────────────────────────────────────────────────────────────

    static class NullHandler implements LDAPConnectionReady {
        @Override
        public void handleReady(LDAPConnected connection) {}

        @Override
        public void onConnected(Endpoint endpoint) {}

        @Override
        public void onError(Exception cause) {}

        @Override
        public void onDisconnected() {}

        @Override
        public void onSecurityEstablished(SecurityInfo info) {}
    }

    static class NullSearchHandler implements SearchResultHandler {
        @Override
        public void handleEntry(SearchResultEntry entry) {}

        @Override
        public void handleReference(String[] referralUrls) {}

        @Override
        public void handleDone(LDAPResult result, LDAPSession session) {}
    }

    static class CapturingEndpoint implements Endpoint {
        List<ByteBuffer> sentBuffers = new ArrayList<>();

        @Override
        public void send(ByteBuffer buf) {
            byte[] copy = new byte[buf.remaining()];
            buf.get(copy);
            sentBuffers.add(ByteBuffer.wrap(copy));
        }

        @Override
        public boolean isOpen() { return true; }

        @Override
        public boolean isClosing() { return false; }

        @Override
        public void close() {}

        @Override
        public SocketAddress getLocalAddress() { return null; }

        @Override
        public SocketAddress getRemoteAddress() { return null; }

        @Override
        public boolean isSecure() { return false; }

        @Override
        public SecurityInfo getSecurityInfo() { return null; }

        @Override
        public void startTLS() throws IOException {}

        @Override
        public void pauseRead() {}

        @Override
        public void resumeRead() {}

        @Override
        public void onWriteReady(Runnable callback) {}

        @Override
        public void execute(Runnable task) { task.run(); }

        @Override
        public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) {}

        @Override
        public org.bluezoo.gumdrop.telemetry.Trace getTrace() { return null; }

        @Override
        public boolean isTelemetryEnabled() { return false; }

        @Override
        public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() { return null; }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }

        @Override
        public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }
}
