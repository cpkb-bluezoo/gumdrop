package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MailboxNameCodec}.
 */
public class MailboxNameCodecTest {

    @Test
    public void testEncodeSimpleAscii() {
        assertEquals("INBOX", MailboxNameCodec.encode("INBOX"));
        assertEquals("Drafts", MailboxNameCodec.encode("Drafts"));
        assertEquals("Sent", MailboxNameCodec.encode("Sent"));
    }

    @Test
    public void testEncodeNull() {
        assertNull(MailboxNameCodec.encode(null));
    }

    @Test
    public void testEncodeEmpty() {
        assertEquals("", MailboxNameCodec.encode(""));
    }

    @Test
    public void testEncodePathSeparators() {
        String encoded = MailboxNameCodec.encode("folder/subfolder");
        assertTrue(encoded.contains("=2F"));
        assertFalse(encoded.contains("/"));

        String encodedBackslash = MailboxNameCodec.encode("folder\\subfolder");
        assertTrue(encodedBackslash.contains("=5C"));
    }

    @Test
    public void testEncodeWindowsForbidden() {
        assertTrue(MailboxNameCodec.encode("file:name").contains("=3A"));
        assertTrue(MailboxNameCodec.encode("file*name").contains("=2A"));
        assertTrue(MailboxNameCodec.encode("file?name").contains("=3F"));
        assertTrue(MailboxNameCodec.encode("file\"name").contains("=22"));
        assertTrue(MailboxNameCodec.encode("file<name").contains("=3C"));
        assertTrue(MailboxNameCodec.encode("file>name").contains("=3E"));
        assertTrue(MailboxNameCodec.encode("file|name").contains("=7C"));
    }

    @Test
    public void testEncodeEscapeChar() {
        assertTrue(MailboxNameCodec.encode("a=b").contains("=3D"));
    }

    @Test
    public void testEncodeUnicode() {
        String encoded = MailboxNameCodec.encode("café");
        assertFalse(encoded.equals("café"));
        assertTrue(encoded.startsWith("caf"));
        assertTrue(encoded.contains("="));
    }

    @Test
    public void testDecodeSimple() {
        assertEquals("INBOX", MailboxNameCodec.decode("INBOX"));
    }

    @Test
    public void testDecodeNull() {
        assertNull(MailboxNameCodec.decode(null));
    }

    @Test
    public void testDecodeEmpty() {
        assertEquals("", MailboxNameCodec.decode(""));
    }

    @Test
    public void testDecodeHexSequences() {
        assertEquals("/", MailboxNameCodec.decode("=2F"));
        assertEquals(":", MailboxNameCodec.decode("=3A"));
        assertEquals("=", MailboxNameCodec.decode("=3D"));
    }

    @Test
    public void testRoundTrip() {
        String[] names = {
            "INBOX", "Sent", "Drafts", "Trash",
            "folder/subfolder", "Reports:2025",
            "café", "日本語", "Données/été",
            "a=b", "file*name.txt", "path\\to\\mail"
        };
        for (String name : names) {
            String encoded = MailboxNameCodec.encode(name);
            String decoded = MailboxNameCodec.decode(encoded);
            assertEquals("Round-trip failed for: " + name, name, decoded);
        }
    }

    @Test
    public void testRequiresEncoding() {
        assertFalse(MailboxNameCodec.requiresEncoding("INBOX"));
        assertFalse(MailboxNameCodec.requiresEncoding("simple-name_123"));
        assertTrue(MailboxNameCodec.requiresEncoding("path/to"));
        assertTrue(MailboxNameCodec.requiresEncoding("name:value"));
        assertTrue(MailboxNameCodec.requiresEncoding("café"));
        assertTrue(MailboxNameCodec.requiresEncoding("a=b"));
    }

    @Test
    public void testRequiresEncodingNull() {
        assertFalse(MailboxNameCodec.requiresEncoding(null));
    }

    @Test
    public void testRequiresEncodingEmpty() {
        assertFalse(MailboxNameCodec.requiresEncoding(""));
    }

    @Test
    public void testIsValidEncodedName() {
        assertTrue(MailboxNameCodec.isValidEncodedName("INBOX"));
        assertTrue(MailboxNameCodec.isValidEncodedName("folder=2Fsubfolder"));
        assertTrue(MailboxNameCodec.isValidEncodedName("caf=C3=A9"));
    }

    @Test
    public void testIsValidEncodedNameInvalid() {
        assertFalse(MailboxNameCodec.isValidEncodedName(null));
        assertFalse(MailboxNameCodec.isValidEncodedName(""));
        assertFalse(MailboxNameCodec.isValidEncodedName("bad/path"));
        assertFalse(MailboxNameCodec.isValidEncodedName("trailing="));
        assertFalse(MailboxNameCodec.isValidEncodedName("bad=GZ"));
    }

    @Test
    public void testDecodeInvalidHexPassesThrough() {
        assertEquals("=GZ", MailboxNameCodec.decode("=GZ"));
    }

    @Test
    public void testEncodedOutputSafeCharsOnly() {
        String encoded = MailboxNameCodec.encode("Données/été<>|");
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            assertTrue("Unsafe char in output: " + c,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-=".indexOf(c) >= 0);
        }
    }
}
