/*
 * SyncReplDispatcherTest.java
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ldap.asn1.Asn1Type;
import org.bluezoo.gumdrop.ldap.asn1.BerDecoder;
import org.bluezoo.gumdrop.ldap.asn1.Asn1Element;
import org.bluezoo.gumdrop.ldap.asn1.BerEncoder;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for RFC 4533 content synchronization support: a
 * {@link SyncRequestValue} control attached to a real search request,
 * and a {@link SyncReplDispatcher} wired to {@link LdapClientProtocolHandler}
 * receiving syncStateValue/syncDoneValue/syncInfoValue from the wire.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533">RFC 4533</a>
 */
public class SyncReplDispatcherTest {

    private static final byte[] COOKIE_1 = new byte[] { 0x01 };
    private static final byte[] COOKIE_2 = new byte[] { 0x02 };
    private static final byte[] ENTRY_UUID = new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    private LdapClientProtocolHandler protocol;
    private StubEndpoint endpoint;
    private RecordingSyncHandler syncHandler;

    @Before
    public void setUp() {
        protocol = new LdapClientProtocolHandler(new NoopConnectionReady(), false);
        endpoint = new StubEndpoint();
        protocol.connected(endpoint);
        syncHandler = new RecordingSyncHandler();
    }

    // ── Request side ─────────────────────────────────────────────────────

    @Test
    public void testSyncRequestControlAttachedToSearchRequest() throws Exception {
        SyncRequestValue syncRequest = new SyncRequestValue(SyncRequestMode.REFRESH_AND_PERSIST, COOKIE_1);
        protocol.setRequestControls(Collections.singletonList(syncRequest.toControl()));

        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));

        assertFalse(endpoint.sentBuffers.isEmpty());
        Control decoded = decodeSingleRequestControl(endpoint.sentBuffers.get(0));
        assertEquals(Control.OID_SYNC_REQUEST, decoded.getOID());
        assertTrue(decoded.isCritical());
        assertArrayEquals(syncRequest.encode(), decoded.getValue());
    }

    // ── Response side: entries carrying a Sync State control ───────────────

    @Test
    public void testSyncEntryReceivesParsedSyncState() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        ByteBuffer entry = buildSearchResultEntryWithSyncState(msgId, "cn=alice,dc=example,dc=com",
                SyncState.ADD, ENTRY_UUID, COOKIE_1);
        protocol.receive(entry);

        assertEquals(1, syncHandler.entries.size());
        assertEquals("cn=alice,dc=example,dc=com", syncHandler.entries.get(0).getDN());
        assertNotNull(syncHandler.syncStates.get(0));
        assertEquals(SyncState.ADD, syncHandler.syncStates.get(0).getState());
        assertArrayEquals(ENTRY_UUID, syncHandler.syncStates.get(0).getEntryUUID());
        assertArrayEquals(COOKIE_1, syncHandler.syncStates.get(0).getCookie());
    }

    @Test
    public void testSyncEntryWithoutSyncStateControlPassesNull() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        ByteBuffer entry = buildPlainSearchResultEntry(msgId, "cn=bob,dc=example,dc=com");
        protocol.receive(entry);

        assertEquals(1, syncHandler.entries.size());
        assertNull(syncHandler.syncStates.get(0));
    }

    // ── Response side: SearchResultDone carrying a Sync Done control ───────

    @Test
    public void testSyncDoneReceivesParsedSyncDoneValue() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        ByteBuffer done = buildSearchResultDoneWithSyncDone(msgId, COOKIE_2, true);
        protocol.receive(done);

        assertNotNull(syncHandler.lastDoneResult);
        assertTrue(syncHandler.lastDoneResult.isSuccess());
        assertNotNull(syncHandler.lastSyncDone);
        assertArrayEquals(COOKIE_2, syncHandler.lastSyncDone.getCookie());
        assertTrue(syncHandler.lastSyncDone.isRefreshDeletes());
    }

    @Test
    public void testSyncDoneWithoutControlUsesEmptyDefault() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        ByteBuffer done = buildPlainSearchResultDone(msgId);
        protocol.receive(done);

        assertNotNull(syncHandler.lastSyncDone);
        assertFalse(syncHandler.lastSyncDone.hasCookie());
        assertFalse(syncHandler.lastSyncDone.isRefreshDeletes());
    }

    // ── Response side: IntermediateResponse carrying a syncInfoValue ───────

    @Test
    public void testSyncInfoNewCookieDispatched() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        BerEncoder valueEncoder = new BerEncoder();
        valueEncoder.writeContext(0, COOKIE_1);
        ByteBuffer response = buildIntermediateResponse(msgId, LdapConstants.OID_SYNC_INFO,
                valueEncoder.toByteArray());
        protocol.receive(response);

        assertArrayEquals(COOKIE_1, syncHandler.lastNewCookie);
    }

    @Test
    public void testSyncInfoRefreshPresentDispatched() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        BerEncoder valueEncoder = new BerEncoder();
        valueEncoder.beginContext(2, true);
        valueEncoder.writeOctetString(COOKIE_1);
        valueEncoder.writeBoolean(true);
        valueEncoder.endContext();
        ByteBuffer response = buildIntermediateResponse(msgId, LdapConstants.OID_SYNC_INFO,
                valueEncoder.toByteArray());
        protocol.receive(response);

        assertArrayEquals(COOKIE_1, syncHandler.lastRefreshPresentCookie);
        assertTrue(syncHandler.lastRefreshPresentDone);
    }

    @Test
    public void testSyncInfoSyncIdSetDispatched() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        BerEncoder valueEncoder = new BerEncoder();
        valueEncoder.beginContext(3, true);
        valueEncoder.writeOctetString(COOKIE_2);
        valueEncoder.writeBoolean(false);
        valueEncoder.beginSet();
        valueEncoder.writeOctetString(ENTRY_UUID);
        valueEncoder.endSet();
        valueEncoder.endContext();
        ByteBuffer response = buildIntermediateResponse(msgId, LdapConstants.OID_SYNC_INFO,
                valueEncoder.toByteArray());
        protocol.receive(response);

        assertArrayEquals(COOKIE_2, syncHandler.lastSyncIdSetCookie);
        assertFalse(syncHandler.lastSyncIdSetRefreshDeletes);
        assertEquals(1, syncHandler.lastSyncIdSetUuids.size());
        assertArrayEquals(ENTRY_UUID, syncHandler.lastSyncIdSetUuids.get(0));
    }

    @Test
    public void testNonSyncIntermediateResponseIgnoredBySyncReplDispatcher() {
        SearchRequest req = new SearchRequest();
        req.setBaseDN("dc=example,dc=com");
        req.setFilter("(objectClass=*)");
        protocol.search(req, new SyncReplDispatcher(syncHandler));
        int msgId = 1;

        ByteBuffer response = buildIntermediateResponse(msgId, "1.2.3.4.5", null);
        protocol.receive(response);

        assertNull(syncHandler.lastNewCookie);
        assertTrue(syncHandler.entries.isEmpty());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Decodes the outbound SearchRequest's Controls [0] SEQUENCE and returns its single control. */
    private Control decodeSingleRequestControl(ByteBuffer wire) throws Exception {
        BerDecoder decoder = new BerDecoder();
        decoder.receive(wire.duplicate());
        Asn1Element message = decoder.next();
        List<Asn1Element> children = message.getChildren();
        // children: [0]=messageId, [1]=SearchRequest, [2]=Controls[0]
        Asn1Element controlsElement = children.get(2);
        Asn1Element controlSeq = controlsElement.getChildren().get(0);
        List<Asn1Element> parts = controlSeq.getChildren();
        String oid = parts.get(0).asString();
        boolean critical = false;
        byte[] value = null;
        for (int i = 1; i < parts.size(); i++) {
            Asn1Element part = parts.get(i);
            if (part.getTag() == Asn1Type.BOOLEAN) {
                critical = part.asBoolean();
            } else if (part.getTag() == Asn1Type.OCTET_STRING) {
                value = part.asOctetString();
            }
        }
        return new Control(oid, critical, value);
    }

    private ByteBuffer buildPlainSearchResultEntry(int messageId, String dn) {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(4, true); // SearchResultEntry
        encoder.writeOctetString(dn);
        encoder.beginSequence(); // PartialAttributeList, empty
        encoder.endSequence();
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildSearchResultEntryWithSyncState(int messageId, String dn,
            SyncState state, byte[] entryUUID, byte[] cookie) {
        BerEncoder valueEncoder = new BerEncoder();
        valueEncoder.beginSequence();
        valueEncoder.writeEnumerated(state.getValue());
        valueEncoder.writeOctetString(entryUUID);
        if (cookie != null) {
            valueEncoder.writeOctetString(cookie);
        }
        valueEncoder.endSequence();

        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(4, true); // SearchResultEntry
        encoder.writeOctetString(dn);
        encoder.beginSequence(); // PartialAttributeList, empty
        encoder.endSequence();
        encoder.endApplication();
        // Controls [0]
        encoder.beginContext(0, true);
        encoder.beginSequence();
        encoder.writeOctetString(Control.OID_SYNC_STATE);
        encoder.writeOctetString(valueEncoder.toByteArray());
        encoder.endSequence();
        encoder.endContext();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildPlainSearchResultDone(int messageId) {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(5, true); // SearchResultDone
        encoder.writeEnumerated(0); // success
        encoder.writeOctetString("");
        encoder.writeOctetString("");
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildSearchResultDoneWithSyncDone(int messageId, byte[] cookie, boolean refreshDeletes) {
        BerEncoder valueEncoder = new BerEncoder();
        valueEncoder.beginSequence();
        if (cookie != null) {
            valueEncoder.writeOctetString(cookie);
        }
        if (refreshDeletes) {
            valueEncoder.writeBoolean(true);
        }
        valueEncoder.endSequence();

        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(5, true); // SearchResultDone
        encoder.writeEnumerated(0); // success
        encoder.writeOctetString("");
        encoder.writeOctetString("");
        encoder.endApplication();
        // Controls [0]
        encoder.beginContext(0, true);
        encoder.beginSequence();
        encoder.writeOctetString(Control.OID_SYNC_DONE);
        encoder.writeOctetString(valueEncoder.toByteArray());
        encoder.endSequence();
        encoder.endContext();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    private ByteBuffer buildIntermediateResponse(int messageId, String oid, byte[] value) {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeInteger(messageId);
        encoder.beginApplication(25, true); // IntermediateResponse
        if (oid != null) {
            encoder.writeContext(0, oid.getBytes(StandardCharsets.UTF_8));
        }
        if (value != null) {
            encoder.writeContext(1, value);
        }
        encoder.endApplication();
        encoder.endSequence();
        return encoder.toByteBuffer();
    }

    // ── Stub / Recording classes ─────────────────────────────────────────

    private static class NoopConnectionReady implements LdapConnectionReady {
        @Override
        public void handleReady(LdapConnected connection) {
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    private static class RecordingSyncHandler implements SyncReplHandler {
        final List<SearchResultEntry> entries = new ArrayList<SearchResultEntry>();
        final List<SyncStateValue> syncStates = new ArrayList<SyncStateValue>();
        LdapResult lastDoneResult;
        SyncDoneValue lastSyncDone;
        byte[] lastNewCookie;
        byte[] lastRefreshPresentCookie;
        boolean lastRefreshPresentDone;
        byte[] lastSyncIdSetCookie;
        boolean lastSyncIdSetRefreshDeletes;
        List<byte[]> lastSyncIdSetUuids;

        @Override
        public void syncEntry(SearchResultEntry entry, SyncStateValue syncState) {
            entries.add(entry);
            syncStates.add(syncState);
        }

        @Override
        public void syncReference(String[] referralUrls) {
        }

        @Override
        public void syncNewCookie(byte[] cookie) {
            lastNewCookie = cookie;
        }

        @Override
        public void syncRefreshDelete(byte[] cookie, boolean refreshDone) {
        }

        @Override
        public void syncRefreshPresent(byte[] cookie, boolean refreshDone) {
            lastRefreshPresentCookie = cookie;
            lastRefreshPresentDone = refreshDone;
        }

        @Override
        public void syncIdSet(byte[] cookie, boolean refreshDeletes, List<byte[]> entryUUIDs) {
            lastSyncIdSetCookie = cookie;
            lastSyncIdSetRefreshDeletes = refreshDeletes;
            lastSyncIdSetUuids = entryUUIDs;
        }

        @Override
        public void syncDone(LdapResult result, SyncDoneValue syncDone) {
            lastDoneResult = result;
            lastSyncDone = syncDone;
        }
    }

    private static class StubEndpoint implements Endpoint {
        final List<ByteBuffer> sentBuffers = new ArrayList<ByteBuffer>();
        boolean open = true;

        @Override
        public void send(ByteBuffer buf) {
            byte[] copy = new byte[buf.remaining()];
            buf.get(copy);
            sentBuffers.add(ByteBuffer.wrap(copy));
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isClosing() {
            return false;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public void startTLS() throws java.io.IOException {
        }

        @Override
        public void pauseRead() {
        }

        @Override
        public void resumeRead() {
        }

        @Override
        public void onWriteReady(Runnable callback) {
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) {
        }

        @Override
        public org.bluezoo.gumdrop.telemetry.Trace getTrace() {
            return null;
        }

        @Override
        public boolean isTelemetryEnabled() {
            return false;
        }

        @Override
        public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() {
            return null;
        }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return null;
        }
    }
}
