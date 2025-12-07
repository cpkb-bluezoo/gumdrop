/*
 * SearchIndexIntegrationTest.java
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
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Integration tests for the search index system.
 * Tests the complete workflow of building, persisting, loading, searching, 
 * and incrementally updating message indexes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchIndexIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path indexPath;
    private MessageIndex index;
    private MessageIndexBuilder builder;
    private List<TestMessage> corpus;

    @Before
    public void setUp() throws Exception {
        indexPath = tempFolder.newFile("integration.gidx").toPath();
        Files.deleteIfExists(indexPath);
        
        builder = new MessageIndexBuilder();
        corpus = createTestCorpus();
        
        // Build initial index
        index = new MessageIndex(indexPath, 1000L, 1L);
        buildIndexFromCorpus();
    }

    @After
    public void tearDown() throws Exception {
        index = null;
    }

    // ========================================================================
    // Full Workflow Tests
    // ========================================================================

    @Test
    public void testBuildSaveLoadCycle() throws IOException {
        // Save index
        index.save();
        
        // Verify file exists
        assertTrue(Files.exists(indexPath));
        assertTrue(Files.size(indexPath) > 0);
        
        // Load into new instance
        MessageIndex loaded = MessageIndex.load(indexPath);
        
        // Verify counts match
        assertEquals(index.getEntryCount(), loaded.getEntryCount());
        assertEquals(index.getUidValidity(), loaded.getUidValidity());
        
        // Verify all entries present
        for (TestMessage msg : corpus) {
            MessageIndexEntry original = index.getEntryByUid(msg.uid);
            MessageIndexEntry restored = loaded.getEntryByUid(msg.uid);
            
            assertNotNull("Entry " + msg.uid + " should exist after load", restored);
            assertEquals(original.getSubject(), restored.getSubject());
            assertEquals(original.getFrom(), restored.getFrom());
            assertEquals(original.getFlags(), restored.getFlags());
        }
    }

    @Test
    public void testMultipleSaveLoadCycles() throws IOException {
        // First cycle
        index.save();
        MessageIndex loaded1 = MessageIndex.load(indexPath);
        assertEquals(corpus.size(), loaded1.getEntryCount());
        
        // Modify and save again
        loaded1.updateFlags(1L, EnumSet.of(Flag.SEEN, Flag.FLAGGED));
        loaded1.save();
        
        // Second load
        MessageIndex loaded2 = MessageIndex.load(indexPath);
        assertTrue(loaded2.getEntryByUid(1L).hasFlag(Flag.FLAGGED));
        
        // Add entry and save
        MessageIndexEntry newEntry = createEntryFromMessage(
            new TestMessage(100L, 100, "new@test.com", "New Message", 
                "Body", EnumSet.noneOf(Flag.class), "2024-01-15"));
        loaded2.addEntry(newEntry);
        loaded2.save();
        
        // Third load
        MessageIndex loaded3 = MessageIndex.load(indexPath);
        assertEquals(corpus.size() + 1, loaded3.getEntryCount());
        assertNotNull(loaded3.getEntryByUid(100L));
    }

    // ========================================================================
    // Search Tests Using Index
    // ========================================================================

    @Test
    public void testSearchByFlag() {
        // Set up flags (note: message 5 in corpus already has FLAGGED)
        index.updateFlags(1L, EnumSet.of(Flag.SEEN));
        index.updateFlags(2L, EnumSet.of(Flag.SEEN, Flag.FLAGGED));
        index.updateFlags(3L, EnumSet.of(Flag.FLAGGED));
        
        List<Long> seen = index.getUidsByFlag(Flag.SEEN);
        assertEquals(2, seen.size());
        assertTrue(seen.contains(1L));
        assertTrue(seen.contains(2L));
        
        List<Long> flagged = index.getUidsByFlag(Flag.FLAGGED);
        // Messages 2, 3, and 5 (from corpus) have FLAGGED
        assertEquals(3, flagged.size());
        assertTrue(flagged.contains(2L));
        assertTrue(flagged.contains(3L));
        assertTrue(flagged.contains(5L));
        
        List<Long> deleted = index.getUidsByFlag(Flag.DELETED);
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void testSearchByFrom() {
        // Find messages from alice
        List<Long> fromAlice = new ArrayList<>();
        for (MessageIndexEntry entry : index.getAllEntries()) {
            if (entry.getFrom().contains("alice")) {
                fromAlice.add(entry.getUid());
            }
        }
        
        assertFalse(fromAlice.isEmpty());
    }

    @Test
    public void testSearchBySubject() {
        // Find messages with "report" in subject
        List<Long> reportMessages = new ArrayList<>();
        for (MessageIndexEntry entry : index.getAllEntries()) {
            if (entry.getSubject().contains("report")) {
                reportMessages.add(entry.getUid());
            }
        }
        
        // Should find at least one
        assertFalse(reportMessages.isEmpty());
    }

    @Test
    public void testSearchByDateRange() {
        long startDate = parseDate("2024-01-05");
        long endDate = parseDate("2024-01-15");
        
        List<Long> inRange = index.getUidsByDateRange(startDate, endDate);
        
        // Verify all results are in range
        for (Long uid : inRange) {
            MessageIndexEntry entry = index.getEntryByUid(uid);
            long entryDate = entry.getInternalDate();
            assertTrue("Entry date should be >= startDate", entryDate >= startDate);
            assertTrue("Entry date should be <= endDate", entryDate <= endDate);
        }
    }

    @Test
    public void testSearchBySizeRange() {
        // Find messages between 100 and 1000 bytes
        List<Long> mediumSize = index.getUidsBySizeRange(100, 1000);
        
        for (Long uid : mediumSize) {
            MessageIndexEntry entry = index.getEntryByUid(uid);
            assertTrue(entry.getSize() >= 100);
            assertTrue(entry.getSize() <= 1000);
        }
    }

    // ========================================================================
    // Incremental Update Tests
    // ========================================================================

    @Test
    public void testIncrementalAddMessages() throws IOException {
        int initialCount = index.getEntryCount();
        index.save();
        
        // Add new messages
        for (int i = 0; i < 5; i++) {
            long uid = 1000L + i;
            TestMessage msg = new TestMessage(
                uid, initialCount + i + 1,
                "new" + i + "@test.com",
                "New subject " + i,
                "New body",
                EnumSet.noneOf(Flag.class),
                "2024-02-01"
            );
            MessageIndexEntry entry = createEntryFromMessage(msg);
            index.addEntry(entry);
        }
        
        assertEquals(initialCount + 5, index.getEntryCount());
        assertTrue(index.isDirty());
        
        // Save and reload
        index.save();
        MessageIndex reloaded = MessageIndex.load(indexPath);
        assertEquals(initialCount + 5, reloaded.getEntryCount());
        
        // Verify new entries
        for (int i = 0; i < 5; i++) {
            MessageIndexEntry entry = reloaded.getEntryByUid(1000L + i);
            assertNotNull(entry);
            assertTrue(entry.getSubject().contains("new subject " + i));
        }
    }

    @Test
    public void testIncrementalRemoveMessages() throws IOException {
        int initialCount = index.getEntryCount();
        
        // Remove a few messages
        index.removeEntry(2L);
        index.removeEntry(4L);
        
        assertEquals(initialCount - 2, index.getEntryCount());
        assertNull(index.getEntryByUid(2L));
        assertNull(index.getEntryByUid(4L));
        assertNotNull(index.getEntryByUid(1L));
        assertNotNull(index.getEntryByUid(3L));
        
        // Save and reload
        index.save();
        MessageIndex reloaded = MessageIndex.load(indexPath);
        assertEquals(initialCount - 2, reloaded.getEntryCount());
        assertNull(reloaded.getEntryByUid(2L));
        assertNull(reloaded.getEntryByUid(4L));
    }

    @Test
    public void testIncrementalFlagUpdates() throws IOException {
        // Initial state - no flags
        assertFalse(index.getEntryByUid(1L).hasFlag(Flag.SEEN));
        
        // Mark as seen
        index.updateFlags(1L, EnumSet.of(Flag.SEEN));
        assertTrue(index.getEntryByUid(1L).hasFlag(Flag.SEEN));
        
        // Save and reload
        index.save();
        MessageIndex reloaded = MessageIndex.load(indexPath);
        assertTrue(reloaded.getEntryByUid(1L).hasFlag(Flag.SEEN));
        
        // Clear flags
        reloaded.updateFlags(1L, EnumSet.noneOf(Flag.class));
        assertFalse(reloaded.getEntryByUid(1L).hasFlag(Flag.SEEN));
        
        // Save and reload again
        reloaded.save();
        MessageIndex reloaded2 = MessageIndex.load(indexPath);
        assertFalse(reloaded2.getEntryByUid(1L).hasFlag(Flag.SEEN));
    }

    @Test
    public void testCompactionAfterDeletes() throws IOException {
        // Add 10 messages numbered 1-10
        index = new MessageIndex(indexPath, 1000L, 1L);
        for (int i = 1; i <= 10; i++) {
            TestMessage msg = new TestMessage(
                i, i, "user@test.com", "Subject " + i, "Body", 
                EnumSet.noneOf(Flag.class), "2024-01-01"
            );
            index.addEntry(createEntryFromMessage(msg));
        }
        
        // Remove messages 3, 5, 7
        index.removeEntry(3L);
        index.removeEntry(5L);
        index.removeEntry(7L);
        
        // Compact
        index.compact();
        
        // Verify message numbers are sequential
        assertEquals(7, index.getEntryCount());
        
        int expectedMsgNum = 1;
        for (MessageIndexEntry entry : index.getAllEntries()) {
            assertEquals(expectedMsgNum, entry.getMessageNumber());
            expectedMsgNum++;
        }
        
        // Save and reload, verify compaction persisted
        index.save();
        MessageIndex reloaded = MessageIndex.load(indexPath);
        assertEquals(7, reloaded.getEntryCount());
    }

    // ========================================================================
    // Flag Sub-Index Integration Tests
    // ========================================================================

    @Test
    public void testFlagSubIndexConsistency() throws IOException {
        // Set various flags
        index.updateFlags(1L, EnumSet.of(Flag.SEEN));
        index.updateFlags(2L, EnumSet.of(Flag.SEEN, Flag.ANSWERED));
        index.updateFlags(3L, EnumSet.of(Flag.FLAGGED));
        index.updateFlags(4L, EnumSet.of(Flag.DELETED));
        
        // Save and reload
        index.save();
        MessageIndex reloaded = MessageIndex.load(indexPath);
        
        // Verify flag sub-indexes match entry flags
        List<Long> seen = reloaded.getUidsByFlag(Flag.SEEN);
        for (Long uid : seen) {
            assertTrue(reloaded.getEntryByUid(uid).hasFlag(Flag.SEEN));
        }
        
        List<Long> deleted = reloaded.getUidsByFlag(Flag.DELETED);
        assertEquals(1, deleted.size());
        assertTrue(deleted.contains(4L));
    }

    @Test
    public void testFlagSubIndexAfterRemove() {
        // Mark message 1 as SEEN
        index.updateFlags(1L, EnumSet.of(Flag.SEEN));
        assertEquals(1, index.getUidsByFlag(Flag.SEEN).size());
        
        // Remove the message
        index.removeEntry(1L);
        
        // SEEN flag index should be empty for that UID
        List<Long> seen = index.getUidsByFlag(Flag.SEEN);
        assertFalse(seen.contains(1L));
    }

    // ========================================================================
    // Search Context Integration Tests
    // ========================================================================

    @Test
    public void testIndexedMessageContextSearch() throws IOException {
        // Set up some flags
        index.updateFlags(1L, EnumSet.of(Flag.SEEN));
        
        // Create search context from indexed entry
        MessageIndexEntry entry = index.getEntryByUid(1L);
        IndexedMessageContext context = new IndexedMessageContext(entry);
        
        // Test flag-based criteria
        assertTrue(context.getFlags().contains(Flag.SEEN));
        
        // Test header access
        TestMessage originalMsg = corpus.get(0);
        assertTrue(context.getHeader("From").contains("alice"));
    }

    // ========================================================================
    // Edge Cases and Error Handling
    // ========================================================================

    @Test
    public void testEmptyIndexPersistence() throws IOException {
        // Create empty index
        MessageIndex empty = new MessageIndex(indexPath, 1000L, 1L);
        empty.save();
        
        // Load empty index
        MessageIndex loaded = MessageIndex.load(indexPath);
        assertEquals(0, loaded.getEntryCount());
        assertEquals(1000L, loaded.getUidValidity());
    }

    @Test
    public void testLargeIndexPersistence() throws IOException {
        // Create index with many entries
        MessageIndex large = new MessageIndex(indexPath, 1000L, 1L);
        
        for (int i = 1; i <= 1000; i++) {
            TestMessage msg = new TestMessage(
                i, i,
                "user" + i + "@example.com",
                "Subject number " + i + " with some additional text",
                "Body content for message " + i,
                i % 2 == 0 ? EnumSet.of(Flag.SEEN) : EnumSet.noneOf(Flag.class),
                "2024-01-01"
            );
            large.addEntry(createEntryFromMessage(msg));
        }
        
        // Save
        large.save();
        
        // Load and verify
        MessageIndex loaded = MessageIndex.load(indexPath);
        assertEquals(1000, loaded.getEntryCount());
        
        // Spot check some entries
        assertNotNull(loaded.getEntryByUid(1L));
        assertNotNull(loaded.getEntryByUid(500L));
        assertNotNull(loaded.getEntryByUid(1000L));
        
        // Check flags
        assertFalse(loaded.getEntryByUid(1L).hasFlag(Flag.SEEN));
        assertTrue(loaded.getEntryByUid(2L).hasFlag(Flag.SEEN));
    }

    @Test
    public void testCorruptionRecovery() throws IOException {
        // Save valid index
        index.save();
        
        // Corrupt the magic bytes at the start of the file
        byte[] bytes = Files.readAllBytes(indexPath);
        if (bytes.length >= 4) {
            // Corrupt magic number - this will definitely be detected
            bytes[0] = (byte) 0xDE;
            bytes[1] = (byte) 0xAD;
            bytes[2] = (byte) 0xBE;
            bytes[3] = (byte) 0xEF;
            Files.write(indexPath, bytes);
        }
        
        // Try to load - should throw
        try {
            MessageIndex.load(indexPath);
            fail("Should throw CorruptIndexException");
        } catch (MessageIndex.CorruptIndexException e) {
            // Expected - in real usage, we would rebuild here
        }
    }

    @Test
    public void testRapidFlagChanges() throws IOException {
        // Rapidly change flags on same message
        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                index.updateFlags(1L, EnumSet.of(Flag.SEEN));
            } else {
                index.updateFlags(1L, EnumSet.noneOf(Flag.class));
            }
        }
        
        // Should end without SEEN flag
        assertFalse(index.getEntryByUid(1L).hasFlag(Flag.SEEN));
        
        // Save and reload
        index.save();
        MessageIndex loaded = MessageIndex.load(indexPath);
        assertFalse(loaded.getEntryByUid(1L).hasFlag(Flag.SEEN));
    }

    // ========================================================================
    // Concurrent-Like Access Pattern Tests
    // ========================================================================

    @Test
    public void testInterleavedReadsAndWrites() throws IOException {
        for (int i = 0; i < 50; i++) {
            // Read
            MessageIndexEntry entry = index.getEntryByUid(1L);
            assertNotNull(entry);
            
            // Write (update flags)
            index.updateFlags(1L, EnumSet.of(Flag.SEEN));
            
            // Read again
            entry = index.getEntryByUid(1L);
            assertTrue(entry.hasFlag(Flag.SEEN));
            
            // Write (remove flag)
            index.updateFlags(1L, EnumSet.noneOf(Flag.class));
            
            // Read date range
            index.getUidsByDateRange(0L, System.currentTimeMillis());
            
            // Add entry
            TestMessage msg = new TestMessage(
                1000L + i, 100 + i, "test@test.com", "Subject", "Body",
                EnumSet.noneOf(Flag.class), "2024-01-01"
            );
            index.addEntry(createEntryFromMessage(msg));
        }
        
        // Verify state is consistent
        assertEquals(corpus.size() + 50, index.getEntryCount());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private List<TestMessage> createTestCorpus() {
        List<TestMessage> messages = new ArrayList<>();
        
        messages.add(new TestMessage(1L, 1, "alice@example.com", 
            "Weekly Status Report", "Here is the report...", 
            EnumSet.noneOf(Flag.class), "2024-01-10"));
        
        messages.add(new TestMessage(2L, 2, "bob@example.com",
            "Re: Weekly Status Report", "Thanks for the update.",
            EnumSet.noneOf(Flag.class), "2024-01-11"));
        
        messages.add(new TestMessage(3L, 3, "charlie@example.com",
            "Meeting Request", "Can we meet tomorrow?",
            EnumSet.noneOf(Flag.class), "2024-01-12"));
        
        messages.add(new TestMessage(4L, 4, "alice@example.com",
            "Project Update", "The project is on track.",
            EnumSet.noneOf(Flag.class), "2024-01-13"));
        
        messages.add(new TestMessage(5L, 5, "dave@example.com",
            "Urgent: Server Down", "Need help ASAP!",
            EnumSet.of(Flag.FLAGGED), "2024-01-14"));
        
        return messages;
    }

    private void buildIndexFromCorpus() throws IOException {
        for (TestMessage msg : corpus) {
            MessageIndexEntry entry = createEntryFromMessage(msg);
            index.addEntry(entry);
        }
    }

    private MessageIndexEntry createEntryFromMessage(TestMessage msg) throws IOException {
        String rawMessage = buildRawMessage(msg);
        ReadableByteChannel channel = createChannel(rawMessage);
        
        return builder.buildEntry(
            msg.uid,
            msg.messageNumber,
            rawMessage.length(),
            parseDate(msg.date),
            msg.flags,
            "location-" + msg.uid,
            channel
        );
    }

    private String buildRawMessage(TestMessage msg) {
        return "From: " + msg.from + "\r\n" +
               "To: recipient@example.com\r\n" +
               "Subject: " + msg.subject + "\r\n" +
               "Date: " + msg.date + "\r\n" +
               "Message-ID: <msg" + msg.uid + "@example.com>\r\n" +
               "\r\n" +
               msg.body + "\r\n";
    }

    private ReadableByteChannel createChannel(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayChannel(bytes);
    }

    /**
     * A simple channel backed by a byte array that works correctly with NIO patterns.
     */
    private static class ByteArrayChannel implements ReadableByteChannel {
        private final java.io.ByteArrayInputStream input;
        private static final int CHUNK_SIZE = 16;
        private boolean open = true;

        ByteArrayChannel(byte[] data) {
            this.input = new java.io.ByteArrayInputStream(data);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new IOException("Channel closed");
            }
            
            int available = Math.min(dst.remaining(), input.available());
            available = Math.min(available, CHUNK_SIZE);
            
            if (available == 0) {
                return -1;
            }
            
            byte[] temp = new byte[available];
            int read = input.read(temp, 0, available);
            if (read > 0) {
                dst.put(temp, 0, read);
            }
            return read;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private long parseDate(String dateStr) {
        // Simple date parsing for tests: "YYYY-MM-DD"
        String[] parts = dateStr.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        
        OffsetDateTime dt = OffsetDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC);
        return dt.toInstant().toEpochMilli();
    }

    /**
     * Simple test message holder.
     */
    private static class TestMessage {
        final long uid;
        final int messageNumber;
        final String from;
        final String subject;
        final String body;
        final Set<Flag> flags;
        final String date;

        TestMessage(long uid, int messageNumber, String from, String subject,
                String body, Set<Flag> flags, String date) {
            this.uid = uid;
            this.messageNumber = messageNumber;
            this.from = from;
            this.subject = subject;
            this.body = body;
            this.flags = flags;
            this.date = date;
        }
    }

}

