/*
 * AttributeTest.java
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

package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link Attribute}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AttributeTest {

    // ========================================================================
    // String Attribute Tests
    // ========================================================================

    @Test
    public void testStringAttribute() {
        Attribute attr = Attribute.string("key", "value");
        
        assertEquals("key", attr.getKey());
        assertEquals(Attribute.TYPE_STRING, attr.getType());
        assertEquals("value", attr.getStringValue());
        assertEquals("value", attr.getValue());
    }

    @Test
    public void testStringAttributeNullValue() {
        Attribute attr = Attribute.string("key", null);
        
        assertEquals("key", attr.getKey());
        assertEquals(Attribute.TYPE_STRING, attr.getType());
        assertNull(attr.getStringValue());
    }

    @Test
    public void testStringAttributeEmptyValue() {
        Attribute attr = Attribute.string("key", "");
        
        assertEquals("", attr.getStringValue());
    }

    @Test
    public void testStringAttributeUnicode() {
        Attribute attr = Attribute.string("message", "日本語テスト 🎉");
        
        assertEquals("日本語テスト 🎉", attr.getStringValue());
    }

    @Test(expected = IllegalStateException.class)
    public void testStringAttributeWrongTypeAccess() {
        Attribute attr = Attribute.string("key", "value");
        attr.getBoolValue(); // Should throw
    }

    // ========================================================================
    // Boolean Attribute Tests
    // ========================================================================

    @Test
    public void testBoolAttributeTrue() {
        Attribute attr = Attribute.bool("enabled", true);
        
        assertEquals("enabled", attr.getKey());
        assertEquals(Attribute.TYPE_BOOL, attr.getType());
        assertTrue(attr.getBoolValue());
        assertEquals(Boolean.TRUE, attr.getValue());
    }

    @Test
    public void testBoolAttributeFalse() {
        Attribute attr = Attribute.bool("disabled", false);
        
        assertEquals(Attribute.TYPE_BOOL, attr.getType());
        assertFalse(attr.getBoolValue());
        assertEquals(Boolean.FALSE, attr.getValue());
    }

    @Test(expected = IllegalStateException.class)
    public void testBoolAttributeWrongTypeAccess() {
        Attribute attr = Attribute.bool("flag", true);
        attr.getStringValue(); // Should throw
    }

    // ========================================================================
    // Integer Attribute Tests
    // ========================================================================

    @Test
    public void testIntegerAttribute() {
        Attribute attr = Attribute.integer("count", 42);
        
        assertEquals("count", attr.getKey());
        assertEquals(Attribute.TYPE_INT, attr.getType());
        assertEquals(42L, attr.getIntValue());
        assertEquals(Long.valueOf(42L), attr.getValue());
    }

    @Test
    public void testIntegerAttributeZero() {
        Attribute attr = Attribute.integer("zero", 0);
        
        assertEquals(0L, attr.getIntValue());
    }

    @Test
    public void testIntegerAttributeNegative() {
        Attribute attr = Attribute.integer("negative", -100);
        
        assertEquals(-100L, attr.getIntValue());
    }

    @Test
    public void testIntegerAttributeMaxValue() {
        Attribute attr = Attribute.integer("max", Long.MAX_VALUE);
        
        assertEquals(Long.MAX_VALUE, attr.getIntValue());
    }

    @Test
    public void testIntegerAttributeMinValue() {
        Attribute attr = Attribute.integer("min", Long.MIN_VALUE);
        
        assertEquals(Long.MIN_VALUE, attr.getIntValue());
    }

    @Test(expected = IllegalStateException.class)
    public void testIntegerAttributeWrongTypeAccess() {
        Attribute attr = Attribute.integer("count", 42);
        attr.getDoubleValue(); // Should throw
    }

    // ========================================================================
    // Double Attribute Tests
    // ========================================================================

    @Test
    public void testDoubleAttribute() {
        Attribute attr = Attribute.doubleValue("ratio", 3.14159);
        
        assertEquals("ratio", attr.getKey());
        assertEquals(Attribute.TYPE_DOUBLE, attr.getType());
        assertEquals(3.14159, attr.getDoubleValue(), 0.00001);
    }

    @Test
    public void testDoubleAttributeZero() {
        Attribute attr = Attribute.doubleValue("zero", 0.0);
        
        assertEquals(0.0, attr.getDoubleValue(), 0.0);
    }

    @Test
    public void testDoubleAttributeNegative() {
        Attribute attr = Attribute.doubleValue("negative", -123.456);
        
        assertEquals(-123.456, attr.getDoubleValue(), 0.001);
    }

    @Test
    public void testDoubleAttributeInfinity() {
        Attribute attrPos = Attribute.doubleValue("pos_inf", Double.POSITIVE_INFINITY);
        Attribute attrNeg = Attribute.doubleValue("neg_inf", Double.NEGATIVE_INFINITY);
        
        assertEquals(Double.POSITIVE_INFINITY, attrPos.getDoubleValue(), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, attrNeg.getDoubleValue(), 0.0);
    }

    @Test
    public void testDoubleAttributeNaN() {
        Attribute attr = Attribute.doubleValue("nan", Double.NaN);
        
        assertTrue(Double.isNaN(attr.getDoubleValue()));
    }

    @Test(expected = IllegalStateException.class)
    public void testDoubleAttributeWrongTypeAccess() {
        Attribute attr = Attribute.doubleValue("ratio", 1.5);
        attr.getIntValue(); // Should throw
    }

    // ========================================================================
    // Null Key Tests
    // ========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testNullKeyString() {
        Attribute.string(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullKeyBool() {
        Attribute.bool(null, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullKeyInteger() {
        Attribute.integer(null, 42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullKeyDouble() {
        Attribute.doubleValue(null, 1.0);
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Test
    public void testToStringString() {
        Attribute attr = Attribute.string("name", "test");
        
        assertEquals("name=test", attr.toString());
    }

    @Test
    public void testToStringBool() {
        Attribute attr = Attribute.bool("flag", true);
        
        assertEquals("flag=true", attr.toString());
    }

    @Test
    public void testToStringInteger() {
        Attribute attr = Attribute.integer("count", 123);
        
        assertEquals("count=123", attr.toString());
    }

    @Test
    public void testToStringDouble() {
        Attribute attr = Attribute.doubleValue("ratio", 2.5);
        
        assertEquals("ratio=2.5", attr.toString());
    }

    // ========================================================================
    // Type Constants Tests
    // ========================================================================

    @Test
    public void testTypeConstants() {
        // Verify type constants are distinct
        assertNotEquals(Attribute.TYPE_STRING, Attribute.TYPE_BOOL);
        assertNotEquals(Attribute.TYPE_STRING, Attribute.TYPE_INT);
        assertNotEquals(Attribute.TYPE_STRING, Attribute.TYPE_DOUBLE);
        assertNotEquals(Attribute.TYPE_BOOL, Attribute.TYPE_INT);
        assertNotEquals(Attribute.TYPE_BOOL, Attribute.TYPE_DOUBLE);
        assertNotEquals(Attribute.TYPE_INT, Attribute.TYPE_DOUBLE);
    }

}

