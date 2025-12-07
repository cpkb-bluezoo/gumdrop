/*
 * MessageIndexTest.java
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageIndex}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path indexPath;
    private MessageIndex index;

    @Before
    public void setUp() throws Exception {
        indexPath = tempFolder.newFile("test.gidx").toPath();
        Files.deleteIfExists(indexPath);
        index = new MessageIndex(indexPath, 1000L, 1L);
    }

    @After
    public void tearDown() throws Exception {
        if (index != null) {
            index = null;
        }
    }

    // ========================================================================
    // Basic Construction Tests
    // ========================================================================

    @Test
    public void testEmptyIndex() {
        assertEquals(0, index.getEntryCount());
        assertEquals(1000L, index.getUidValidity());
        assertEquals(1L, index.getUidNext());
        assertFalse(index.isDirty());
    }

    @Test
    public void testAddEntry() {
        MessageIndexEntry entry = createEntry(1L, 1, "location1", "alice@example.com", "Test Subject 1");
        index.addEntry(entry);

        assertEquals(1, index.getEntryCount());
        assertTrue(index.isDirty());

        MessageIndexEntry retrieved = index.getEntryByUid(1L);
        assertNotNull(retrieved);
        assertEquals(1L, retrieved.getUid());
        assertEquals("alice@example.com", retrieved.getFrom());
    }

    @Test
    public void testAddMultipleEntries() {
        for (int i = 1; i <= 100; i++) {
            MessageIndexEntry entry = createEntry(i, i, "location" + i, 
                "user" + i + "@example.com", "Subject " + i);
            index.addEntry(entry);
        }

        assertEquals(100, index.getEntryCount());

        // Verify random access
        for (int i = 1; i <= 100; i++) {
            MessageIndexEntry entry = index.getEntryByUid(i);
            assertNotNull("Entry " + i + " should exist", entry);
            assertEquals(i, entry.getUid());
            assertEquals(i, entry.getMessageNumber());
        }
    }

    // ========================================================================
    // Save and Load Tests
    // ========================================================================

    @Test
    public void testSaveAndLoad() throws IOException {
        // Add entries
        for (int i = 1; i <= 10; i++) {
            Set<Flag> flags = EnumSet.noneOf(Flag.class);
            if (i % 2 == 0) {
                flags.add(Flag.SEEN);
            }
            if (i % 3 == 0) {
                flags.add(Flag.FLAGGED);
            }
            MessageIndexEntry entry = new MessageIndexEntry(
                i, i, 1000L * i, 1704067200000L + i * 1000, 1704067100000L + i * 1000,
                flags,
                "location" + i,
                "from" + i + "@test.com",
                "to" + i + "@test.com",
                "",
                "",
                "Subject line " + i,
                "<msg" + i + "@test.com>",
                i % 2 == 0 ? "important" : ""
            );
            index.addEntry(entry);
        }

        // Save
        index.save();
        assertFalse(index.isDirty());

        // Load into new index
        MessageIndex loaded = MessageIndex.load(indexPath);

        assertEquals(index.getEntryCount(), loaded.getEntryCount());
        assertEquals(index.getUidValidity(), loaded.getUidValidity());
        assertEquals(index.getUidNext(), loaded.getUidNext());

        // Verify all entries
        for (int i = 1; i <= 10; i++) {
            MessageIndexEntry original = index.getEntryByUid(i);
            MessageIndexEntry restored = loaded.getEntryByUid(i);

            assertNotNull(restored);
            assertEquals(original.getUid(), restored.getUid());
            assertEquals(original.getMessageNumber(), restored.getMessageNumber());
            assertEquals(original.getSize(), restored.getSize());
            assertEquals(original.getInternalDate(), restored.getInternalDate());
            assertEquals(original.getFlags(), restored.getFlags());
            assertEquals(original.getFrom(), restored.getFrom());
            assertEquals(original.getSubject(), restored.getSubject());
        }
    }

    @Test
    public void testSaveEmptyIndex() throws IOException {
        index.save();
        
        assertTrue(Files.exists(indexPath));
        
        MessageIndex loaded = MessageIndex.load(indexPath);
        assertEquals(0, loaded.getEntryCount());
        assertEquals(1000L, loaded.getUidValidity());
    }

    @Test
    public void testMultipleSaves() throws IOException {
        // First save
        index.addEntry(createEntry(1L, 1, "loc1", "a@test.com", "Subject 1"));
        index.save();
        assertFalse(index.isDirty());

        // Second save with more entries
        index.addEntry(createEntry(2L, 2, "loc2", "b@test.com", "Subject 2"));
        assertTrue(index.isDirty());
        index.save();
        assertFalse(index.isDirty());

        // Verify both entries exist after reload
        MessageIndex loaded = MessageIndex.load(indexPath);
        assertEquals(2, loaded.getEntryCount());
        assertNotNull(loaded.getEntryByUid(1L));
        assertNotNull(loaded.getEntryByUid(2L));
    }

    // ========================================================================
    // Flag Update Tests
    // ========================================================================

    @Test
    public void testUpdateFlags() {
        MessageIndexEntry entry = createEntry(1L, 1, "loc", "from@test.com", "Subject");
        index.addEntry(entry);

        assertFalse(index.getEntryByUid(1L).hasFlag(Flag.SEEN));

        index.updateFlags(1L, EnumSet.of(Flag.SEEN, Flag.ANSWERED));

        assertTrue(index.getEntryByUid(1L).hasFlag(Flag.SEEN));
        assertTrue(index.getEntryByUid(1L).hasFlag(Flag.ANSWERED));
        assertFalse(index.getEntryByUid(1L).hasFlag(Flag.FLAGGED));
        assertTrue(index.isDirty());
    }

    @Test
    public void testUpdateFlagsNonExistent() {
        // Should not throw for non-existent UID
        index.updateFlags(999L, EnumSet.of(Flag.SEEN));
        assertFalse(index.isDirty());
    }

    @Test
    public void testUpdateFlagsPersistence() throws IOException {
        MessageIndexEntry entry = createEntry(1L, 1, "loc", "from@test.com", "Subject");
        index.addEntry(entry);
        index.save();

        // Update flags
        index.updateFlags(1L, EnumSet.of(Flag.DELETED, Flag.FLAGGED));
        index.save();

        // Reload and verify
        MessageIndex loaded = MessageIndex.load(indexPath);
        MessageIndexEntry loadedEntry = loaded.getEntryByUid(1L);
        assertTrue(loadedEntry.hasFlag(Flag.DELETED));
        assertTrue(loadedEntry.hasFlag(Flag.FLAGGED));
    }

    // ========================================================================
    // Remove Entry Tests
    // ========================================================================

    @Test
    public void testRemoveEntry() {
        index.addEntry(createEntry(1L, 1, "loc1", "a@test.com", "Subject 1"));
        index.addEntry(createEntry(2L, 2, "loc2", "b@test.com", "Subject 2"));
        index.addEntry(createEntry(3L, 3, "loc3", "c@test.com", "Subject 3"));

        assertEquals(3, index.getEntryCount());

        index.removeEntry(2L);

        assertEquals(2, index.getEntryCount());
        assertNotNull(index.getEntryByUid(1L));
        assertNull(index.getEntryByUid(2L));
        assertNotNull(index.getEntryByUid(3L));
        assertTrue(index.isDirty());
    }

    @Test
    public void testRemoveNonExistentEntry() throws IOException {
        index.addEntry(createEntry(1L, 1, "loc", "a@test.com", "Subject"));
        index.save();
        
        index.removeEntry(999L);
        
        // Entry still exists
        assertEquals(1, index.getEntryCount());
    }

    @Test
    public void testRemoveAllEntries() {
        for (int i = 1; i <= 5; i++) {
            index.addEntry(createEntry(i, i, "loc" + i, "user@test.com", "Subject"));
        }

        for (int i = 1; i <= 5; i++) {
            index.removeEntry(i);
        }

        assertEquals(0, index.getEntryCount());
    }

    // ========================================================================
    // Compact Tests
    // ========================================================================

    @Test
    public void testCompact() {
        // Add 5 messages
        for (int i = 1; i <= 5; i++) {
            index.addEntry(createEntry(i, i, "loc" + i, "user@test.com", "Subject " + i));
        }

        // Remove messages 2 and 4
        index.removeEntry(2L);
        index.removeEntry(4L);

        // Compact (renumber remaining)
        index.compact();

        // Verify renumbering: UIDs 1, 3, 5 should now be message numbers 1, 2, 3
        assertEquals(3, index.getEntryCount());
        
        MessageIndexEntry entry1 = index.getEntryByUid(1L);
        assertNotNull(entry1);
        assertEquals(1, entry1.getMessageNumber());

        MessageIndexEntry entry3 = index.getEntryByUid(3L);
        assertNotNull(entry3);
        assertEquals(2, entry3.getMessageNumber());

        MessageIndexEntry entry5 = index.getEntryByUid(5L);
        assertNotNull(entry5);
        assertEquals(3, entry5.getMessageNumber());
    }

    // ========================================================================
    // Sub-Index Tests (Flag BitSets)
    // ========================================================================

    @Test
    public void testFlagSubIndex() {
        // Create entries with different flag combinations
        index.addEntry(createEntryWithFlags(1L, 1, EnumSet.of(Flag.SEEN)));
        index.addEntry(createEntryWithFlags(2L, 2, EnumSet.of(Flag.SEEN, Flag.FLAGGED)));
        index.addEntry(createEntryWithFlags(3L, 3, EnumSet.of(Flag.FLAGGED)));
        index.addEntry(createEntryWithFlags(4L, 4, EnumSet.noneOf(Flag.class)));
        index.addEntry(createEntryWithFlags(5L, 5, EnumSet.of(Flag.SEEN, Flag.ANSWERED)));

        // Get entries by flag
        List<Long> seenEntries = index.getUidsByFlag(Flag.SEEN);
        assertEquals(3, seenEntries.size());
        assertTrue(seenEntries.contains(1L));
        assertTrue(seenEntries.contains(2L));
        assertTrue(seenEntries.contains(5L));

        List<Long> flaggedEntries = index.getUidsByFlag(Flag.FLAGGED);
        assertEquals(2, flaggedEntries.size());
        assertTrue(flaggedEntries.contains(2L));
        assertTrue(flaggedEntries.contains(3L));

        List<Long> deletedEntries = index.getUidsByFlag(Flag.DELETED);
        assertTrue(deletedEntries.isEmpty());
    }

    @Test
    public void testFlagSubIndexAfterFlagUpdate() {
        index.addEntry(createEntryWithFlags(1L, 1, EnumSet.of(Flag.SEEN)));
        
        List<Long> seenBefore = index.getUidsByFlag(Flag.SEEN);
        assertEquals(1, seenBefore.size());
        
        // Remove SEEN flag, add FLAGGED
        index.updateFlags(1L, EnumSet.of(Flag.FLAGGED));
        
        List<Long> seenAfter = index.getUidsByFlag(Flag.SEEN);
        assertTrue(seenAfter.isEmpty());
        
        List<Long> flaggedAfter = index.getUidsByFlag(Flag.FLAGGED);
        assertEquals(1, flaggedAfter.size());
    }

    // ========================================================================
    // Date Range Query Tests
    // ========================================================================

    @Test
    public void testGetEntriesByDateRange() {
        long baseDate = 1704067200000L; // Jan 1, 2024 00:00:00 UTC
        long dayMs = 24 * 60 * 60 * 1000;

        index.addEntry(createEntryWithDate(1L, 1, baseDate));
        index.addEntry(createEntryWithDate(2L, 2, baseDate + dayMs));
        index.addEntry(createEntryWithDate(3L, 3, baseDate + 2 * dayMs));
        index.addEntry(createEntryWithDate(4L, 4, baseDate + 3 * dayMs));
        index.addEntry(createEntryWithDate(5L, 5, baseDate + 4 * dayMs));

        // Query for dates 2-4
        List<Long> range = index.getUidsByDateRange(baseDate + dayMs, baseDate + 3 * dayMs);
        
        assertEquals(3, range.size());
        assertTrue(range.contains(2L));
        assertTrue(range.contains(3L));
        assertTrue(range.contains(4L));
    }

    // ========================================================================
    // Size Range Query Tests
    // ========================================================================

    @Test
    public void testGetEntriesBySizeRange() {
        index.addEntry(createEntryWithSize(1L, 1, 100));
        index.addEntry(createEntryWithSize(2L, 2, 500));
        index.addEntry(createEntryWithSize(3L, 3, 1000));
        index.addEntry(createEntryWithSize(4L, 4, 5000));
        index.addEntry(createEntryWithSize(5L, 5, 10000));

        // Query for sizes 500-5000
        List<Long> range = index.getUidsBySizeRange(500, 5000);
        
        assertEquals(3, range.size());
        assertTrue(range.contains(2L));
        assertTrue(range.contains(3L));
        assertTrue(range.contains(4L));
    }

    // ========================================================================
    // Corruption Detection Tests
    // ========================================================================

    @Test(expected = MessageIndex.CorruptIndexException.class)
    public void testCorruptMagicNumber() throws IOException {
        // Create a valid index file
        index.addEntry(createEntry(1L, 1, "loc", "user@test.com", "Subject"));
        index.save();

        // Corrupt the magic number
        RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw");
        raf.seek(0);
        raf.writeInt(0xDEADBEEF);
        raf.close();

        // Try to load - should throw
        MessageIndex.load(indexPath);
    }

    @Test(expected = MessageIndex.CorruptIndexException.class)
    public void testCorruptVersion() throws IOException {
        index.addEntry(createEntry(1L, 1, "loc", "user@test.com", "Subject"));
        index.save();

        // Corrupt the version number
        RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw");
        raf.seek(4); // After magic number
        raf.writeShort(9999); // Invalid version
        raf.close();

        MessageIndex.load(indexPath);
    }

    @Test(expected = MessageIndex.CorruptIndexException.class)
    public void testCorruptChecksum() throws IOException {
        index.addEntry(createEntry(1L, 1, "loc", "user@test.com", "Subject"));
        index.save();

        // Corrupt some data in the middle of the file
        long fileSize = Files.size(indexPath);
        if (fileSize > 50) {
            RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw");
            raf.seek(fileSize / 2);
            raf.writeByte(0xFF);
            raf.close();
        }

        MessageIndex.load(indexPath);
    }

    @Test(expected = MessageIndex.CorruptIndexException.class)
    public void testTruncatedFile() throws IOException {
        index.addEntry(createEntry(1L, 1, "loc", "user@test.com", "Subject"));
        index.save();

        // Read original file content
        byte[] bytes = Files.readAllBytes(indexPath);
        
        // Truncate the file by writing only half
        FileOutputStream fos = new FileOutputStream(indexPath.toFile());
        try {
            int truncatedLen = bytes.length / 2;
            if (truncatedLen > 0) {
                fos.write(bytes, 0, truncatedLen);
            }
        } finally {
            fos.close();
        }

        MessageIndex.load(indexPath);
    }

    @Test
    public void testEmptyFileCorruption() throws IOException {
        // Create empty file
        Files.write(indexPath, new byte[0]);
        
        try {
            MessageIndex.load(indexPath);
            fail("Should throw CorruptIndexException for empty file");
        } catch (MessageIndex.CorruptIndexException e) {
            // Expected
        }
    }

    @Test
    public void testNonExistentFile() {
        Path nonExistent = tempFolder.getRoot().toPath().resolve("nonexistent.gidx");
        
        try {
            MessageIndex.load(nonExistent);
            fail("Should throw IOException for non-existent file");
        } catch (IOException e) {
            // Expected
        }
    }

    // ========================================================================
    // Get Entry by Message Number Tests
    // ========================================================================

    @Test
    public void testGetEntryByMessageNumber() {
        index.addEntry(createEntry(100L, 1, "loc1", "a@test.com", "Subject 1"));
        index.addEntry(createEntry(200L, 2, "loc2", "b@test.com", "Subject 2"));
        index.addEntry(createEntry(300L, 3, "loc3", "c@test.com", "Subject 3"));

        MessageIndexEntry entry = index.getEntryByMessageNumber(2);
        assertNotNull(entry);
        assertEquals(200L, entry.getUid());
        assertEquals("Subject 2", entry.getSubject());
    }

    @Test
    public void testGetEntryByMessageNumberNonExistent() {
        index.addEntry(createEntry(1L, 1, "loc", "a@test.com", "Subject"));
        
        MessageIndexEntry entry = index.getEntryByMessageNumber(999);
        assertNull(entry);
    }

    // ========================================================================
    // Iterator Tests
    // ========================================================================

    @Test
    public void testIterateAllEntries() {
        for (int i = 1; i <= 10; i++) {
            index.addEntry(createEntry(i, i, "loc" + i, "user@test.com", "Subject " + i));
        }

        int count = 0;
        for (MessageIndexEntry entry : index.getAllEntries()) {
            assertNotNull(entry);
            count++;
        }

        assertEquals(10, count);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private MessageIndexEntry createEntry(long uid, int msgNum, String location, 
            String from, String subject) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, 1704067200000L, 1704067100000L,
            EnumSet.noneOf(Flag.class),
            location, from, "to@test.com", "", "", subject, "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithFlags(long uid, int msgNum, Set<Flag> flags) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, 1704067200000L, 1704067100000L,
            flags,
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithDate(long uid, int msgNum, long internalDate) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, internalDate, internalDate,
            EnumSet.noneOf(Flag.class),
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithSize(long uid, int msgNum, long size) {
        return new MessageIndexEntry(
            uid, msgNum, size, 1704067200000L, 1704067100000L,
            EnumSet.noneOf(Flag.class),
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

}

