package org.bluezoo.gumdrop.telemetry.metrics;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Attributes}.
 */
public class AttributesTest {

    @Test
    public void testEmpty() {
        Attributes attrs = Attributes.empty();
        assertTrue(attrs.isEmpty());
        assertEquals(0, attrs.size());
        assertEquals("{}", attrs.toString());
    }

    @Test
    public void testEmptyIsSingleton() {
        assertSame(Attributes.empty(), Attributes.empty());
    }

    @Test
    public void testOfNull() {
        assertSame(Attributes.empty(), Attributes.of((Object[]) null));
    }

    @Test
    public void testOfEmpty() {
        assertSame(Attributes.empty(), Attributes.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOfOddLength() {
        Attributes.of("key");
    }

    @Test
    public void testOfStringValue() {
        Attributes attrs = Attributes.of("method", "GET");
        assertEquals(1, attrs.size());
        Attribute a = attrs.asList().get(0);
        assertEquals("method", a.getKey());
        assertEquals(Attribute.TYPE_STRING, a.getType());
        assertEquals("GET", a.getStringValue());
    }

    @Test
    public void testOfLongValue() {
        Attributes attrs = Attributes.of("count", 42L);
        Attribute a = attrs.asList().get(0);
        assertEquals(Attribute.TYPE_INT, a.getType());
        assertEquals(42L, a.getIntValue());
    }

    @Test
    public void testOfIntegerValue() {
        Attributes attrs = Attributes.of("status", Integer.valueOf(200));
        Attribute a = attrs.asList().get(0);
        assertEquals(Attribute.TYPE_INT, a.getType());
        assertEquals(200L, a.getIntValue());
    }

    @Test
    public void testOfDoubleValue() {
        Attributes attrs = Attributes.of("latency", 1.5);
        Attribute a = attrs.asList().get(0);
        assertEquals(Attribute.TYPE_DOUBLE, a.getType());
        assertEquals(1.5, a.getDoubleValue(), 0.0001);
    }

    @Test
    public void testOfBooleanValue() {
        Attributes attrs = Attributes.of("error", true);
        Attribute a = attrs.asList().get(0);
        assertEquals(Attribute.TYPE_BOOL, a.getType());
        assertTrue(a.getBoolValue());
    }

    @Test
    public void testOfMultipleValues() {
        Attributes attrs = Attributes.of(
                "method", "GET",
                "status", 200
        );
        assertEquals(2, attrs.size());
    }

    @Test
    public void testSortedByKey() {
        Attributes attrs = Attributes.of(
                "z_key", "last",
                "a_key", "first",
                "m_key", "middle"
        );
        List<Attribute> list = attrs.asList();
        assertEquals("a_key", list.get(0).getKey());
        assertEquals("m_key", list.get(1).getKey());
        assertEquals("z_key", list.get(2).getKey());
    }

    @Test
    public void testEquals() {
        Attributes a1 = Attributes.of("method", "GET", "status", 200);
        Attributes a2 = Attributes.of("method", "GET", "status", 200);
        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
    }

    @Test
    public void testEqualsDifferentOrder() {
        Attributes a1 = Attributes.of("status", 200, "method", "GET");
        Attributes a2 = Attributes.of("method", "GET", "status", 200);
        assertEquals(a1, a2);
    }

    @Test
    public void testNotEquals() {
        Attributes a1 = Attributes.of("method", "GET");
        Attributes a2 = Attributes.of("method", "POST");
        assertNotEquals(a1, a2);
    }

    @Test
    public void testNotEqualsDifferentKeys() {
        Attributes a1 = Attributes.of("method", "GET");
        Attributes a2 = Attributes.of("verb", "GET");
        assertNotEquals(a1, a2);
    }

    @Test
    public void testOfAttributeList() {
        List<Attribute> list = Arrays.asList(
                Attribute.string("b", "2"),
                Attribute.string("a", "1")
        );
        Attributes attrs = Attributes.of(list);
        assertEquals("a", attrs.asList().get(0).getKey());
        assertEquals("b", attrs.asList().get(1).getKey());
    }

    @Test
    public void testOfEmptyList() {
        assertSame(Attributes.empty(), Attributes.of(Arrays.asList()));
    }

    @Test
    public void testToString() {
        Attributes attrs = Attributes.of("method", "GET");
        assertEquals("{method=GET}", attrs.toString());
    }

    @Test
    public void testImmutability() {
        Attributes attrs = Attributes.of("method", "GET");
        try {
            attrs.asList().add(Attribute.string("extra", "val"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }
}
