/*
 * MessageIndexVersionTest.java
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

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexVersionTest {

    @Test
    public void testLoadRejectsVersion1Index() throws Exception {
        Path path = Files.createTempFile("stale", ".gidx");
        try {
            writeMinimalIndex(path, (short) 1, 0);
            try {
                MessageIndex.load(path);
                fail("Expected CorruptIndexException for stale version");
            } catch (MessageIndex.CorruptIndexException e) {
                assertTrue(e.getMessage().contains("stale"));
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void testSaveAndLoadVersion2() throws Exception {
        Path path = Files.createTempFile("v2", ".gidx");
        try {
            MessageIndex index = new MessageIndex(path, 42L, 100L);
            index.addEntry(new MessageIndexEntry(
                    1L, 1, 100L, 0L, 0L, null,
                    "loc", "from@test.com", "to@test.com", "", "",
                    "subject", "<Msg@Test.COM>",
                    "<parent@test.com> <other@test.com>",
                    "<parent@test.com>", "kw"));
            index.save();
            MessageIndex loaded = MessageIndex.load(path);
            MessageIndexEntry entry = loaded.getEntryByUid(1L);
            assertEquals("<Msg@Test.COM>", entry.getMessageId());
            assertEquals("<parent@test.com> <other@test.com>",
                    entry.getReferences());
            assertEquals("<parent@test.com>", entry.getInReplyTo());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void writeMinimalIndex(Path path, short version,
            int entryCount) throws IOException {
        byte[] magic = {'G', 'I', 'D', 'X'};
        CRC32 crc = new CRC32();
        crc.update(magic);
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(path))) {
            out.write(magic);
            out.writeShort(version);
            updateCrcShort(crc, version);
            out.writeShort(0);
            updateCrcShort(crc, (short) 0);
            long uidValidity = 1L;
            out.writeLong(uidValidity);
            updateCrcLong(crc, uidValidity);
            long uidNext = 2L;
            out.writeLong(uidNext);
            updateCrcLong(crc, uidNext);
            out.writeInt(entryCount);
            updateCrcInt(crc, entryCount);
            out.writeInt((int) crc.getValue());
            out.writeInt(0);
        }
    }

    private static void updateCrcShort(CRC32 crc, short value) {
        crc.update((value >> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    private static void updateCrcInt(CRC32 crc, int value) {
        crc.update((value >> 24) & 0xFF);
        crc.update((value >> 16) & 0xFF);
        crc.update((value >> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    private static void updateCrcLong(CRC32 crc, long value) {
        crc.update((int) ((value >> 56) & 0xFF));
        crc.update((int) ((value >> 48) & 0xFF));
        crc.update((int) ((value >> 40) & 0xFF));
        crc.update((int) ((value >> 32) & 0xFF));
        crc.update((int) ((value >> 24) & 0xFF));
        crc.update((int) ((value >> 16) & 0xFF));
        crc.update((int) ((value >> 8) & 0xFF));
        crc.update((int) (value & 0xFF));
    }
}
