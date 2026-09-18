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
 * Unit tests for {@link RespType}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPTypeTest {

    @Test
    public void testSimpleStringPrefix() {
        assertEquals((byte) '+', RespType.SIMPLE_STRING.getPrefix());
    }

    @Test
    public void testErrorPrefix() {
        assertEquals((byte) '-', RespType.ERROR.getPrefix());
    }

    @Test
    public void testIntegerPrefix() {
        assertEquals((byte) ':', RespType.INTEGER.getPrefix());
    }

    @Test
    public void testBulkStringPrefix() {
        assertEquals((byte) '$', RespType.BULK_STRING.getPrefix());
    }

    @Test
    public void testArrayPrefix() {
        assertEquals((byte) '*', RespType.ARRAY.getPrefix());
    }

    @Test
    public void testFromPrefixSimpleString() throws RespException {
        assertEquals(RespType.SIMPLE_STRING, RespType.fromPrefix((byte) '+'));
    }

    @Test
    public void testFromPrefixError() throws RespException {
        assertEquals(RespType.ERROR, RespType.fromPrefix((byte) '-'));
    }

    @Test
    public void testFromPrefixInteger() throws RespException {
        assertEquals(RespType.INTEGER, RespType.fromPrefix((byte) ':'));
    }

    @Test
    public void testFromPrefixBulkString() throws RespException {
        assertEquals(RespType.BULK_STRING, RespType.fromPrefix((byte) '$'));
    }

    @Test
    public void testFromPrefixArray() throws RespException {
        assertEquals(RespType.ARRAY, RespType.fromPrefix((byte) '*'));
    }

    @Test(expected = RespException.class)
    public void testFromPrefixUnknown() throws RespException {
        RespType.fromPrefix((byte) 'X');
    }

    @Test(expected = RespException.class)
    public void testFromPrefixNull() throws RespException {
        RespType.fromPrefix((byte) 0);
    }

    @Test
    public void testAllTypesHaveUniquePrefix() throws RespException {
        RespType[] types = RespType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals("Types should have unique prefixes",
                    types[i].getPrefix(), types[j].getPrefix());
            }
        }
    }

    @Test
    public void testRoundTrip() throws RespException {
        for (RespType type : RespType.values()) {
            byte prefix = type.getPrefix();
            RespType resolved = RespType.fromPrefix(prefix);
            assertEquals(type, resolved);
        }
    }

}

