/*
 * FTPFileInfoTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.ftp;

import java.time.Instant;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FTPFileInfo}, including RFC 3659 machine-readable
 * listing format (MLST/MLSD).
 */
public class FTPFileInfoTest {

    @Test
    public void testFormatAsMLSEntry_file() {
        Instant modified = Instant.parse("2025-06-15T14:30:00Z");
        FTPFileInfo file = new FTPFileInfo("report.pdf", 204800, modified,
                "alice", "staff", "rw-r--r--");

        String entry = file.formatAsMLSEntry();

        assertTrue("should contain type=file", entry.contains("type=file;"));
        assertTrue("should contain size", entry.contains("size=204800;"));
        assertTrue("should contain modify timestamp",
                entry.contains("modify=20250615143000;"));
        assertTrue("should contain perm", entry.contains("perm="));
        assertTrue("should end with filename", entry.endsWith(" report.pdf"));
        // readable file: r (read)
        assertTrue("readable file should have 'r' perm", entry.contains("perm=r"));
    }

    @Test
    public void testFormatAsMLSEntry_writableFile() {
        Instant modified = Instant.parse("2025-01-01T00:00:00Z");
        FTPFileInfo file = new FTPFileInfo("data.csv", 1024, modified,
                "bob", "users", "rwxr-xr-x");

        String entry = file.formatAsMLSEntry();

        assertTrue("writable file should include w", entry.contains("w"));
        assertTrue("writable file should include a", entry.contains("a"));
        assertTrue("writable file should include d", entry.contains("d"));
        assertTrue("writable file should include f", entry.contains("f"));
    }

    @Test
    public void testFormatAsMLSEntry_directory() {
        Instant modified = Instant.parse("2025-03-20T08:00:00Z");
        FTPFileInfo dir = new FTPFileInfo("docs", modified,
                "alice", "staff", "rwxr-xr-x");

        String entry = dir.formatAsMLSEntry();

        assertTrue("should contain type=dir", entry.contains("type=dir;"));
        assertTrue("should contain size=0", entry.contains("size=0;"));
        assertTrue("should contain modify timestamp",
                entry.contains("modify=20250320080000;"));
        // writable dir: e, l, c, m, p
        assertTrue("dir should have 'e' perm (enter)", entry.contains("e"));
        assertTrue("dir should have 'l' perm (list)", entry.contains("l"));
        assertTrue("writable dir should have 'c' perm (create)", entry.contains("c"));
        assertTrue("writable dir should have 'm' perm (mkdir)", entry.contains("m"));
        assertTrue("writable dir should have 'p' perm (purge)", entry.contains("p"));
        assertTrue("should end with dirname", entry.endsWith(" docs"));
    }

    @Test
    public void testFormatAsMLSEntry_readOnlyDirectory() {
        Instant modified = Instant.parse("2025-02-01T12:00:00Z");
        FTPFileInfo dir = new FTPFileInfo("readonly", modified,
                "root", "root", "r-xr-xr-x");

        String entry = dir.formatAsMLSEntry();

        assertTrue("should have 'e' and 'l'", entry.contains("perm=el;"));
    }

    @Test
    public void testFormatAsMLSEntry_noModifyTime() {
        FTPFileInfo file = new FTPFileInfo("notime.txt", 100, null,
                null, null, null);

        String entry = file.formatAsMLSEntry();

        assertFalse("should not contain modify= when null",
                entry.contains("modify="));
        assertTrue("should contain type=file", entry.contains("type=file;"));
    }

    @Test
    public void testFormatAsListingLine() {
        Instant modified = Instant.parse("2025-01-15T10:30:00Z");
        FTPFileInfo file = new FTPFileInfo("test.txt", 1234, modified,
                "user", "group", "rw-r--r--");

        String line = file.formatAsListingLine();

        assertTrue("should start with '-'", line.startsWith("-"));
        assertTrue("should contain permissions", line.contains("rw-r--r--"));
        assertTrue("should contain filename", line.endsWith("test.txt"));
        assertTrue("should contain size", line.contains("1234"));
    }

    @Test
    public void testDirectoryListingLine() {
        Instant modified = Instant.parse("2025-01-15T10:30:00Z");
        FTPFileInfo dir = new FTPFileInfo("subdir", modified,
                "user", "group", "rwxr-xr-x");

        String line = dir.formatAsListingLine();

        assertTrue("directory should start with 'd'", line.startsWith("d"));
    }

    @Test
    public void testAccessors() {
        Instant now = Instant.now();
        FTPFileInfo file = new FTPFileInfo("file.dat", 999, now,
                "owner1", "group1", "rwx------");

        assertEquals("file.dat", file.getName());
        assertFalse(file.isDirectory());
        assertTrue(file.isFile());
        assertEquals(999, file.getSize());
        assertEquals(now, file.getLastModified());
        assertEquals("owner1", file.getOwner());
        assertEquals("group1", file.getGroup());
        assertEquals("rwx------", file.getPermissions());
    }

    @Test
    public void testDirectoryAccessors() {
        Instant now = Instant.now();
        FTPFileInfo dir = new FTPFileInfo("mydir", now,
                "owner2", "group2", "rwxr-xr-x");

        assertEquals("mydir", dir.getName());
        assertTrue(dir.isDirectory());
        assertFalse(dir.isFile());
        assertEquals(0, dir.getSize());
    }
}
