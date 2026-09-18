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
 * Unit tests for {@link DnsType}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSTypeTest {

    @Test
    public void testValues() {
        assertEquals(1, DnsType.A.getValue());
        assertEquals(2, DnsType.NS.getValue());
        assertEquals(5, DnsType.CNAME.getValue());
        assertEquals(6, DnsType.SOA.getValue());
        assertEquals(12, DnsType.PTR.getValue());
        assertEquals(15, DnsType.MX.getValue());
        assertEquals(16, DnsType.TXT.getValue());
        assertEquals(28, DnsType.AAAA.getValue());
        assertEquals(33, DnsType.SRV.getValue());
        assertEquals(41, DnsType.OPT.getValue());
        assertEquals(52, DnsType.TLSA.getValue());
        assertEquals(64, DnsType.SVCB.getValue());
        assertEquals(65, DnsType.HTTPS.getValue());
        assertEquals(255, DnsType.ANY.getValue());
    }
    
    @Test
    public void testFromValue() {
        assertEquals(DnsType.A, DnsType.fromValue(1));
        assertEquals(DnsType.NS, DnsType.fromValue(2));
        assertEquals(DnsType.CNAME, DnsType.fromValue(5));
        assertEquals(DnsType.SOA, DnsType.fromValue(6));
        assertEquals(DnsType.PTR, DnsType.fromValue(12));
        assertEquals(DnsType.MX, DnsType.fromValue(15));
        assertEquals(DnsType.TXT, DnsType.fromValue(16));
        assertEquals(DnsType.AAAA, DnsType.fromValue(28));
        assertEquals(DnsType.SRV, DnsType.fromValue(33));
        assertEquals(DnsType.OPT, DnsType.fromValue(41));
        assertEquals(DnsType.TLSA, DnsType.fromValue(52));
        assertEquals(DnsType.ANY, DnsType.fromValue(255));
    }
    
    @Test
    public void testFromValueUnknown() {
        assertNull(DnsType.fromValue(0));
        assertNull(DnsType.fromValue(3));
        assertNull(DnsType.fromValue(999));
        assertNull(DnsType.fromValue(-1));
    }
    
    @Test
    public void testAllTypesHaveUniqueValues() {
        DnsType[] types = DnsType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals("Types " + types[i] + " and " + types[j] + " have same value",
                               types[i].getValue(), types[j].getValue());
            }
        }
    }
}

