/*
 * LDAPClientProtocolHandlerTest.java
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
import org.bluezoo.gumdrop.ldap.asn1.BEREncoder;
import org.bluezoo.gumdrop.ldap.asn1.ASN1Type;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for LDAPClientProtocolHandler — new features:
 * abandon (RFC 4511 §4.11), controls (RFC 4511 §4.1.11),
 * unsolicited notifications (RFC 4511 §4.4),
 * intermediate responses (RFC 4511 §4.13).
 */
public class LDAPClientProtocolHandlerTest {

    private RecordingHandler handler;
    private LDAPClientProtocolHandler protocol;
    private StubEndpoint endpoint;

    @Before
    public void setUp() {
        handler = new RecordingHandler();
        protocol = new LDAPClientProtocolHandler(handler, false);
        endpoint = new StubEndpoint();
        protocol.connected(endpoint);
    }

    // ── Abandon ──────────────────────────────────────────────────────────

    @Test
    public void testAbandonSendsRequest() {
        RecordingBindHandler bindHandler = new RecordingBindHandler();
        protocol.bind("cn=admin", "secret", bindHandler);
        endpoint.sentBuffers.clear();

        protocol.abandon(1);

        assertFalse(endpoint.sentBuffers.isEmpty());
    }

    @Test
    public void testAbandonRemovesPendingCallback() {
        RecordingBindHandler bindHandler = new RecordingBindHandler();
        protocol.bind("cn=admin", "secret", bindHandler);

        protocol.abandon(1);

        // Send a BindResponse for messageId 1 — should NOT invoke callback
        ByteBuffer response = buildBindResponse(1, 0, "", "");
        protocol.receive(response);
        assertNull(bindHandler.session);
        assertNull(bindHandler.failureResult);
    }

    // ── Controls ─────────────────────────────────────────────────────────

    @Test
    public void testSetRequestControlsConsumedAfterSend() {
        RecordingSearchHandler searchHandler = new RecordingSearchHandler();
        protocol.setRequestControls(
                Collections.singletonList(new Control("1.2.3.4", true)));

        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, searchHandler);

        // After search is sent, getResponseControls (request-side) is gone
        List<Control> rc = protocol.getResponseControls();
        assertTrue(rc.isEmpty());
    }

    @Test
    public void testResponseControlsParsed() {
        RecordingSearchHandler searchHandler = new RecordingSearchHandler();
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, searchHandler);
        int msgId = 1;

        // Build SearchResultDone with controls in the message envelope
        ByteBuffer response = buildSearchResultDoneWithControls(msgId);
        protocol.receive(response);

        assertNotNull(searchHandler.doneResult);
        assertTrue(searchHandler.doneResult.hasControls());
        assertEquals(1, searchHandler.doneResult.getControls().size());
        assertEquals("1.2.840.113556.1.4.319",
                searchHandler.doneResult.getControls().get(0).getOID());
    }

    // ── Unsolicited Notification ─────────────────────────────────────────

    @Test
    public void testNoticeOfDisconnectionClosesConnection() {
        ByteBuffer notification = buildNoticeOfDisconnection();
        protocol.receive(notification);

        assertNotNull(handler.lastError);
        assertTrue(handler.lastError.getMessage().contains("Notice of Disconnection"));
        assertFalse(protocol.isOpen());
    }

    // ── IntermediateResponse ─────────────────────────────────────────────

    @Test
    public void testIntermediateResponseDeliveredToHandler() {
        RecordingIntermediateSearchHandler searchHandler =
                new RecordingIntermediateSearchHandler();
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, searchHandler);
        int msgId = 1;

        ByteBuffer response = buildIntermediateResponse(msgId, "1.2.3.4.5", null);
        protocol.receive(response);

        assertEquals("1.2.3.4.5", searchHandler.lastIntermediateOid);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ByteBuffer buildBindResponse(int messageId, int resultCode,
                                         String matchedDN, String diagnostic) {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(1, true);  // BindResponse
        encoder.writeEnumerated(resultCode);
        encoder.writeOctetString(matchedDN);
        encoder.writeOctetString(diagnostic);
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildSearchResultDoneWithControls(int messageId) {
        BEREncoder outer = new BEREncoder();
        outer.beginSequence();
        outer.writeInteger(messageId);
        outer.beginApplication(5, true);  // SearchResultDone
        outer.writeEnumerated(0);  // success
        outer.writeOctetString("");
        outer.writeOctetString("");
        outer.endApplication();
        // Controls [0] SEQUENCE
        outer.beginContext(0, true);
        outer.beginSequence();
        outer.writeOctetString("1.2.840.113556.1.4.319");
        outer.writeBoolean(false);
        outer.writeOctetString(new byte[]{0x01, 0x02});
        outer.endSequence();
        outer.endContext();
        outer.endSequence();
        return outer.toByteBuffer();
    }

    private ByteBuffer buildNoticeOfDisconnection() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(0);  // messageID 0 = unsolicited
        encoder.beginApplication(24, true);  // ExtendedResponse
        encoder.writeEnumerated(80);  // OTHER
        encoder.writeOctetString("");
        encoder.writeOctetString("Server shutting down");
        // responseName [10] — Notice of Disconnection OID
        encoder.writeContext(10,
                "1.3.6.1.4.1.1466.20036".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildIntermediateResponse(int messageId,
                                                  String oid, byte[] value) {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(25, true);  // IntermediateResponse
        if (oid != null) {
            encoder.writeContext(0,
                    oid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (value != null) {
            encoder.writeContext(1, value);
        }
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    // ── Stub / Recording classes ─────────────────────────────────────────

    static class RecordingHandler implements LDAPConnectionReady {
        LDAPConnected connection;
        Exception lastError;

        @Override
        public void handleReady(LDAPConnected connection) {
            this.connection = connection;
        }

        @Override
        public void onConnected(Endpoint endpoint) {}

        @Override
        public void onError(Exception cause) {
            this.lastError = cause;
        }

        @Override
        public void onDisconnected() {}

        @Override
        public void onSecurityEstablished(SecurityInfo info) {}
    }

    static class RecordingBindHandler implements BindResultHandler {
        LDAPSession session;
        LDAPResult failureResult;

        @Override
        public void handleBindSuccess(LDAPSession session) {
            this.session = session;
        }

        @Override
        public void handleBindFailure(LDAPResult result, LDAPConnected connection) {
            this.failureResult = result;
        }
    }

    static class RecordingSearchHandler implements SearchResultHandler {
        List<SearchResultEntry> entries = new ArrayList<>();
        LDAPResult doneResult;

        @Override
        public void handleEntry(SearchResultEntry entry) {
            entries.add(entry);
        }

        @Override
        public void handleReference(String[] referralUrls) {}

        @Override
        public void handleDone(LDAPResult result, LDAPSession session) {
            this.doneResult = result;
        }
    }

    static class RecordingIntermediateSearchHandler
            implements SearchResultHandler, IntermediateResponseHandler {
        String lastIntermediateOid;
        byte[] lastIntermediateValue;

        @Override
        public void handleEntry(SearchResultEntry entry) {}

        @Override
        public void handleReference(String[] referralUrls) {}

        @Override
        public void handleDone(LDAPResult result, LDAPSession session) {}

        @Override
        public void handleIntermediateResponse(String responseName, byte[] responseValue) {
            this.lastIntermediateOid = responseName;
            this.lastIntermediateValue = responseValue;
        }
    }

    static class StubEndpoint implements Endpoint {
        List<ByteBuffer> sentBuffers = new ArrayList<>();
        boolean open = true;

        @Override
        public void send(ByteBuffer buf) {
            byte[] copy = new byte[buf.remaining()];
            buf.get(copy);
            sentBuffers.add(ByteBuffer.wrap(copy));
        }

        @Override
        public boolean isOpen() { return open; }

        @Override
        public boolean isClosing() { return false; }

        @Override
        public void close() { open = false; }

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
