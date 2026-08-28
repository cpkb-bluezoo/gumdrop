package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MaildirFilename}.
 */
public class MaildirFilenameTest {

    @Test
    public void testParseBasicFilename() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=4523:2,SF");
        assertEquals(1733356800000L, f.getTimestamp());
        assertEquals("12345.1", f.getUniquePart());
        assertEquals(4523, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
        assertTrue(f.getFlags().contains(Flag.FLAGGED));
        assertEquals(2, f.getFlags().size());
    }

    @Test
    public void testParseNoFlags() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=100:2,");
        assertEquals(1733356800000L, f.getTimestamp());
        assertEquals(0, f.getFlags().size());
    }

    @Test
    public void testParseAllFlags() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=100:2,DFRST");
        assertTrue(f.getFlags().contains(Flag.DRAFT));
        assertTrue(f.getFlags().contains(Flag.FLAGGED));
        assertTrue(f.getFlags().contains(Flag.ANSWERED));
        assertTrue(f.getFlags().contains(Flag.SEEN));
        assertTrue(f.getFlags().contains(Flag.DELETED));
        assertEquals(5, f.getFlags().size());
    }

    @Test
    public void testParseKeywords() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=100:2,Sac");
        assertTrue(f.getFlags().contains(Flag.SEEN));
        Set<Integer> kw = f.getKeywordIndices();
        assertTrue(kw.contains(0));   // 'a' -> 0
        assertTrue(kw.contains(2));   // 'c' -> 2
        assertEquals(2, kw.size());
    }

    @Test
    public void testParseNoSize() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1:2,S");
        assertEquals(-1, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
    }

    @Test
    public void testParseNoInfoSeparator() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=500");
        assertEquals(500, f.getSize());
        assertEquals(0, f.getFlags().size());
    }

    // ── issue #287: ",2," is also accepted, on every platform, as an
    // alternative to ":2," -- the form this JVM generates on Windows,
    // where a literal ':' can't appear in a filename at all. Parsing
    // must accept it everywhere (not just on Windows) so a fixture
    // committed in that form -- e.g. specifically so a git clone/checkout
    // on Windows doesn't fail on an illegal ':' in a tracked filename --
    // reads identically on every platform, including this test's own.

    @Test
    public void testParseCommaFormSeparator() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=4523,2,SF");
        assertEquals(1733356800000L, f.getTimestamp());
        assertEquals("12345.1", f.getUniquePart());
        assertEquals(4523, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
        assertTrue(f.getFlags().contains(Flag.FLAGGED));
        assertEquals(2, f.getFlags().size());
    }

    @Test
    public void testParseCommaFormNoFlags() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=100,2,");
        assertEquals(1733356800000L, f.getTimestamp());
        assertEquals(100, f.getSize());
        assertEquals(0, f.getFlags().size());
    }

    @Test
    public void testParseCommaFormWithKeywords() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,S=100,2,Sac");
        assertTrue(f.getFlags().contains(Flag.SEEN));
        Set<Integer> kw = f.getKeywordIndices();
        assertTrue(kw.contains(0));   // 'a' -> 0
        assertTrue(kw.contains(2));   // 'c' -> 2
        assertEquals(2, kw.size());
    }

    @Test
    public void testParseCommaFormNoSize() {
        MaildirFilename f = new MaildirFilename("1733356800000.12345.1,2,S");
        assertEquals(-1, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
    }

    @Test
    public void testParseCommaFormAndColonFormEquivalent() {
        MaildirFilename colon = new MaildirFilename("1733356800000.12345.1,S=4523:2,SF");
        MaildirFilename comma = new MaildirFilename("1733356800000.12345.1,S=4523,2,SF");
        assertEquals(colon.getTimestamp(), comma.getTimestamp());
        assertEquals(colon.getUniquePart(), comma.getUniquePart());
        assertEquals(colon.getSize(), comma.getSize());
        assertEquals(colon.getFlags(), comma.getFlags());
    }

    @Test
    public void testGenerationUsesColonFormOnNonWindows() {
        // Locks in the non-Windows half of issue #287's design: normal
        // (standard, spec-compliant, interoperable with Dovecot/Courier
        // etc.) behaviour is unchanged on every platform except Windows.
        org.junit.Assume.assumeFalse(isWindows());
        assertEquals(":2,", MaildirFilename.INFO_SEPARATOR);
    }

    @Test
    public void testGenerationUsesCommaFormOnWindows() {
        org.junit.Assume.assumeTrue(isWindows());
        assertEquals(",2,", MaildirFilename.INFO_SEPARATOR);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidNoDot() {
        new MaildirFilename("invalidfilename");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidTimestamp() {
        new MaildirFilename("notanumber.12345.1,S=100:2,");
    }

    @Test
    public void testConstructorWithParameters() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.ANSWERED);
        Set<Integer> keywords = new HashSet<>();
        keywords.add(1);  // 'b'

        MaildirFilename f = new MaildirFilename(1700000000000L, "99.1", 2048, flags, keywords);
        assertEquals(1700000000000L, f.getTimestamp());
        assertEquals("99.1", f.getUniquePart());
        assertEquals(2048, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
        assertTrue(f.getFlags().contains(Flag.ANSWERED));
        assertTrue(f.getKeywordIndices().contains(1));
    }

    @Test
    public void testToString() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.FLAGGED);
        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", 4523,
                flags, Collections.emptySet());
        // MaildirFilename.INFO_SEPARATOR, not a hardcoded ":2,": generation
        // is platform-dependent (issue #287 -- ",2," on Windows, where a
        // literal ':' is illegal in a filename), so this test must remain
        // correct on every platform it runs on, not just this one.
        assertEquals("1733356800000.12345.1,S=4523" + MaildirFilename.INFO_SEPARATOR + "FS",
                f.toString());
    }

    @Test
    public void testToStringAllFlags() {
        Set<Flag> flags = EnumSet.of(Flag.DRAFT, Flag.FLAGGED, Flag.ANSWERED, Flag.SEEN, Flag.DELETED);
        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", 100,
                flags, Collections.emptySet());
        assertEquals("1733356800000.12345.1,S=100" + MaildirFilename.INFO_SEPARATOR + "DFRST",
                f.toString());
    }

    @Test
    public void testToStringWithKeywords() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN);
        Set<Integer> keywords = new HashSet<>();
        keywords.add(0);  // 'a'
        keywords.add(2);  // 'c'

        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", 100,
                flags, keywords);
        assertEquals("1733356800000.12345.1,S=100" + MaildirFilename.INFO_SEPARATOR + "Sac",
                f.toString());
    }

    @Test
    public void testGetBaseFilename() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN);
        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", 4523,
                flags, Collections.emptySet());
        assertEquals("1733356800000.12345.1,S=4523", f.getBaseFilename());
    }

    @Test
    public void testGetBaseFilenameNoSize() {
        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", -1,
                EnumSet.noneOf(Flag.class), Collections.emptySet());
        assertEquals("1733356800000.12345.1", f.getBaseFilename());
    }

    @Test
    public void testWithFlags() {
        Set<Flag> origFlags = EnumSet.of(Flag.SEEN);
        MaildirFilename orig = new MaildirFilename(1733356800000L, "12345.1", 100,
                origFlags, Collections.emptySet());

        Set<Flag> newFlags = EnumSet.of(Flag.SEEN, Flag.ANSWERED);
        MaildirFilename updated = orig.withFlags(newFlags, Collections.emptySet());

        assertEquals(orig.getTimestamp(), updated.getTimestamp());
        assertEquals(orig.getUniquePart(), updated.getUniquePart());
        assertEquals(orig.getSize(), updated.getSize());
        assertTrue(updated.getFlags().contains(Flag.ANSWERED));
        assertFalse(orig.getFlags().contains(Flag.ANSWERED));
    }

    @Test
    public void testRoundTrip() {
        // Built with INFO_SEPARATOR, not a hardcoded ":2," -- a round trip
        // through toString() always re-generates using this platform's
        // separator (issue #287), which only matches a literal ":2,"
        // input on non-Windows platforms.
        String filename = "1733356800000.12345.1,S=4523" + MaildirFilename.INFO_SEPARATOR + "DFRS";
        MaildirFilename parsed = new MaildirFilename(filename);
        assertEquals(filename, parsed.toString());
    }

    @Test
    public void testGenerate() {
        MaildirFilename f = MaildirFilename.generate(1024,
                EnumSet.of(Flag.SEEN), Collections.emptySet());
        assertTrue(f.getTimestamp() > 0);
        assertNotNull(f.getUniquePart());
        assertEquals(1024, f.getSize());
        assertTrue(f.getFlags().contains(Flag.SEEN));
    }

    @Test
    public void testFlagsCopyIsDefensive() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN);
        MaildirFilename f = new MaildirFilename(1733356800000L, "12345.1", 100,
                flags, Collections.emptySet());

        Set<Flag> returned = f.getFlags();
        returned.add(Flag.DELETED);
        assertFalse(f.getFlags().contains(Flag.DELETED));
    }
}
