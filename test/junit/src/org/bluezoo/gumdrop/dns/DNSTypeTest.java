/*
 * DNSTypeTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSType}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSTypeTest {

    @Test
    public void testValues() {
        assertEquals(1, DNSType.A.getValue());
        assertEquals(2, DNSType.NS.getValue());
        assertEquals(5, DNSType.CNAME.getValue());
        assertEquals(6, DNSType.SOA.getValue());
        assertEquals(12, DNSType.PTR.getValue());
        assertEquals(15, DNSType.MX.getValue());
        assertEquals(16, DNSType.TXT.getValue());
        assertEquals(28, DNSType.AAAA.getValue());
        assertEquals(41, DNSType.OPT.getValue());
        assertEquals(255, DNSType.ANY.getValue());
    }
    
    @Test
    public void testFromValue() {
        assertEquals(DNSType.A, DNSType.fromValue(1));
        assertEquals(DNSType.NS, DNSType.fromValue(2));
        assertEquals(DNSType.CNAME, DNSType.fromValue(5));
        assertEquals(DNSType.SOA, DNSType.fromValue(6));
        assertEquals(DNSType.PTR, DNSType.fromValue(12));
        assertEquals(DNSType.MX, DNSType.fromValue(15));
        assertEquals(DNSType.TXT, DNSType.fromValue(16));
        assertEquals(DNSType.AAAA, DNSType.fromValue(28));
        assertEquals(DNSType.OPT, DNSType.fromValue(41));
        assertEquals(DNSType.ANY, DNSType.fromValue(255));
    }
    
    @Test
    public void testFromValueUnknown() {
        assertNull(DNSType.fromValue(0));
        assertNull(DNSType.fromValue(3));
        assertNull(DNSType.fromValue(999));
        assertNull(DNSType.fromValue(-1));
    }
    
    @Test
    public void testAllTypesHaveUniqueValues() {
        DNSType[] types = DNSType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals("Types " + types[i] + " and " + types[j] + " have same value",
                               types[i].getValue(), types[j].getValue());
            }
        }
    }
}

