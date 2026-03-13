package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StatusItem}.
 */
public class StatusItemTest {

    @Test
    public void testFromImapName() {
        assertEquals(StatusItem.MESSAGES, StatusItem.fromImapName("MESSAGES"));
        assertEquals(StatusItem.RECENT, StatusItem.fromImapName("RECENT"));
        assertEquals(StatusItem.UIDNEXT, StatusItem.fromImapName("UIDNEXT"));
        assertEquals(StatusItem.UIDVALIDITY, StatusItem.fromImapName("UIDVALIDITY"));
        assertEquals(StatusItem.UNSEEN, StatusItem.fromImapName("UNSEEN"));
        assertEquals(StatusItem.DELETED, StatusItem.fromImapName("DELETED"));
        assertEquals(StatusItem.SIZE, StatusItem.fromImapName("SIZE"));
        assertEquals(StatusItem.HIGHESTMODSEQ, StatusItem.fromImapName("HIGHESTMODSEQ"));
        assertEquals(StatusItem.APPENDLIMIT, StatusItem.fromImapName("APPENDLIMIT"));
    }

    @Test
    public void testFromImapNameCaseInsensitive() {
        assertEquals(StatusItem.MESSAGES, StatusItem.fromImapName("messages"));
        assertEquals(StatusItem.UIDNEXT, StatusItem.fromImapName("uidnext"));
        assertEquals(StatusItem.UNSEEN, StatusItem.fromImapName("Unseen"));
        assertEquals(StatusItem.HIGHESTMODSEQ, StatusItem.fromImapName("HighestModSeq"));
    }

    @Test
    public void testFromImapNameNull() {
        assertNull(StatusItem.fromImapName(null));
    }

    @Test
    public void testFromImapNameUnknown() {
        assertNull(StatusItem.fromImapName("UNKNOWN"));
        assertNull(StatusItem.fromImapName(""));
        assertNull(StatusItem.fromImapName("FLAGS"));
    }

    @Test
    public void testGetImapName() {
        assertEquals("MESSAGES", StatusItem.MESSAGES.getImapName());
        assertEquals("RECENT", StatusItem.RECENT.getImapName());
        assertEquals("UIDNEXT", StatusItem.UIDNEXT.getImapName());
        assertEquals("UIDVALIDITY", StatusItem.UIDVALIDITY.getImapName());
        assertEquals("UNSEEN", StatusItem.UNSEEN.getImapName());
        assertEquals("DELETED", StatusItem.DELETED.getImapName());
        assertEquals("SIZE", StatusItem.SIZE.getImapName());
        assertEquals("HIGHESTMODSEQ", StatusItem.HIGHESTMODSEQ.getImapName());
        assertEquals("APPENDLIMIT", StatusItem.APPENDLIMIT.getImapName());
    }

    @Test
    public void testToString() {
        assertEquals("MESSAGES", StatusItem.MESSAGES.toString());
        assertEquals("UIDNEXT", StatusItem.UIDNEXT.toString());
    }

    @Test
    public void testRoundTrip() {
        for (StatusItem item : StatusItem.values()) {
            assertEquals(item, StatusItem.fromImapName(item.getImapName()));
        }
    }

    @Test
    public void testValueCount() {
        assertEquals(9, StatusItem.values().length);
    }
}
