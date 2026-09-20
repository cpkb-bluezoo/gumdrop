/*
 * BasicFTPFileSystemInMemoryTest.java
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
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bluezoo.gumdrop.ftp.FtpFileInfo;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BasicFTPFileSystem} running against an in-memory
 * file system, so no disk I/O occurs. Real-disk behaviour (symlinks, host
 * permissions) is covered by the integration test of the same subject.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicFTPFileSystemInMemoryTest {

    private MemoryFileSystem mem;
    private Path root;
    private BasicFTPFileSystem fs;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        fs = new BasicFTPFileSystem(root, false);
    }

    private static String read(ReadableByteChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int n = ch.read(buf);
        ch.close();
        return new String(buf.array(), 0, Math.max(n, 0), StandardCharsets.UTF_8);
    }

    private static void write(WritableByteChannel ch, String s) throws IOException {
        ch.write(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)));
        ch.close();
    }

    private static Set<String> names(List<FtpFileInfo> infos) {
        Set<String> names = new HashSet<String>();
        for (FtpFileInfo info : infos) {
            names.add(info.getName());
        }
        return names;
    }

    // Construction

    @Test
    public void testConstructor() {
        assertEquals(root, fs.getRootPath());
        assertFalse(fs.isReadOnly());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNonExistent() {
        new BasicFTPFileSystem(mem.getPath("/nonexistent"), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRootIsFile() throws IOException {
        Path file = Files.createFile(mem.getPath("/plain"));
        new BasicFTPFileSystem(file, false);
    }

    // Listing

    @Test
    public void testListEmptyDirectory() {
        List<FtpFileInfo> files = fs.listDirectory("/", null);
        assertNotNull(files);
        assertTrue(files.isEmpty());
    }

    @Test
    public void testListDirectoryWithFiles() throws IOException {
        Files.createFile(root.resolve("file1.txt"));
        Files.createFile(root.resolve("file2.txt"));
        Files.createDirectory(root.resolve("subdir"));

        List<FtpFileInfo> files = fs.listDirectory("/", null);
        assertNotNull(files);
        Set<String> names = names(files);
        assertEquals(3, files.size());
        assertTrue(names.contains("file1.txt"));
        assertTrue(names.contains("file2.txt"));
        assertTrue(names.contains("subdir"));
    }

    @Test
    public void testListNonExistentDirectory() {
        assertNull(fs.listDirectory("/nonexistent", null));
    }

    @Test
    public void testListFileIsNotADirectory() throws IOException {
        Files.createFile(root.resolve("f"));
        assertNull(fs.listDirectory("/f", null));
    }

    @Test
    public void testListNestedDirectory() throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        Files.createFile(root.resolve("a/b/deep.txt"));
        List<FtpFileInfo> files = fs.listDirectory("/a/b", null);
        assertNotNull(files);
        assertEquals(1, files.size());
        assertEquals("deep.txt", files.get(0).getName());
    }

    // Directory operations

    @Test
    public void testCreateDirectory() {
        assertEquals(FtpFileOperationResult.SUCCESS, fs.createDirectory("/newdir", null));
        assertTrue(Files.isDirectory(root.resolve("newdir")));
    }

    @Test
    public void testCreateDirectoryAlreadyExists() throws IOException {
        Files.createDirectory(root.resolve("existing"));
        assertEquals(FtpFileOperationResult.ALREADY_EXISTS,
                fs.createDirectory("/existing", null));
    }

    @Test
    public void testCreateDirectoryCreatesMissingParents() {
        assertEquals(FtpFileOperationResult.SUCCESS, fs.createDirectory("/no/parent", null));
        assertTrue(Files.isDirectory(root.resolve("no/parent")));
    }

    @Test
    public void testRemoveDirectory() throws IOException {
        Files.createDirectory(root.resolve("toremove"));
        assertEquals(FtpFileOperationResult.SUCCESS, fs.removeDirectory("/toremove", null));
        assertFalse(Files.exists(root.resolve("toremove")));
    }

    @Test
    public void testRemoveDirectoryNotEmpty() throws IOException {
        Path dir = Files.createDirectory(root.resolve("notempty"));
        Files.createFile(dir.resolve("child.txt"));
        assertEquals(FtpFileOperationResult.DIRECTORY_NOT_EMPTY,
                fs.removeDirectory("/notempty", null));
    }

    @Test
    public void testRemoveNonExistentDirectory() {
        assertEquals(FtpFileOperationResult.NOT_FOUND, fs.removeDirectory("/nope", null));
    }

    @Test
    public void testRemoveDirectoryOnFile() throws IOException {
        Files.createFile(root.resolve("f"));
        assertEquals(FtpFileOperationResult.IS_FILE, fs.removeDirectory("/f", null));
    }

    // File operations

    @Test
    public void testDeleteFile() throws IOException {
        Files.createFile(root.resolve("delete-me.txt"));
        assertEquals(FtpFileOperationResult.SUCCESS, fs.deleteFile("/delete-me.txt", null));
        assertFalse(Files.exists(root.resolve("delete-me.txt")));
    }

    @Test
    public void testDeleteFileNotFound() {
        assertEquals(FtpFileOperationResult.NOT_FOUND, fs.deleteFile("/missing.txt", null));
    }

    @Test
    public void testDeleteFileOnDirectory() throws IOException {
        Files.createDirectory(root.resolve("d"));
        assertEquals(FtpFileOperationResult.IS_DIRECTORY, fs.deleteFile("/d", null));
    }

    @Test
    public void testRename() throws IOException {
        Files.createFile(root.resolve("old.txt"));
        assertEquals(FtpFileOperationResult.SUCCESS, fs.rename("/old.txt", "/new.txt", null));
        assertFalse(Files.exists(root.resolve("old.txt")));
        assertTrue(Files.exists(root.resolve("new.txt")));
    }

    @Test
    public void testRenameNotFound() {
        assertEquals(FtpFileOperationResult.NOT_FOUND,
                fs.rename("/missing.txt", "/new.txt", null));
    }

    @Test
    public void testRenameTargetExists() throws IOException {
        Files.createFile(root.resolve("a"));
        Files.createFile(root.resolve("b"));
        assertEquals(FtpFileOperationResult.ALREADY_EXISTS, fs.rename("/a", "/b", null));
        assertTrue(Files.exists(root.resolve("a")));
    }

    @Test
    public void testRenameIntoMissingDirectory() throws IOException {
        Files.createFile(root.resolve("a"));
        assertEquals(FtpFileOperationResult.FILE_SYSTEM_ERROR,
                fs.rename("/a", "/nodir/a", null));
    }

    // Metadata

    @Test
    public void testGetFileInfo() throws IOException {
        Files.write(root.resolve("info.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        FtpFileInfo info = fs.getFileInfo("/info.txt", null);
        assertNotNull(info);
        assertEquals("info.txt", info.getName());
        assertFalse(info.isDirectory());
        assertEquals(5, info.getSize());
        assertEquals("rw-r--r--", info.getPermissions());
    }

    @Test
    public void testGetFileInfoReflectsPermissionBits() throws IOException {
        Path f = Files.createFile(root.resolve("secret"));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        assertEquals("rw-------", fs.getFileInfo("/secret", null).getPermissions());
    }

    @Test
    public void testGetFileInfoDirectory() throws IOException {
        Files.createDirectory(root.resolve("mydir"));
        FtpFileInfo info = fs.getFileInfo("/mydir", null);
        assertNotNull(info);
        assertTrue(info.isDirectory());
        assertEquals("rwxr-xr-x", info.getPermissions());
    }

    @Test
    public void testGetFileInfoNotFound() {
        assertNull(fs.getFileInfo("/nonexistent", null));
    }

    @Test
    public void testFileInfoModifiedTimeComesFromFileSystem() throws IOException {
        Path f = Files.createFile(root.resolve("t"));
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(86400000L));
        assertEquals(java.time.Instant.ofEpochMilli(86400000L),
                fs.getFileInfo("/t", null).getLastModified());
    }

    // Navigation

    @Test
    public void testChangeDirectory() throws IOException {
        Files.createDirectory(root.resolve("sub"));
        FtpFileSystem.DirectoryChangeResult result = fs.changeDirectory("/sub", "/", null);
        assertEquals(FtpFileOperationResult.SUCCESS, result.getResult());
        assertEquals("/sub", result.getNewDirectory());
    }

    @Test
    public void testChangeDirectoryRelative() throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        FtpFileSystem.DirectoryChangeResult result = fs.changeDirectory("b", "/a", null);
        assertEquals(FtpFileOperationResult.SUCCESS, result.getResult());
        assertEquals("/a/b", result.getNewDirectory());
    }

    @Test
    public void testChangeDirectoryToRoot() {
        FtpFileSystem.DirectoryChangeResult result = fs.changeDirectory("/", "/x", null);
        assertEquals(FtpFileOperationResult.SUCCESS, result.getResult());
        assertEquals("/", result.getNewDirectory());
    }

    @Test
    public void testChangeDirectoryNotFound() {
        FtpFileSystem.DirectoryChangeResult result = fs.changeDirectory("/missing", "/", null);
        assertEquals(FtpFileOperationResult.NOT_FOUND, result.getResult());
        assertEquals("/", result.getNewDirectory());
    }

    @Test
    public void testChangeDirectoryIntoFile() throws IOException {
        Files.createFile(root.resolve("f"));
        FtpFileSystem.DirectoryChangeResult result = fs.changeDirectory("/f", "/", null);
        assertEquals(FtpFileOperationResult.IS_FILE, result.getResult());
    }

    // Path containment

    @Test
    public void testPathTraversalDeniedForChangeDirectory() throws IOException {
        Files.createDirectories(mem.getPath("/srv/secret"));
        FtpFileSystem.DirectoryChangeResult result =
                fs.changeDirectory("/../secret", "/", null);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, result.getResult());
        assertEquals("/", result.getNewDirectory());
    }

    @Test
    public void testPathTraversalDeniedForListing() throws IOException {
        Files.createDirectories(mem.getPath("/srv/secret"));
        assertNull(fs.listDirectory("/../secret", null));
    }

    @Test
    public void testPathTraversalDeniedForMutations() throws IOException {
        Files.createFile(mem.getPath("/srv/outside"));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, fs.deleteFile("/../outside", null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.createDirectory("/../newdir", null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/../outside", "/inside", null));
        assertTrue(Files.exists(mem.getPath("/srv/outside")));
        assertFalse(Files.exists(mem.getPath("/srv/newdir")));
    }

    @Test
    public void testPathTraversalDeniedForTransfers() throws IOException {
        Files.write(mem.getPath("/srv/outside"), "x".getBytes(StandardCharsets.UTF_8));
        assertNull(fs.openForReading("/../outside", 0, null));
        assertNull(fs.openForWriting("/../evil", false, null));
        assertFalse(Files.exists(mem.getPath("/srv/evil")));
        assertNull(fs.resolvePathForAsyncRead("/../outside", 0, null));
        assertNull(fs.resolvePathForAsyncWrite("/../evil", false, null));
    }

    @Test
    public void testSiblingDirectoryWithSharedPrefixIsOutsideRoot() throws IOException {
        Files.createDirectories(mem.getPath("/srv/ftp-other"));
        FtpFileSystem.DirectoryChangeResult result =
                fs.changeDirectory("/../ftp-other", "/", null);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, result.getResult());
    }

    // Read-only mode

    @Test
    public void testReadOnlyMutationsDenied() throws IOException {
        Files.createFile(root.resolve("f"));
        BasicFTPFileSystem ro = new BasicFTPFileSystem(root, true);
        assertTrue(ro.isReadOnly());
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, ro.createDirectory("/d", null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, ro.removeDirectory("/d", null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, ro.deleteFile("/f", null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, ro.rename("/f", "/g", null));
        assertNull(ro.openForWriting("/f", false, null));
        assertNull(ro.resolvePathForAsyncWrite("/f", false, null));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                ro.generateUniqueName("/", "f", null).getResult());
        assertTrue(Files.exists(root.resolve("f")));
    }

    @Test
    public void testReadOnlyStillReads() throws IOException {
        Files.write(root.resolve("f"), "data".getBytes(StandardCharsets.UTF_8));
        BasicFTPFileSystem ro = new BasicFTPFileSystem(root, true);
        assertEquals("data", read(ro.openForReading("/f", 0, null)));
        assertNotNull(ro.listDirectory("/", null));
    }

    // Transfers

    @Test
    public void testWriteAndReadFile() throws IOException {
        WritableByteChannel wch = fs.openForWriting("/test.txt", false, null);
        assertNotNull(wch);
        write(wch, "hello world");
        assertEquals("hello world", read(fs.openForReading("/test.txt", 0, null)));
    }

    @Test
    public void testWriteOverwritesExisting() throws IOException {
        write(fs.openForWriting("/f", false, null), "a long first version");
        write(fs.openForWriting("/f", false, null), "short");
        assertEquals("short", read(fs.openForReading("/f", 0, null)));
    }

    @Test
    public void testWriteAppends() throws IOException {
        write(fs.openForWriting("/f", false, null), "abc");
        write(fs.openForWriting("/f", true, null), "def");
        assertEquals("abcdef", read(fs.openForReading("/f", 0, null)));
    }

    @Test
    public void testWriteCreatesMissingParentDirectories() throws IOException {
        write(fs.openForWriting("/x/y/z.txt", false, null), "deep");
        assertEquals("deep", read(fs.openForReading("/x/y/z.txt", 0, null)));
    }

    @Test
    public void testWriteToDirectoryRefused() throws IOException {
        Files.createDirectory(root.resolve("d"));
        assertNull(fs.openForWriting("/d", false, null));
    }

    @Test
    public void testReadWithRestartOffset() throws IOException {
        Files.write(root.resolve("offset.txt"), "hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals("world", read(fs.openForReading("/offset.txt", 6, null)));
    }

    @Test
    public void testReadMissingOrDirectoryRefused() throws IOException {
        Files.createDirectory(root.resolve("d"));
        assertNull(fs.openForReading("/missing", 0, null));
        assertNull(fs.openForReading("/d", 0, null));
    }

    @Test
    public void testAsyncPathResolution() {
        assertEquals(root.resolve("f"), fs.resolvePathForAsyncRead("/f", 0, null));
        assertEquals(root.resolve("f"), fs.resolvePathForAsyncWrite("/f", false, null));
    }

    // Unique names

    @Test
    public void testGenerateUniqueNameNoCollision() {
        FtpFileSystem.UniqueNameResult r = fs.generateUniqueName("/", "report.txt", null);
        assertEquals(FtpFileOperationResult.SUCCESS, r.getResult());
        assertEquals("/report.txt", r.getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameCounterInsertedBeforeExtension() throws IOException {
        Files.createFile(root.resolve("report.txt"));
        Files.createFile(root.resolve("report_2.txt"));
        FtpFileSystem.UniqueNameResult r = fs.generateUniqueName("/", "report.txt", null);
        assertEquals("/report_3.txt", r.getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameWithoutExtension() throws IOException {
        Files.createFile(root.resolve("README"));
        assertEquals("/README_2", fs.generateUniqueName("/", "README", null).getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameInSubdirectory() throws IOException {
        Files.createDirectory(root.resolve("up"));
        assertEquals("/up/a.bin", fs.generateUniqueName("/up/", "a.bin", null).getUniquePath());
        assertEquals("/up/a.bin", fs.generateUniqueName("/up", "a.bin", null).getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameSanitisesSeparators() {
        FtpFileSystem.UniqueNameResult r = fs.generateUniqueName("/", "../../etc/passwd", null);
        assertEquals(FtpFileOperationResult.SUCCESS, r.getResult());
        assertEquals("/.._.._etc_passwd", r.getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameBlankFallsBackToFile() {
        assertEquals("/file", fs.generateUniqueName("/", "  ", null).getUniquePath());
        assertEquals("/file", fs.generateUniqueName("/", null, null).getUniquePath());
    }

    @Test
    public void testGenerateUniqueNameMissingDirectory() {
        assertEquals(FtpFileOperationResult.NOT_FOUND,
                fs.generateUniqueName("/nodir", "f", null).getResult());
    }

    @Test
    public void testAllocateSpace() {
        assertEquals(FtpFileOperationResult.SUCCESS, fs.allocateSpace("/test", 1024, null));
    }
}
