/*
 * BasicFTPFileSystemErrorTest.java
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


package org.bluezoo.gumdrop.ftp.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ftp.FtpFileInfo;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Error and fallback behaviour of {@link BasicFTPFileSystem} on an in-memory
 * file system: results when the underlying file system reports I/O errors,
 * listings and file information on a volume without POSIX attributes, and
 * null or empty paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicFTPFileSystemErrorTest {

    private MemoryFileSystem mem;
    private Path root;
    private BasicFTPFileSystem fs;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        Files.write(root.resolve("a.txt"), "hello".getBytes(StandardCharsets.US_ASCII));
        Files.createDirectories(root.resolve("dir"));
        fs = new BasicFTPFileSystem(root, false);
    }

    @Test
    public void nullAndEmptyPathsResolveToTheRoot() {
        List<FtpFileInfo> byNull = fs.listDirectory(null, null);
        List<FtpFileInfo> byEmpty = fs.listDirectory("", null);
        assertNotNull(byNull);
        assertEquals(2, byNull.size());
        assertEquals(2, byEmpty.size());
    }

    @Test
    public void volumeWithoutPosixAttributesStillListsAndDescribesFiles() {
        mem.disablePosixAttributes();
        FtpFileInfo file = fs.getFileInfo("/a.txt", null);
        assertNotNull(file);
        assertEquals("a.txt", file.getName());
        assertEquals(5L, file.getSize());
        assertFalse(file.isDirectory());
        FtpFileInfo dir = fs.getFileInfo("/dir", null);
        assertNotNull(dir);
        assertTrue(dir.isDirectory());
        assertEquals(2, fs.listDirectory("/", null).size());
        assertNull(fs.getFileInfo("/missing", null));
    }

    @Test
    public void directoryOperationsReportIoErrors() {
        mem.failIoUnder(root.resolve("dir"));
        mem.failIoUnder(root.resolve("a.txt"));
        mem.failIoUnder(root.resolve("fresh"));
        assertEquals(FtpFileOperationResult.FILE_SYSTEM_ERROR, fs.createDirectory("/fresh", null));
        assertEquals(FtpFileOperationResult.FILE_SYSTEM_ERROR, fs.removeDirectory("/dir", null));
        assertEquals(FtpFileOperationResult.FILE_SYSTEM_ERROR, fs.deleteFile("/a.txt", null));
        List<FtpFileInfo> listing = fs.listDirectory("/dir", null);
        assertNotNull(listing);
        assertTrue(listing.isEmpty());
    }

    @Test
    public void transfersReportIoErrorsAsNoChannel() {
        mem.failIoUnder(root.resolve("a.txt"));
        mem.failIoUnder(root.resolve("new.txt"));
        assertNull(fs.openForReading("/a.txt", 0, null));
        assertNull(fs.openForWriting("/new.txt", false, null));
    }

    @Test
    public void fallbackPermissionsFollowTheProcessAccessRights() throws IOException {
        Path locked = root.resolve("locked.txt");
        Files.write(locked, new byte[1]);
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        Path full = root.resolve("full.txt");
        Files.write(full, new byte[1]);
        Files.setPosixFilePermissions(full, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        mem.disablePosixAttributes();
        assertEquals("---------", fs.getFileInfo("/locked.txt", null).getPermissions());
        assertEquals("rwxrwxr--", fs.getFileInfo("/full.txt", null).getPermissions());
    }

    @Test
    public void uniqueNameThatWouldEscapeTheRootIsDenied() {
        org.bluezoo.gumdrop.ftp.FtpFileSystem.UniqueNameResult r = fs.generateUniqueName("/", "..", null);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, r.getResult());
    }
}
