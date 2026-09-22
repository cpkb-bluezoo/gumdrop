/*
 * SyncReplValuesTest.java
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.ldap.asn1.Asn1Exception;
import org.bluezoo.gumdrop.ldap.asn1.BerEncoder;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the RFC 4533 (LDAP Content Synchronization) value
 * classes: {@link SyncRequestValue}, {@link SyncStateValue},
 * {@link SyncDoneValue}, and {@link SyncInfoValue}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SyncReplValuesTest {

    private static final byte[] COOKIE = new byte[] { 1, 2, 3, 4 };
    private static final byte[] UUID_BYTES = new byte[] {
        (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
    };

    // ── SyncRequestValue ─────────────────────────────────────────────────

    @Test
    public void testSyncRequestValueEncodeNoCookie() {
        SyncRequestValue value = new SyncRequestValue(SyncRequestMode.REFRESH_ONLY);
        byte[] encoded = value.encode();

        // SEQUENCE { ENUMERATED 1 } -- no cookie, no reloadHint (both omitted)
        assertArrayEquals(new byte[] { 0x30, 0x03, 0x0A, 0x01, 0x01 }, encoded);
    }

    @Test
    public void testSyncRequestValueEncodeWithCookieAndReloadHint() {
        SyncRequestValue value = new SyncRequestValue(SyncRequestMode.REFRESH_AND_PERSIST, COOKIE, true);
        byte[] encoded = value.encode();

        BerEncoder expected = new BerEncoder();
        expected.beginSequence();
        expected.writeEnumerated(3);
        expected.writeOctetString(COOKIE);
        expected.writeBoolean(true);
        expected.endSequence();
        assertArrayEquals(expected.toByteArray(), encoded);
    }

    @Test
    public void testSyncRequestValueToControl() {
        SyncRequestValue value = new SyncRequestValue(SyncRequestMode.REFRESH_AND_PERSIST, COOKIE);
        Control control = value.toControl();

        assertEquals(Control.OID_SYNC_REQUEST, control.getOID());
        assertTrue(control.isCritical());
        assertArrayEquals(value.encode(), control.getValue());
    }

    @Test
    public void testSyncRequestValueRejectsNullMode() {
        try {
            new SyncRequestValue(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // ── SyncStateValue ───────────────────────────────────────────────────

    @Test
    public void testSyncStateValueParseWithCookie() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeEnumerated(SyncState.ADD.getValue());
        encoder.writeOctetString(UUID_BYTES);
        encoder.writeOctetString(COOKIE);
        encoder.endSequence();

        SyncStateValue parsed = SyncStateValue.parse(encoder.toByteArray());

        assertEquals(SyncState.ADD, parsed.getState());
        assertArrayEquals(UUID_BYTES, parsed.getEntryUUID());
        assertTrue(parsed.hasCookie());
        assertArrayEquals(COOKIE, parsed.getCookie());
    }

    @Test
    public void testSyncStateValueParseWithoutCookie() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeEnumerated(SyncState.PRESENT.getValue());
        encoder.writeOctetString(UUID_BYTES);
        encoder.endSequence();

        SyncStateValue parsed = SyncStateValue.parse(encoder.toByteArray());

        assertEquals(SyncState.PRESENT, parsed.getState());
        assertFalse(parsed.hasCookie());
        assertNull(parsed.getCookie());
    }

    @Test(expected = Asn1Exception.class)
    public void testSyncStateValueParseRejectsEmpty() throws Asn1Exception {
        SyncStateValue.parse(new byte[0]);
    }

    @Test(expected = Asn1Exception.class)
    public void testSyncStateValueParseRejectsTruncated() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeEnumerated(SyncState.DELETE.getValue());
        encoder.endSequence();
        SyncStateValue.parse(encoder.toByteArray());
    }

    // ── SyncDoneValue ────────────────────────────────────────────────────

    @Test
    public void testSyncDoneValueParseNullIsEmpty() throws Asn1Exception {
        SyncDoneValue parsed = SyncDoneValue.parse(null);
        assertFalse(parsed.hasCookie());
        assertFalse(parsed.isRefreshDeletes());
    }

    @Test
    public void testSyncDoneValueParseWithCookieAndRefreshDeletes() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeOctetString(COOKIE);
        encoder.writeBoolean(true);
        encoder.endSequence();

        SyncDoneValue parsed = SyncDoneValue.parse(encoder.toByteArray());

        assertArrayEquals(COOKIE, parsed.getCookie());
        assertTrue(parsed.isRefreshDeletes());
    }

    @Test
    public void testSyncDoneValueParseEmptySequenceDefaults() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.endSequence();

        SyncDoneValue parsed = SyncDoneValue.parse(encoder.toByteArray());

        assertFalse(parsed.hasCookie());
        assertFalse(parsed.isRefreshDeletes());
    }

    // ── SyncInfoValue ────────────────────────────────────────────────────

    @Test
    public void testSyncInfoValueParseNewCookie() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.writeContext(0, COOKIE); // newcookie [0] OCTET STRING (primitive)

        SyncInfoValue parsed = SyncInfoValue.parse(encoder.toByteArray());

        assertEquals(SyncInfoValue.Kind.NEW_COOKIE, parsed.getKind());
        assertArrayEquals(COOKIE, parsed.getCookie());
    }

    @Test
    public void testSyncInfoValueParseRefreshDelete() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginContext(1, true); // refreshDelete [1] SEQUENCE
        encoder.writeOctetString(COOKIE);
        encoder.writeBoolean(false); // refreshDone explicitly false
        encoder.endContext();

        SyncInfoValue parsed = SyncInfoValue.parse(encoder.toByteArray());

        assertEquals(SyncInfoValue.Kind.REFRESH_DELETE, parsed.getKind());
        assertArrayEquals(COOKIE, parsed.getCookie());
        assertFalse(parsed.isRefreshDone());
    }

    @Test
    public void testSyncInfoValueParseRefreshPresentDefaultsRefreshDoneTrue() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.beginContext(2, true); // refreshPresent [2] SEQUENCE, empty (both fields OPTIONAL/DEFAULT)
        encoder.endContext();

        SyncInfoValue parsed = SyncInfoValue.parse(encoder.toByteArray());

        assertEquals(SyncInfoValue.Kind.REFRESH_PRESENT, parsed.getKind());
        assertFalse(parsed.hasCookie());
        assertTrue(parsed.isRefreshDone());
    }

    @Test
    public void testSyncInfoValueParseSyncIdSet() throws Asn1Exception {
        byte[] uuid2 = new byte[] {
            (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2
        };
        BerEncoder encoder = new BerEncoder();
        encoder.beginContext(3, true); // syncIdSet [3] SEQUENCE
        encoder.writeOctetString(COOKIE);
        encoder.writeBoolean(true); // refreshDeletes
        encoder.beginSet();
        encoder.writeOctetString(UUID_BYTES);
        encoder.writeOctetString(uuid2);
        encoder.endSet();
        encoder.endContext();

        SyncInfoValue parsed = SyncInfoValue.parse(encoder.toByteArray());

        assertEquals(SyncInfoValue.Kind.SYNC_ID_SET, parsed.getKind());
        assertArrayEquals(COOKIE, parsed.getCookie());
        assertTrue(parsed.isRefreshDeletes());
        List<byte[]> uuids = parsed.getEntryUUIDs();
        assertEquals(2, uuids.size());
        assertArrayEquals(UUID_BYTES, uuids.get(0));
        assertArrayEquals(uuid2, uuids.get(1));
    }

    @Test(expected = Asn1Exception.class)
    public void testSyncInfoValueParseRejectsUnknownChoiceTag() throws Asn1Exception {
        BerEncoder encoder = new BerEncoder();
        encoder.writeContext(7, COOKIE); // no such alternative
        SyncInfoValue.parse(encoder.toByteArray());
    }

    @Test(expected = Asn1Exception.class)
    public void testSyncInfoValueParseRejectsNull() throws Asn1Exception {
        SyncInfoValue.parse(null);
    }
}
