/*
 * FTPFileEntryTest.java
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

package org.bluezoo.gumdrop.ftp.client;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FTPFileEntry}'s NLST/MLSD/LIST line parsers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPFileEntryTest {

    @Test
    public void testParseNlstLine() {
        FTPFileEntry entry = FTPFileEntry.parseNlstLine("readme.txt");
        assertEquals("readme.txt", entry.getName());
        assertEquals(-1, entry.getSize());
        assertFalse(entry.isDirectory());
    }

    @Test
    public void testParseMlsdFile() {
        FTPFileEntry entry = FTPFileEntry.parseMlsdLine(
                "Type=file;Size=1234;Modify=20260101120000; readme.txt");
        assertEquals("readme.txt", entry.getName());
        assertEquals(1234, entry.getSize());
        assertFalse(entry.isDirectory());
        assertEquals("20260101120000", entry.getFact("modify"));
        assertEquals("file", entry.getFact("type"));
    }

    @Test
    public void testParseMlsdDirectory() {
        FTPFileEntry entry = FTPFileEntry.parseMlsdLine("Type=dir;Sizd=0; pub");
        assertEquals("pub", entry.getName());
        assertTrue(entry.isDirectory());
    }

    @Test
    public void testParseMlsdFilenameWithSpaces() {
        FTPFileEntry entry = FTPFileEntry.parseMlsdLine(
                "Type=file;Size=10; my file.txt");
        assertEquals("my file.txt", entry.getName());
    }

    @Test
    public void testParseListFile() {
        FTPFileEntry entry = FTPFileEntry.parseListLine(
                "-rw-r--r-- 1 owner group 1234 Jan 01 00:00 readme.txt");
        assertEquals("readme.txt", entry.getName());
        assertEquals(1234, entry.getSize());
        assertFalse(entry.isDirectory());
    }

    @Test
    public void testParseListDirectory() {
        FTPFileEntry entry = FTPFileEntry.parseListLine(
                "drwxr-xr-x 2 owner group 4096 Jan 01 00:00 pub");
        assertEquals("pub", entry.getName());
        assertTrue(entry.isDirectory());
    }

    @Test
    public void testParseListUnrecognisedFormatFallsBackToWholeLine() {
        FTPFileEntry entry = FTPFileEntry.parseListLine("not a normal listing line");
        assertEquals("not a normal listing line", entry.getName());
        assertEquals(-1, entry.getSize());
    }

    @Test
    public void testToStringReturnsRawLine() {
        String raw = "-rw-r--r-- 1 owner group 1234 Jan 01 00:00 readme.txt";
        FTPFileEntry entry = FTPFileEntry.parseListLine(raw);
        assertEquals(raw, entry.toString());
        assertEquals(raw, entry.getRawLine());
    }
}
