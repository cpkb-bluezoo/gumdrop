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
 * Unit tests for {@link DnsQuestion}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSQuestionTest {

    @Test
    public void testConstructorWithAllArgs() {
        DnsQuestion question = new DnsQuestion("example.com", DnsType.MX, DnsClass.IN);
        
        assertEquals("example.com", question.getName());
        assertEquals(DnsType.MX, question.getType());
        assertEquals(DnsClass.IN, question.getDNSClass());
    }
    
    @Test
    public void testConstructorDefaultsToIN() {
        DnsQuestion question = new DnsQuestion("example.com", DnsType.A);
        
        assertEquals(DnsClass.IN, question.getDNSClass());
    }
    
    @Test
    public void testDifferentRecordTypes() {
        DnsQuestion a = new DnsQuestion("example.com", DnsType.A);
        DnsQuestion aaaa = new DnsQuestion("example.com", DnsType.AAAA);
        DnsQuestion mx = new DnsQuestion("example.com", DnsType.MX);
        DnsQuestion txt = new DnsQuestion("example.com", DnsType.TXT);
        DnsQuestion ns = new DnsQuestion("example.com", DnsType.NS);
        
        assertEquals(DnsType.A, a.getType());
        assertEquals(DnsType.AAAA, aaaa.getType());
        assertEquals(DnsType.MX, mx.getType());
        assertEquals(DnsType.TXT, txt.getType());
        assertEquals(DnsType.NS, ns.getType());
    }
    
    @Test
    public void testEqualsSameName() {
        DnsQuestion q1 = new DnsQuestion("example.com", DnsType.A);
        DnsQuestion q2 = new DnsQuestion("example.com", DnsType.A);
        
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
    
    @Test
    public void testEqualsCaseInsensitive() {
        DnsQuestion q1 = new DnsQuestion("example.com", DnsType.A);
        DnsQuestion q2 = new DnsQuestion("EXAMPLE.COM", DnsType.A);
        
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
    
    @Test
    public void testNotEqualsDifferentType() {
        DnsQuestion q1 = new DnsQuestion("example.com", DnsType.A);
        DnsQuestion q2 = new DnsQuestion("example.com", DnsType.AAAA);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testNotEqualsDifferentClass() {
        DnsQuestion q1 = new DnsQuestion("example.com", DnsType.A, DnsClass.IN);
        DnsQuestion q2 = new DnsQuestion("example.com", DnsType.A, DnsClass.CH);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testNotEqualsDifferentName() {
        DnsQuestion q1 = new DnsQuestion("example.com", DnsType.A);
        DnsQuestion q2 = new DnsQuestion("example.org", DnsType.A);
        
        assertNotEquals(q1, q2);
    }
    
    @Test
    public void testToString() {
        DnsQuestion question = new DnsQuestion("www.example.com", DnsType.A);
        String str = question.toString();
        
        assertTrue(str.contains("www.example.com"));
        assertTrue(str.contains("IN"));
        assertTrue(str.contains("A"));
    }
    
    @Test
    public void testAnyQuery() {
        DnsQuestion question = new DnsQuestion("example.com", DnsType.ANY);

        assertEquals(DnsType.ANY, question.getType());
    }

    @Test
    public void testUnicastResponseRequestedDefaultsFalse() {
        DnsQuestion question = new DnsQuestion("example.com", DnsType.A);

        assertFalse(question.isUnicastResponseRequested());
    }

    @Test
    public void testUnicastResponseRequestedTrue() {
        DnsQuestion question = new DnsQuestion(
                "example.local", DnsType.A, DnsClass.IN, true);

        assertTrue(question.isUnicastResponseRequested());
        assertEquals(DnsClass.IN, question.getDNSClass());
    }

    @Test
    public void testEqualsIgnoresUnicastResponseRequested() {
        DnsQuestion q1 = new DnsQuestion(
                "example.local", DnsType.A, DnsClass.IN, true);
        DnsQuestion q2 = new DnsQuestion(
                "example.local", DnsType.A, DnsClass.IN, false);

        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
}

