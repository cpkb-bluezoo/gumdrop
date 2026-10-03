/*
 * StreamH2WebSocketUpgradeTest.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link MessageIndex} sub-index maintenance and persistence
 * validation: entries sharing a size, date, address or keyword, removal of
 * entries, the range and flag accessors with removed entries in the middle,
 * and every corruption check performed by {@link MessageIndex#load}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexMaintenanceTest {

    private static final long DAY = 24L * 3600L * 1000L;
    private static final long BASE = LocalDate.of(2024, 3, 10).atStartOfDay(ZoneOffset.UTC)
        .toInstant().toEpochMilli();

    private Path path;
    private MessageIndex index;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        path = mem.getPath("/idx/m.gidx");
        Files.createDirectories(path.getParent());
        index = new MessageIndex(path, 500L, 1L);
    }

    private static MessageIndexEntry entry(long uid, int number, long size, long date,
            EnumSet<Flag> flags, String to, String keywords) {
        return new MessageIndexEntry(uid, number, size, date, date + 5L, flags, "loc" + uid,
            "from" + uid + "@x", to, "cc" + uid + "@x", "", "subject " + uid,
            "<" + uid + "@x>", keywords);
    }

    private void addThree() {
        index.addEntry(entry(1L, 1, 100L, BASE, EnumSet.of(Flag.SEEN), "shared@x", "red"));
        index.addEntry(entry(2L, 2, 100L, BASE, EnumSet.of(Flag.FLAGGED), "shared@x", "red,blue"));
        index.addEntry(entry(3L, 3, 300L, BASE + DAY, EnumSet.noneOf(Flag.class), "other@x", ""));
    }

    @Test
    public void removingOneOfSeveralKeepsTheSharedSubIndexEntries() {
        addThree();
        assertTrue(index.removeEntry(1L));
        assertEquals(1, index.search(SearchCriteria.keyword("red")).size());
        assertEquals(1, index.search(SearchCriteria.larger(200L)).size());
        assertEquals(2, index.search(SearchCriteria.since(LocalDate.of(2024, 3, 10))).size());
        assertTrue(index.removeEntry(2L));
        assertEquals(0, index.search(SearchCriteria.keyword("red")).size());
        assertEquals(0, index.search(SearchCriteria.smaller(200L)).size());
        assertEquals(0, index.getEntriesByInternalDateRange(BASE, BASE + 1L).size());
        assertEquals(0, index.getEntriesBySizeRange(0L, 200L).size());
        assertFalse(index.removeEntry(2L));
    }

    @Test
    public void searchSkipsRemovedEntries() {
        addThree();
        index.removeEntry(2L);
        List<Integer> all = index.search(SearchCriteria.all());
        assertEquals(2, all.size());
        assertFalse(all.contains(Integer.valueOf(2)));
        List<Integer> text = index.search(SearchCriteria.text("subject"));
        assertEquals(2, text.size());
    }

    @Test
    public void rangeAccessorsIgnoreRemovedEntries() {
        addThree();
        index.removeEntry(2L);
        assertEquals(2, index.getEntriesBySentDateRange(BASE, BASE + 2 * DAY).size());
        assertEquals(1, index.getEntriesBySentDateRange(BASE, BASE + 10L).size());
        assertEquals(2, index.getEntriesBySizeRange(0L, 1000L).size());
        List<Long> byFlag = index.getUidsByFlag(Flag.FLAGGED);
        assertTrue(byFlag.isEmpty());
        assertEquals(1, index.getUidsByFlag(Flag.SEEN).size());
        assertEquals(2, index.getUidsByDateRange(BASE, BASE + 2 * DAY).size());
        assertEquals(2, index.getUidsBySentDateRange(BASE, BASE + 2 * DAY).size());
        assertEquals(2, index.getUidsBySizeRange(0L, 1000L).size());
        assertEquals(1, index.getUidsBySizeRange(250L, 400L).size());
        BitSet seen = index.getEntriesWithFlag(Flag.SEEN);
        assertEquals(1, seen.cardinality());
        BitSet recent = index.getEntriesWithFlag(Flag.RECENT);
        assertTrue(recent.isEmpty());
    }

    @Test
    public void accessorsHandleOutOfRangeAndRemovedEntries() {
        addThree();
        assertNull(index.getEntry(-1));
        assertNull(index.getEntry(3));
        assertNotNull(index.getEntry(0));
        index.removeEntry(1L);
        assertNull(index.getEntry(0));
        int count = 0;
        for (MessageIndexEntry e : index.getAllEntries()) {
            assertNotNull(e);
            count++;
        }
        assertEquals(2, count);
        assertFalse(index.requiresMessageParsing(SearchCriteria.body("x")));
        assertEquals(path, index.getIndexPath());
    }

    @Test
    public void saveRequiresAParentDirectory() {
        MessageIndex orphan = new MessageIndex(MemoryFileSystem.create().getPath("bare.gidx"), 1L, 1L);
        try {
            orphan.save();
            fail("saved without a parent directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no parent"));
        }
    }

    private byte[] savedBytes() throws IOException {
        addThree();
        index.save();
        return Files.readAllBytes(path);
    }

    private void expectCorrupt(byte[] data, String messagePart) throws IOException {
        Files.write(path, data);
        try {
            MessageIndex.load(path);
            fail("corrupt index accepted: " + messagePart);
        } catch (MessageIndex.CorruptIndexException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    @Test
    public void invalidEntryCountIsRejected() throws IOException {
        byte[] data = savedBytes();
        ByteBuffer.wrap(data).putInt(24, -1);
        expectCorrupt(data, "entry count");
    }

    @Test
    public void headerChecksumMismatchIsRejected() throws IOException {
        byte[] data = savedBytes();
        data[10] = (byte) (data[10] ^ 0x55);
        expectCorrupt(data, "checksum");
    }

    @Test
    public void zeroUidIsRejected() throws IOException {
        byte[] data = savedBytes();
        ByteBuffer.wrap(data).putLong(32, 0L);
        expectCorrupt(data, "Invalid UID");
    }

    @Test
    public void duplicateUidIsRejected() throws IOException {
        byte[] data = savedBytes();
        int second = 32 + index.getEntry(0).getSerializedSize();
        ByteBuffer.wrap(data).putLong(second, 1L);
        expectCorrupt(data, "Duplicate UID");
    }

    @Test
    public void uidBeyondUidNextIsRejected() throws IOException {
        byte[] data = savedBytes();
        ByteBuffer.wrap(data).putLong(32, 99L);
        expectCorrupt(data, "uidNext");
    }

    @Test
    public void truncatedEntryIsRejected() throws IOException {
        byte[] data = savedBytes();
        byte[] cut = new byte[32 + 20];
        System.arraycopy(data, 0, cut, 0, cut.length);
        expectCorrupt(cut, "Failed to read entry 0");
    }

    @Test
    public void saveAndLoadPreserveSubIndexes() throws IOException {
        addThree();
        index.save();
        assertFalse(index.isDirty());
        MessageIndex loaded = MessageIndex.load(path);
        assertEquals(3, loaded.getEntryCount());
        assertEquals(2, loaded.search(SearchCriteria.keyword("red")).size());
        assertEquals(1, loaded.getEntriesWithFlag(Flag.SEEN).cardinality());
        Set<Integer> byDate = loaded.getEntriesByInternalDateRange(BASE, BASE + DAY);
        assertEquals(2, byDate.size());
    }
}
