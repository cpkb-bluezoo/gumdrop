/*
 * MessageIndexEntryTest.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageIndexEntry}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexEntryTest {

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testEmptyConstruction() {
        MessageIndexEntry entry = new MessageIndexEntry();
        assertEquals(0, entry.getUid());
        assertEquals(0, entry.getMessageNumber());
        assertEquals(0, entry.getSize());
        assertEquals("", entry.getLocation());
        assertEquals("", entry.getFrom());
        assertEquals("", entry.getSubject());
        assertTrue(entry.getFlags().isEmpty());
    }

    @Test
    public void testFullConstruction() {
        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.FLAGGED);
        MessageIndexEntry entry = new MessageIndexEntry(
            12345L,                    // uid
            42,                        // messageNumber
            8192L,                     // size
            1704067200000L,            // internalDate
            1704067100000L,            // sentDate
            flags,
            "1704067200.12345.host",   // location
            "alice@example.com",       // from
            "bob@example.com",         // to
            "charlie@example.com",     // cc
            "",                        // bcc
            "hello world",             // subject
            "<msg123@example.com>",    // messageId
            "important,urgent"         // keywords
        );

        assertEquals(12345L, entry.getUid());
        assertEquals(42, entry.getMessageNumber());
        assertEquals(8192L, entry.getSize());
        assertEquals(1704067200000L, entry.getInternalDate());
        assertEquals(1704067100000L, entry.getSentDate());
        assertEquals(flags, entry.getFlags());
        assertEquals("1704067200.12345.host", entry.getLocation());
        assertEquals("alice@example.com", entry.getFrom());
        assertEquals("bob@example.com", entry.getTo());
        assertEquals("charlie@example.com", entry.getCc());
        assertEquals("", entry.getBcc());
        assertEquals("hello world", entry.getSubject());
        assertEquals("<msg123@example.com>", entry.getMessageId());
        assertEquals("important,urgent", entry.getKeywords());
    }

    // ========================================================================
    // Flag Tests
    // ========================================================================

    @Test
    public void testAllFlags() {
        Set<Flag> allFlags = EnumSet.allOf(Flag.class);
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, allFlags,
            "", "", "", "", "", "", "", ""
        );

        Set<Flag> retrievedFlags = entry.getFlags();
        assertEquals(allFlags, retrievedFlags);

        for (Flag flag : Flag.values()) {
            assertTrue("Should have flag " + flag, entry.hasFlag(flag));
        }
    }

    @Test
    public void testNoFlags() {
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );

        assertTrue(entry.getFlags().isEmpty());

        for (Flag flag : Flag.values()) {
            assertFalse("Should not have flag " + flag, entry.hasFlag(flag));
        }
    }

    @Test
    public void testNullFlags() {
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, null,
            "", "", "", "", "", "", "", ""
        );

        assertTrue(entry.getFlags().isEmpty());
    }

    @Test
    public void testSetFlags() {
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );

        assertFalse(entry.hasFlag(Flag.SEEN));

        entry.setFlags(EnumSet.of(Flag.SEEN, Flag.ANSWERED));
        assertTrue(entry.hasFlag(Flag.SEEN));
        assertTrue(entry.hasFlag(Flag.ANSWERED));
        assertFalse(entry.hasFlag(Flag.FLAGGED));
    }

    // ========================================================================
    // Serialization Tests
    // ========================================================================

    @Test
    public void testSerializationRoundTrip() throws IOException {
        Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.ANSWERED, Flag.FLAGGED);
        MessageIndexEntry original = new MessageIndexEntry(
            99999L,
            123,
            65536L,
            1704067200000L,
            1704067100000L,
            flags,
            "some/path/to/message",
            "sender@test.com",
            "recipient@test.com",
            "cc1@test.com, cc2@test.com",
            "bcc@test.com",
            "Test Subject Line",
            "<unique-id@test.com>",
            "keyword1,keyword2,keyword3"
        );

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        // Verify all fields
        assertEquals(original.getUid(), restored.getUid());
        assertEquals(original.getMessageNumber(), restored.getMessageNumber());
        assertEquals(original.getSize(), restored.getSize());
        assertEquals(original.getInternalDate(), restored.getInternalDate());
        assertEquals(original.getSentDate(), restored.getSentDate());
        assertEquals(original.getFlags(), restored.getFlags());
        assertEquals(original.getLocation(), restored.getLocation());
        assertEquals(original.getFrom(), restored.getFrom());
        assertEquals(original.getTo(), restored.getTo());
        assertEquals(original.getCc(), restored.getCc());
        assertEquals(original.getBcc(), restored.getBcc());
        assertEquals(original.getSubject(), restored.getSubject());
        assertEquals(original.getMessageId(), restored.getMessageId());
        assertEquals(original.getKeywords(), restored.getKeywords());
    }

    @Test
    public void testSerializationWithEmptyStrings() throws IOException {
        MessageIndexEntry original = new MessageIndexEntry(
            1L, 1, 0L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        assertEquals("", restored.getLocation());
        assertEquals("", restored.getFrom());
        assertEquals("", restored.getSubject());
    }

    @Test
    public void testSerializationWithNullStrings() throws IOException {
        MessageIndexEntry original = new MessageIndexEntry(
            1L, 1, 0L, 0L, 0L, null,
            null, null, null, null, null, null, null, null
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        assertEquals("", restored.getLocation());
        assertEquals("", restored.getFrom());
    }

    // ========================================================================
    // UTF-8 Tests
    // ========================================================================

    @Test
    public void testUTF8Encoding() throws IOException {
        MessageIndexEntry original = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "path/日本語/テスト",           // Japanese
            "utilisateur@exemple.fr",      // French
            "пользователь@пример.ru",      // Russian
            "用户@示例.cn",                 // Chinese
            "",
            "Ελληνικά θέμα",               // Greek subject
            "<日本語@example.com>",
            "重要,緊急"                     // Keywords in Japanese
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        assertEquals("path/日本語/テスト", restored.getLocation());
        assertEquals("utilisateur@exemple.fr", restored.getFrom());
        assertEquals("пользователь@пример.ru", restored.getTo());
        assertEquals("用户@示例.cn", restored.getCc());
        assertEquals("Ελληνικά θέμα", restored.getSubject());
        assertEquals("<日本語@example.com>", restored.getMessageId());
        assertEquals("重要,緊急", restored.getKeywords());
    }

    @Test
    public void testUTF8WithEmoji() throws IOException {
        MessageIndexEntry original = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "location",
            "user@example.com",
            "recipient@example.com",
            "",
            "",
            "🎉 Party invitation! 🎂",
            "<msg@example.com>",
            "party,celebration,🎉"
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        assertEquals("🎉 Party invitation! 🎂", restored.getSubject());
        assertEquals("party,celebration,🎉", restored.getKeywords());
    }

    // ========================================================================
    // Long String Tests
    // ========================================================================

    @Test
    public void testLongStrings() throws IOException {
        // Create a very long subject line (common in spam)
        StringBuilder longSubject = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longSubject.append("word").append(i).append(" ");
        }

        // Create long recipient list
        StringBuilder longTo = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                longTo.append(", ");
            }
            longTo.append("user").append(i).append("@example.com");
        }

        MessageIndexEntry original = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "location",
            "from@example.com",
            longTo.toString(),
            "",
            "",
            longSubject.toString(),
            "<msg@example.com>",
            ""
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeTo(dos);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        MessageIndexEntry restored = MessageIndexEntry.readFrom(dis);

        assertEquals(longSubject.toString(), restored.getSubject());
        assertEquals(longTo.toString(), restored.getTo());
    }

    // ========================================================================
    // Serialized Size Tests
    // ========================================================================

    @Test
    public void testSerializedSize() throws IOException {
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "location123",
            "from@test.com",
            "to@test.com",
            "",
            "",
            "subject",
            "<id@test.com>",
            ""
        );

        int predictedSize = entry.getSerializedSize();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        entry.writeTo(dos);
        dos.flush();

        int actualSize = baos.size();

        assertEquals("Serialized size should match prediction", predictedSize, actualSize);
    }

    // ========================================================================
    // Message Number Mutability Test
    // ========================================================================

    @Test
    public void testMessageNumberMutable() {
        MessageIndexEntry entry = new MessageIndexEntry(
            1L, 1, 100L, 0L, 0L, EnumSet.noneOf(Flag.class),
            "", "", "", "", "", "", "", ""
        );

        assertEquals(1, entry.getMessageNumber());
        
        entry.setMessageNumber(42);
        assertEquals(42, entry.getMessageNumber());
        
        entry.setMessageNumber(999);
        assertEquals(999, entry.getMessageNumber());
    }

    // ========================================================================
    // ToString Test
    // ========================================================================

    @Test
    public void testToString() {
        MessageIndexEntry entry = new MessageIndexEntry(
            123L, 5, 1024L, 0L, 0L, EnumSet.of(Flag.SEEN),
            "location",
            "alice@example.com",
            "bob@example.com",
            "",
            "",
            "Test Subject",
            "<msg@example.com>",
            ""
        );

        String str = entry.toString();
        
        assertTrue("Should contain uid", str.contains("123"));
        assertTrue("Should contain from", str.contains("alice@example.com"));
        assertTrue("Should contain subject", str.contains("Test Subject"));
    }

}

