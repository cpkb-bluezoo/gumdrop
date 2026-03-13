package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Header}.
 */
public class HeaderTest {

    @Test
    public void testConstruction() {
        Header header = new Header("Content-Type", "text/html");
        assertEquals("Content-Type", header.getName());
        assertEquals("text/html", header.getValue());
    }

    @Test
    public void testNullValue() {
        Header header = new Header("ETag", null);
        assertEquals("ETag", header.getName());
        assertNull(header.getValue());
    }

    @Test(expected = NullPointerException.class)
    public void testNullNameThrows() {
        new Header(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyNameThrows() {
        new Header("", "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidNameThrows() {
        new Header("Content Type", "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValueThrows() {
        new Header("X-Header", "value\0with null");
    }

    @Test
    public void testPseudoHeader() {
        Header header = new Header(":status", "200");
        assertEquals(":status", header.getName());
        assertEquals("200", header.getValue());
    }

    @Test
    public void testEqualsSameCase() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("Content-Type", "text/html");
        assertEquals(h1, h2);
    }

    @Test
    public void testEqualsDifferentCase() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("content-type", "text/html");
        assertEquals(h1, h2);
    }

    @Test
    public void testNotEqualsDifferentValues() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("Content-Type", "application/json");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testNotEqualsDifferentNames() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("Accept", "text/html");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testNotEqualsNull() {
        Header header = new Header("Content-Type", "text/html");
        assertNotEquals(header, null);
    }

    @Test
    public void testNotEqualsOtherType() {
        Header header = new Header("Content-Type", "text/html");
        assertNotEquals(header, "Content-Type: text/html");
    }

    @Test
    public void testHashCodeConsistentWithEquals() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("content-type", "text/html");
        assertEquals(h1, h2);
        assertEquals(h1.hashCode(), h2.hashCode());
    }

    @Test
    public void testHashCodeDifferentForDifferentValues() {
        Header h1 = new Header("Content-Type", "text/html");
        Header h2 = new Header("Content-Type", "application/json");
        assertNotEquals(h1.hashCode(), h2.hashCode());
    }

    @Test
    public void testToString() {
        Header header = new Header("Content-Type", "text/html");
        assertEquals("Content-Type: text/html", header.toString());
    }

    @Test
    public void testToStringNullValue() {
        Header header = new Header("ETag", null);
        assertEquals("ETag: ", header.toString());
    }

    @Test
    public void testHeaderWithTabInValue() {
        Header header = new Header("X-Custom", "value\twith\ttabs");
        assertEquals("value\twith\ttabs", header.getValue());
    }
}
