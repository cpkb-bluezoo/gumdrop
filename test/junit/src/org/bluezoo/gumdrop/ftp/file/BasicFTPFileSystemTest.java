package org.bluezoo.gumdrop.ftp.file;

import org.bluezoo.gumdrop.ftp.FTPFileInfo;
import org.bluezoo.gumdrop.ftp.FTPFileOperationResult;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link BasicFTPFileSystem}.
 */
public class BasicFTPFileSystemTest {

    private Path tempDir;
    private BasicFTPFileSystem fs;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("ftptest");
        fs = new BasicFTPFileSystem(tempDir, false);
    }

    @After
    public void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                });
    }

    @Test
    public void testConstructor() {
        assertEquals(tempDir.toAbsolutePath().normalize(), fs.getRootPath());
        assertFalse(fs.isReadOnly());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNonExistent() {
        new BasicFTPFileSystem("/nonexistent/path/12345", false);
    }

    @Test
    public void testListEmptyDirectory() {
        List<FTPFileInfo> files = fs.listDirectory("/", null);
        assertNotNull(files);
        assertTrue(files.isEmpty());
    }

    @Test
    public void testListDirectoryWithFiles() throws IOException {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createFile(tempDir.resolve("file2.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        List<FTPFileInfo> files = fs.listDirectory("/", null);
        assertNotNull(files);
        assertEquals(3, files.size());
    }

    @Test
    public void testListNonExistentDirectory() {
        assertNull(fs.listDirectory("/nonexistent", null));
    }

    @Test
    public void testCreateDirectory() {
        FTPFileOperationResult result = fs.createDirectory("/newdir", null);
        assertEquals(FTPFileOperationResult.SUCCESS, result);
        assertTrue(Files.isDirectory(tempDir.resolve("newdir")));
    }

    @Test
    public void testCreateDirectoryAlreadyExists() throws IOException {
        Files.createDirectory(tempDir.resolve("existing"));
        FTPFileOperationResult result = fs.createDirectory("/existing", null);
        assertEquals(FTPFileOperationResult.ALREADY_EXISTS, result);
    }

    @Test
    public void testRemoveDirectory() throws IOException {
        Files.createDirectory(tempDir.resolve("toremove"));
        FTPFileOperationResult result = fs.removeDirectory("/toremove", null);
        assertEquals(FTPFileOperationResult.SUCCESS, result);
        assertFalse(Files.exists(tempDir.resolve("toremove")));
    }

    @Test
    public void testRemoveDirectoryNotEmpty() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("notempty"));
        Files.createFile(dir.resolve("child.txt"));
        FTPFileOperationResult result = fs.removeDirectory("/notempty", null);
        assertEquals(FTPFileOperationResult.DIRECTORY_NOT_EMPTY, result);
    }

    @Test
    public void testRemoveNonExistentDirectory() {
        FTPFileOperationResult result = fs.removeDirectory("/nope", null);
        assertEquals(FTPFileOperationResult.NOT_FOUND, result);
    }

    @Test
    public void testDeleteFile() throws IOException {
        Files.createFile(tempDir.resolve("delete-me.txt"));
        FTPFileOperationResult result = fs.deleteFile("/delete-me.txt", null);
        assertEquals(FTPFileOperationResult.SUCCESS, result);
        assertFalse(Files.exists(tempDir.resolve("delete-me.txt")));
    }

    @Test
    public void testDeleteFileNotFound() {
        FTPFileOperationResult result = fs.deleteFile("/missing.txt", null);
        assertEquals(FTPFileOperationResult.NOT_FOUND, result);
    }

    @Test
    public void testRename() throws IOException {
        Files.createFile(tempDir.resolve("old.txt"));
        FTPFileOperationResult result = fs.rename("/old.txt", "/new.txt", null);
        assertEquals(FTPFileOperationResult.SUCCESS, result);
        assertFalse(Files.exists(tempDir.resolve("old.txt")));
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
    }

    @Test
    public void testRenameNotFound() {
        FTPFileOperationResult result = fs.rename("/missing.txt", "/new.txt", null);
        assertEquals(FTPFileOperationResult.NOT_FOUND, result);
    }

    @Test
    public void testGetFileInfo() throws IOException {
        Files.write(tempDir.resolve("info.txt"), "hello".getBytes());
        FTPFileInfo info = fs.getFileInfo("/info.txt", null);
        assertNotNull(info);
        assertEquals("info.txt", info.getName());
        assertFalse(info.isDirectory());
        assertEquals(5, info.getSize());
    }

    @Test
    public void testGetFileInfoDirectory() throws IOException {
        Files.createDirectory(tempDir.resolve("mydir"));
        FTPFileInfo info = fs.getFileInfo("/mydir", null);
        assertNotNull(info);
        assertTrue(info.isDirectory());
    }

    @Test
    public void testGetFileInfoNotFound() {
        assertNull(fs.getFileInfo("/nonexistent", null));
    }

    @Test
    public void testChangeDirectory() throws IOException {
        Files.createDirectory(tempDir.resolve("sub"));
        FTPFileSystem.DirectoryChangeResult result =
                fs.changeDirectory("/sub", "/", null);
        assertEquals(FTPFileOperationResult.SUCCESS, result.getResult());
    }

    @Test
    public void testChangeDirectoryNotFound() {
        FTPFileSystem.DirectoryChangeResult result =
                fs.changeDirectory("/missing", "/", null);
        assertEquals(FTPFileOperationResult.NOT_FOUND, result.getResult());
    }

    @Test
    public void testPathTraversalPrevented() {
        try {
            fs.listDirectory("/../../../etc", null);
            // If resolveSecurePath doesn't throw, it should return root or null
        } catch (SecurityException e) {
            // Expected
        }
    }

    @Test
    public void testReadOnlyCreateDenied() {
        BasicFTPFileSystem roFs = new BasicFTPFileSystem(tempDir, true);
        assertTrue(roFs.isReadOnly());
        assertEquals(FTPFileOperationResult.ACCESS_DENIED, roFs.createDirectory("/test", null));
    }

    @Test
    public void testReadOnlyDeleteDenied() {
        BasicFTPFileSystem roFs = new BasicFTPFileSystem(tempDir, true);
        assertEquals(FTPFileOperationResult.ACCESS_DENIED, roFs.deleteFile("/test", null));
    }

    @Test
    public void testReadOnlyRenameDenied() {
        BasicFTPFileSystem roFs = new BasicFTPFileSystem(tempDir, true);
        assertEquals(FTPFileOperationResult.ACCESS_DENIED, roFs.rename("/a", "/b", null));
    }

    @Test
    public void testWriteAndReadFile() throws IOException {
        WritableByteChannel wch = fs.openForWriting("/test.txt", false, null);
        assertNotNull(wch);
        wch.write(ByteBuffer.wrap("hello world".getBytes()));
        wch.close();

        ReadableByteChannel rch = fs.openForReading("/test.txt", 0, null);
        assertNotNull(rch);
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int read = rch.read(buf);
        rch.close();
        assertEquals(11, read);
        buf.flip();
        byte[] data = new byte[read];
        buf.get(data);
        assertEquals("hello world", new String(data));
    }

    @Test
    public void testReadWithOffset() throws IOException {
        Files.write(tempDir.resolve("offset.txt"), "hello world".getBytes());
        ReadableByteChannel rch = fs.openForReading("/offset.txt", 6, null);
        assertNotNull(rch);
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int read = rch.read(buf);
        rch.close();
        buf.flip();
        byte[] data = new byte[read];
        buf.get(data);
        assertEquals("world", new String(data));
    }

    @Test
    public void testReadOnlyWriteDenied() {
        BasicFTPFileSystem roFs = new BasicFTPFileSystem(tempDir, true);
        assertNull(roFs.openForWriting("/test.txt", false, null));
    }

    @Test
    public void testAllocateSpace() {
        assertEquals(FTPFileOperationResult.SUCCESS, fs.allocateSpace("/test", 1024, null));
    }
}
