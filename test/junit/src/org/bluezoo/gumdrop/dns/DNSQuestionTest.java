/*
 * DNSQuestionTest.java
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
 * Unit tests for {@link DNSQuestion}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSQuestionTest {

    @Test
    public void testConstructorWithAllArgs() {
        DNSQuestion question = new DNSQuestion("example.com", DNSType.MX, DNSClass.IN);
        
        assertEquals("example.com", question.getName());
        assertEquals(DNSType.MX, question.getType());
        assertEquals(DNSClass.IN, question.getDNSClass());
    }
    
    @Test
    public void testConstructorDefaultsToIN() {
        DNSQuestion question = new DNSQuestion("example.com", DNSType.A);
        
        assertEquals(DNSClass.IN, question.getDNSClass());
    }
    
    @Test
    public void testDifferentRecordTypes() {
        DNSQuestion a = new DNSQuestion("example.com", DNSType.A);
        DNSQuestion aaaa = new DNSQuestion("example.com", DNSType.AAAA);
        DNSQuestion mx = new DNSQuestion("example.com", DNSType.MX);
        DNSQuestion txt = new DNSQuestion("example.com", DNSType.TXT);
        DNSQuestion ns = new DNSQuestion("example.com", DNSType.NS);
        
        assertEquals(DNSType.A, a.getType());
        assertEquals(DNSType.AAAA, aaaa.getType());
        assertEquals(DNSType.MX, mx.getType());
        assertEquals(DNSType.TXT, txt.getType());
        assertEquals(DNSType.NS, ns.getType());
    }
    
    @Test
    public void testEqualsSameName() {
        DNSQuestion q1 = new DNSQuestion("example.com", DNSType.A);
        DNSQuestion q2 = new DNSQuestion("example.com", DNSType.A);
        
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
    
    @Test
    public void testEqualsCaseInsensitive() {
        DNSQuestion q1 = new DNSQuestion("example.com", DNSType.A);
        DNSQuestion q2 = new DNSQuestion("EXAMPLE.COM", DNSType.A);
        
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
    
    @Test
    public void testNotEqualsDifferentType() {
        DNSQuestion q1 = new DNSQuestion("example.com", DNSType.A);
        DNSQuestion q2 = new DNSQuestion("example.com", DNSType.AAAA);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testNotEqualsDifferentClass() {
        DNSQuestion q1 = new DNSQuestion("example.com", DNSType.A, DNSClass.IN);
        DNSQuestion q2 = new DNSQuestion("example.com", DNSType.A, DNSClass.CH);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testNotEqualsDifferentName() {
        DNSQuestion q1 = new DNSQuestion("example.com", DNSType.A);
        DNSQuestion q2 = new DNSQuestion("example.org", DNSType.A);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testToString() {
        DNSQuestion question = new DNSQuestion("www.example.com", DNSType.A);
        String str = question.toString();
        
        assertTrue(str.contains("www.example.com"));
        assertTrue(str.contains("IN"));
        assertTrue(str.contains("A"));
    }
    
    @Test
    public void testAnyQuery() {
        DNSQuestion question = new DNSQuestion("example.com", DNSType.ANY);
        
        assertEquals(DNSType.ANY, question.getType());
    }
}

