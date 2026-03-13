package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageSet}.
 */
public class MessageSetTest {

    @Test
    public void testParseSingle() {
        MessageSet set = MessageSet.parse("5");
        assertTrue(set.isSingle());
        assertFalse(set.hasWildcard());
        assertEquals(1, set.getRanges().size());
        assertEquals("5", set.toImapString());
    }

    @Test
    public void testParseRange() {
        MessageSet set = MessageSet.parse("1:10");
        assertFalse(set.isSingle());
        assertFalse(set.hasWildcard());
        assertEquals("1:10", set.toImapString());
    }

    @Test
    public void testParseWildcard() {
        MessageSet set = MessageSet.parse("*");
        assertTrue(set.hasWildcard());
        assertEquals("*", set.toImapString());
    }

    @Test
    public void testParseWildcardRange() {
        MessageSet set = MessageSet.parse("10:*");
        assertTrue(set.hasWildcard());
        assertEquals("10:*", set.toImapString());
    }

    @Test
    public void testParseComplex() {
        MessageSet set = MessageSet.parse("1:5,7,10:*");
        assertEquals(3, set.getRanges().size());
        assertTrue(set.hasWildcard());
        assertEquals("1:5,7,10:*", set.toImapString());
    }

    @Test
    public void testParseReverseRange() {
        MessageSet set = MessageSet.parse("10:1");
        MessageSet.Range range = set.getRanges().get(0);
        assertEquals(1, range.getStart());
        assertEquals(10, range.getEnd());
    }

    @Test(expected = NullPointerException.class)
    public void testParseNull() {
        MessageSet.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseEmpty() {
        MessageSet.parse("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidNumber() {
        MessageSet.parse("abc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseZero() {
        MessageSet.parse("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseNegative() {
        MessageSet.parse("-1");
    }

    @Test
    public void testContainsSingle() {
        MessageSet set = MessageSet.parse("5");
        assertTrue(set.contains(5, 100));
        assertFalse(set.contains(4, 100));
        assertFalse(set.contains(6, 100));
    }

    @Test
    public void testContainsRange() {
        MessageSet set = MessageSet.parse("3:7");
        assertTrue(set.contains(3, 100));
        assertTrue(set.contains(5, 100));
        assertTrue(set.contains(7, 100));
        assertFalse(set.contains(2, 100));
        assertFalse(set.contains(8, 100));
    }

    @Test
    public void testContainsWildcard() {
        MessageSet set = MessageSet.parse("*");
        assertTrue(set.contains(50, 50));
        assertFalse(set.contains(49, 50));
    }

    @Test
    public void testContainsWildcardRange() {
        MessageSet set = MessageSet.parse("10:*");
        assertTrue(set.contains(10, 50));
        assertTrue(set.contains(50, 50));
        assertTrue(set.contains(30, 50));
        assertFalse(set.contains(9, 50));
    }

    @Test
    public void testContainsComplex() {
        MessageSet set = MessageSet.parse("1:3,7,10:*");
        assertTrue(set.contains(1, 100));
        assertTrue(set.contains(3, 100));
        assertTrue(set.contains(7, 100));
        assertTrue(set.contains(10, 100));
        assertTrue(set.contains(100, 100));
        assertFalse(set.contains(5, 100));
        assertFalse(set.contains(8, 100));
    }

    @Test
    public void testFactorySingle() {
        MessageSet set = MessageSet.single(42);
        assertTrue(set.isSingle());
        assertEquals("42", set.toImapString());
        assertTrue(set.contains(42, 100));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFactorySingleInvalid() {
        MessageSet.single(0);
    }

    @Test
    public void testFactoryRange() {
        MessageSet set = MessageSet.range(5, 10);
        assertEquals("5:10", set.toImapString());
    }

    @Test
    public void testFactoryAll() {
        MessageSet set = MessageSet.all();
        assertTrue(set.hasWildcard());
        assertEquals("1:*", set.toImapString());
        assertTrue(set.contains(1, 100));
        assertTrue(set.contains(100, 100));
    }

    @Test
    public void testFactoryLast() {
        MessageSet set = MessageSet.last();
        assertTrue(set.hasWildcard());
        assertEquals("*", set.toImapString());
    }

    @Test
    public void testRangeIsSingle() {
        MessageSet.Range single = new MessageSet.Range(5, 5);
        assertTrue(single.isSingle());

        MessageSet.Range range = new MessageSet.Range(5, 10);
        assertFalse(range.isSingle());

        MessageSet.Range wildcard = new MessageSet.Range(MessageSet.WILDCARD, MessageSet.WILDCARD);
        assertFalse(wildcard.isSingle());
    }

    @Test
    public void testRangeEquals() {
        MessageSet.Range r1 = new MessageSet.Range(1, 10);
        MessageSet.Range r2 = new MessageSet.Range(1, 10);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    public void testRangeNotEquals() {
        MessageSet.Range r1 = new MessageSet.Range(1, 10);
        MessageSet.Range r2 = new MessageSet.Range(1, 11);
        assertNotEquals(r1, r2);
    }

    @Test
    public void testMessageSetEquals() {
        MessageSet s1 = MessageSet.parse("1:5,7");
        MessageSet s2 = MessageSet.parse("1:5,7");
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    public void testIterable() {
        MessageSet set = MessageSet.parse("1:3,7,10:*");
        int count = 0;
        for (MessageSet.Range range : set) {
            count++;
            assertNotNull(range);
        }
        assertEquals(3, count);
    }

    @Test
    public void testToString() {
        MessageSet set = MessageSet.parse("1:5,7,10:*");
        assertEquals("1:5,7,10:*", set.toString());
    }
}
