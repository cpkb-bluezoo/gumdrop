/*
 * RESPTypeTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link RESPType}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPTypeTest {

    @Test
    public void testSimpleStringPrefix() {
        assertEquals((byte) '+', RESPType.SIMPLE_STRING.getPrefix());
    }

    @Test
    public void testErrorPrefix() {
        assertEquals((byte) '-', RESPType.ERROR.getPrefix());
    }

    @Test
    public void testIntegerPrefix() {
        assertEquals((byte) ':', RESPType.INTEGER.getPrefix());
    }

    @Test
    public void testBulkStringPrefix() {
        assertEquals((byte) '$', RESPType.BULK_STRING.getPrefix());
    }

    @Test
    public void testArrayPrefix() {
        assertEquals((byte) '*', RESPType.ARRAY.getPrefix());
    }

    @Test
    public void testFromPrefixSimpleString() throws RESPException {
        assertEquals(RESPType.SIMPLE_STRING, RESPType.fromPrefix((byte) '+'));
    }

    @Test
    public void testFromPrefixError() throws RESPException {
        assertEquals(RESPType.ERROR, RESPType.fromPrefix((byte) '-'));
    }

    @Test
    public void testFromPrefixInteger() throws RESPException {
        assertEquals(RESPType.INTEGER, RESPType.fromPrefix((byte) ':'));
    }

    @Test
    public void testFromPrefixBulkString() throws RESPException {
        assertEquals(RESPType.BULK_STRING, RESPType.fromPrefix((byte) '$'));
    }

    @Test
    public void testFromPrefixArray() throws RESPException {
        assertEquals(RESPType.ARRAY, RESPType.fromPrefix((byte) '*'));
    }

    @Test(expected = RESPException.class)
    public void testFromPrefixUnknown() throws RESPException {
        RESPType.fromPrefix((byte) 'X');
    }

    @Test(expected = RESPException.class)
    public void testFromPrefixNull() throws RESPException {
        RESPType.fromPrefix((byte) 0);
    }

    @Test
    public void testAllTypesHaveUniquePrefix() throws RESPException {
        RESPType[] types = RESPType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals("Types should have unique prefixes",
                    types[i].getPrefix(), types[j].getPrefix());
            }
        }
    }

    @Test
    public void testRoundTrip() throws RESPException {
        for (RESPType type : RESPType.values()) {
            byte prefix = type.getPrefix();
            RESPType resolved = RESPType.fromPrefix(prefix);
            assertEquals(type, resolved);
        }
    }

}

