package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StoreAction}.
 */
public class StoreActionTest {

    @Test
    public void testFromImapKeyword() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("FLAGS"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+FLAGS"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-FLAGS"));
    }

    @Test
    public void testFromImapKeywordCaseInsensitive() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("flags"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+flags"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-flags"));
    }

    @Test
    public void testFromImapKeywordSilent() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("FLAGS.SILENT"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+FLAGS.SILENT"));
        assertEquals(StoreAction.REMOVE, StoreAction.fromImapKeyword("-FLAGS.SILENT"));
    }

    @Test
    public void testFromImapKeywordSilentCaseInsensitive() {
        assertEquals(StoreAction.REPLACE, StoreAction.fromImapKeyword("flags.silent"));
        assertEquals(StoreAction.ADD, StoreAction.fromImapKeyword("+Flags.Silent"));
    }

    @Test
    public void testFromImapKeywordNull() {
        assertNull(StoreAction.fromImapKeyword(null));
    }

    @Test
    public void testFromImapKeywordEmpty() {
        assertNull(StoreAction.fromImapKeyword(""));
    }

    @Test
    public void testFromImapKeywordUnknown() {
        assertNull(StoreAction.fromImapKeyword("INVALID"));
        assertNull(StoreAction.fromImapKeyword("LABELS"));
    }

    @Test
    public void testGetImapKeyword() {
        assertEquals("FLAGS", StoreAction.REPLACE.getImapKeyword());
        assertEquals("+FLAGS", StoreAction.ADD.getImapKeyword());
        assertEquals("-FLAGS", StoreAction.REMOVE.getImapKeyword());
    }

    @Test
    public void testToString() {
        assertEquals("FLAGS", StoreAction.REPLACE.toString());
        assertEquals("+FLAGS", StoreAction.ADD.toString());
        assertEquals("-FLAGS", StoreAction.REMOVE.toString());
    }

    @Test
    public void testRoundTrip() {
        for (StoreAction action : StoreAction.values()) {
            assertEquals(action, StoreAction.fromImapKeyword(action.getImapKeyword()));
        }
    }
}
