/*
 * ParameterTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Parameter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParameterTest {

    @Test
    public void testConstructor() {
        Parameter p = new Parameter("charset", "utf-8");
        
        assertEquals("charset", p.getName());
        assertEquals("utf-8", p.getValue());
    }
    
    @Test
    public void testEquals() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("charset", "utf-8");
        
        assertEquals(p1, p2);
    }
    
    @Test
    public void testEqualsNameCaseInsensitive() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("CHARSET", "utf-8");
        
        assertEquals(p1, p2);
    }
    
    @Test
    public void testNotEqualsDifferentName() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("boundary", "utf-8");
        
        assertNotEquals(p1, p2);
    }
    
    @Test
    public void testNotEqualsDifferentValue() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("charset", "iso-8859-1");
        
        assertNotEquals(p1, p2);
    }
    
    @Test
    public void testHashCode() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("charset", "utf-8");
        
        assertEquals(p1.hashCode(), p2.hashCode());
    }
    
    @Test
    public void testHashCodeCaseInsensitiveName() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("CHARSET", "utf-8");
        
        assertEquals(p1.hashCode(), p2.hashCode());
    }
    
    @Test
    public void testToString() {
        Parameter p = new Parameter("charset", "utf-8");
        
        String str = p.toString();
        assertTrue(str.contains("charset"));
        assertTrue(str.contains("utf-8"));
    }
    
    @Test
    public void testToStringWithSpecialChars() {
        Parameter p = new Parameter("boundary", "----=_Part_123");
        
        String str = p.toString();
        assertTrue(str.contains("boundary"));
    }
    
    @Test(expected = NullPointerException.class)
    public void testNullValueThrows() {
        // Parameter does not allow null values
        new Parameter("name", null);
    }
    
    @Test(expected = NullPointerException.class)
    public void testNullNameThrows() {
        // Parameter does not allow null names
        new Parameter(null, "value");
    }
    
    @Test
    public void testEmptyValue() {
        Parameter p = new Parameter("name", "");
        
        assertEquals("name", p.getName());
        assertEquals("", p.getValue());
    }
}

