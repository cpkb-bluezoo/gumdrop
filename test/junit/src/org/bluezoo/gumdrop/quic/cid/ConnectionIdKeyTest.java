/*
 * ConnectionIdKeyTest.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link ConnectionIdKey}'s equality/hash contract -- the
 * property connection-ID lookup maps (issue #321) rely on to work
 * correctly as a {@code HashMap} key in place of a hex-encoded
 * {@code String}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectionIdKeyTest {

    @Test
    public void testEqualBytesInDifferentArraysAreEqual() {
        byte[] a = ByteArrays.toByteArray("aabbccddeeff");
        byte[] b = ByteArrays.toByteArray("aabbccddeeff");
        assertFalse("test arrays must be distinct instances", a == b);
        assertEquals(new ConnectionIdKey(a), new ConnectionIdKey(b));
        assertEquals(new ConnectionIdKey(a).hashCode(), new ConnectionIdKey(b).hashCode());
    }

    @Test
    public void testDifferentBytesAreNotEqual() {
        byte[] a = ByteArrays.toByteArray("aabbccddeeff");
        byte[] b = ByteArrays.toByteArray("aabbccddeeee");
        assertFalse(new ConnectionIdKey(a).equals(new ConnectionIdKey(b)));
    }

    @Test
    public void testDifferentLengthsAreNotEqual() {
        byte[] a = ByteArrays.toByteArray("aabbcc");
        byte[] b = ByteArrays.toByteArray("aabbccdd");
        assertFalse(new ConnectionIdKey(a).equals(new ConnectionIdKey(b)));
    }

    @Test
    public void testWorksAsHashMapKey() {
        byte[] connectionId = ByteArrays.toByteArray("0102030405060708090a0b0c0d0e0f1011121314");
        Map<ConnectionIdKey, String> map = new HashMap<ConnectionIdKey, String>();
        map.put(new ConnectionIdKey(connectionId), "connection");

        byte[] lookupCopy = ByteArrays.toByteArray("0102030405060708090a0b0c0d0e0f1011121314");
        assertEquals("connection", map.get(new ConnectionIdKey(lookupCopy)));

        assertTrue(map.containsKey(new ConnectionIdKey(lookupCopy)));
        map.remove(new ConnectionIdKey(lookupCopy));
        assertNull(map.get(new ConnectionIdKey(connectionId)));
    }

    @Test
    public void testNotEqualToUnrelatedType() {
        byte[] a = ByteArrays.toByteArray("aabbcc");
        assertFalse(new ConnectionIdKey(a).equals("aabbcc"));
    }

    @Test
    public void testToStringMatchesHexEncoding() {
        byte[] a = ByteArrays.toByteArray("aabbccddeeff");
        assertEquals("aabbccddeeff", new ConnectionIdKey(a).toString());
    }
}
