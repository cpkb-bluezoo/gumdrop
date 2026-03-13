package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Flag}.
 */
public class FlagTest {

    @Test
    public void testImapAtoms() {
        assertEquals("\\Seen", Flag.SEEN.getImapAtom());
        assertEquals("\\Answered", Flag.ANSWERED.getImapAtom());
        assertEquals("\\Flagged", Flag.FLAGGED.getImapAtom());
        assertEquals("\\Deleted", Flag.DELETED.getImapAtom());
        assertEquals("\\Draft", Flag.DRAFT.getImapAtom());
        assertEquals("\\Recent", Flag.RECENT.getImapAtom());
    }

    @Test
    public void testFromImapAtom() {
        assertEquals(Flag.SEEN, Flag.fromImapAtom("\\Seen"));
        assertEquals(Flag.ANSWERED, Flag.fromImapAtom("\\Answered"));
        assertEquals(Flag.FLAGGED, Flag.fromImapAtom("\\Flagged"));
        assertEquals(Flag.DELETED, Flag.fromImapAtom("\\Deleted"));
        assertEquals(Flag.DRAFT, Flag.fromImapAtom("\\Draft"));
        assertEquals(Flag.RECENT, Flag.fromImapAtom("\\Recent"));
    }

    @Test
    public void testFromImapAtomCaseInsensitive() {
        assertEquals(Flag.SEEN, Flag.fromImapAtom("\\SEEN"));
        assertEquals(Flag.SEEN, Flag.fromImapAtom("\\seen"));
        assertEquals(Flag.SEEN, Flag.fromImapAtom("\\sEeN"));
        assertEquals(Flag.DELETED, Flag.fromImapAtom("\\DELETED"));
    }

    @Test
    public void testFromImapAtomNull() {
        assertNull(Flag.fromImapAtom(null));
    }

    @Test
    public void testFromImapAtomEmpty() {
        assertNull(Flag.fromImapAtom(""));
    }

    @Test
    public void testFromImapAtomUnknown() {
        assertNull(Flag.fromImapAtom("\\Custom"));
        assertNull(Flag.fromImapAtom("notaflag"));
        assertNull(Flag.fromImapAtom("Seen"));
    }

    @Test
    public void testPermanentFlags() {
        Set<Flag> permanent = Flag.permanentFlags();
        assertEquals(5, permanent.size());
        assertTrue(permanent.contains(Flag.SEEN));
        assertTrue(permanent.contains(Flag.ANSWERED));
        assertTrue(permanent.contains(Flag.FLAGGED));
        assertTrue(permanent.contains(Flag.DELETED));
        assertTrue(permanent.contains(Flag.DRAFT));
        assertFalse(permanent.contains(Flag.RECENT));
    }

    @Test
    public void testAllFlags() {
        Set<Flag> all = Flag.allFlags();
        assertEquals(6, all.size());
        assertTrue(all.contains(Flag.RECENT));
    }

    @Test
    public void testToString() {
        assertEquals("\\Seen", Flag.SEEN.toString());
        assertEquals("\\Draft", Flag.DRAFT.toString());
    }

    @Test
    public void testRoundTrip() {
        for (Flag flag : Flag.values()) {
            assertEquals(flag, Flag.fromImapAtom(flag.getImapAtom()));
        }
    }
}
