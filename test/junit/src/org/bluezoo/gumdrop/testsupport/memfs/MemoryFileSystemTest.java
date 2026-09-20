/*
 * MemoryFileSystemTest.java
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

package org.bluezoo.gumdrop.testsupport.memfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies {@link MemoryFileSystem} behaves like a real file system for the
 * operations production code relies on, so tests built on it are trustworthy.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MemoryFileSystemTest {

    private MemoryFileSystem fs;
    private Path root;

    @Before
    public void setUp() {
        fs = MemoryFileSystem.create();
        root = fs.getPath("/");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // Paths

    @Test
    public void testPathComponents() {
        Path p = fs.getPath("/a/b/c.txt");
        assertTrue(p.isAbsolute());
        assertEquals("/a/b/c.txt", p.toString());
        assertEquals("c.txt", p.getFileName().toString());
        assertEquals("/a/b", p.getParent().toString());
        assertEquals(root, p.getRoot());
        assertEquals(3, p.getNameCount());
        assertEquals("b", p.getName(1).toString());
        assertEquals("b/c.txt", p.subpath(1, 3).toString());
        assertNull(root.getParent());
        assertNull(root.getFileName());
        assertNull(fs.getPath("rel").getParent());
    }

    @Test
    public void testPathCollapsesRedundantSeparators() {
        assertEquals("/a/b", fs.getPath("//a///b/").toString());
        assertEquals("/a/b", fs.getPath("/a", "b").toString());
    }

    @Test
    public void testNormalize() {
        assertEquals("/a/c", fs.getPath("/a/b/../c/.").normalize().toString());
        assertEquals("/", fs.getPath("/../..").normalize().toString());
        assertEquals("../x", fs.getPath("../x").normalize().toString());
        assertEquals("", fs.getPath("a/..").normalize().toString());
    }

    @Test
    public void testResolveAndRelativize() {
        Path base = fs.getPath("/a/b");
        assertEquals("/a/b/c/d", base.resolve("c/d").toString());
        assertEquals("/x", base.resolve("/x").toString());
        assertEquals(base, base.resolve(""));
        assertEquals("../../x/y", base.relativize(fs.getPath("/x/y")).toString());
        assertEquals("c", base.relativize(fs.getPath("/a/b/c")).toString());
        assertEquals("/a/sibling", base.resolveSibling("sibling").toString());
    }

    @Test
    public void testStartsAndEndsWith() {
        Path p = fs.getPath("/a/b/c");
        assertTrue(p.startsWith(fs.getPath("/a/b")));
        assertTrue(p.startsWith(root));
        assertFalse(p.startsWith(fs.getPath("/a/x")));
        assertFalse(p.startsWith(fs.getPath("a")));
        assertTrue(p.endsWith(fs.getPath("b/c")));
        assertTrue(p.endsWith(p));
        assertFalse(p.endsWith(fs.getPath("/b/c")));
    }

    @Test
    public void testAbsoluteAndEquality() {
        Path rel = fs.getPath("a/b");
        assertEquals("/a/b", rel.toAbsolutePath().toString());
        assertEquals(fs.getPath("/a/b"), rel.toAbsolutePath());
        assertEquals(fs.getPath("/a/b").hashCode(), rel.toAbsolutePath().hashCode());
        assertFalse(fs.getPath("/a").equals(fs.getPath("a")));
        assertEquals("memfs:/a/b", rel.toUri().toString());
    }

    @Test
    public void testDifferentFileSystemsAreDistinct() {
        MemoryFileSystem other = MemoryFileSystem.create();
        assertFalse(fs.getPath("/a").equals(other.getPath("/a")));
    }

    @Test
    public void testToFileUnsupported() {
        try {
            fs.getPath("/a").toFile();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // in-memory paths have no java.io.File equivalent
        }
    }

    @Test
    public void testToRealPath() throws IOException {
        Files.createDirectory(fs.getPath("/d"));
        assertEquals("/d", fs.getPath("/x/../d").normalize().toRealPath().toString());
        assertEquals("/d", fs.getPath("d").toRealPath().toString());
        try {
            fs.getPath("/nope").toRealPath();
            fail("expected NoSuchFileException");
        } catch (NoSuchFileException expected) {
            // missing paths cannot be resolved
        }
    }

    // Files and directories

    @Test
    public void testWriteAndReadFile() throws IOException {
        Path f = fs.getPath("/hello.txt");
        Files.write(f, bytes("hello"));
        assertArrayEquals(bytes("hello"), Files.readAllBytes(f));
        assertEquals(5, Files.size(f));
        assertTrue(Files.isRegularFile(f));
        assertFalse(Files.isDirectory(f));
    }

    @Test
    public void testOverwriteTruncates() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("long content"));
        Files.write(f, bytes("hi"));
        assertArrayEquals(bytes("hi"), Files.readAllBytes(f));
    }

    @Test
    public void testAppend() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("ab"));
        Files.write(f, bytes("cd"), StandardOpenOption.APPEND);
        assertArrayEquals(bytes("abcd"), Files.readAllBytes(f));
    }

    @Test
    public void testCreateNewFailsWhenPresent() throws IOException {
        Path f = fs.getPath("/f");
        Files.createFile(f);
        try {
            Files.createFile(f);
            fail("expected FileAlreadyExistsException");
        } catch (FileAlreadyExistsException expected) {
            // CREATE_NEW semantics
        }
    }

    @Test
    public void testOpenMissingFileForReadFails() {
        try {
            Files.readAllBytes(fs.getPath("/missing"));
            fail("expected NoSuchFileException");
        } catch (IOException expected) {
            assertTrue(expected instanceof NoSuchFileException);
        }
    }

    @Test
    public void testWriteWithoutParentFails() {
        try {
            Files.write(fs.getPath("/no/parent/f"), bytes("x"));
            fail("expected NoSuchFileException");
        } catch (IOException expected) {
            assertTrue(expected instanceof NoSuchFileException);
        }
    }

    @Test
    public void testWriteUnderFileFails() throws IOException {
        Files.createFile(fs.getPath("/f"));
        try {
            Files.createFile(fs.getPath("/f/child"));
            fail("expected an IOException");
        } catch (IOException expected) {
            // a file is not a directory
        }
    }

    @Test
    public void testOpeningDirectoryFails() throws IOException {
        Files.createDirectory(fs.getPath("/d"));
        try {
            Files.readAllBytes(fs.getPath("/d"));
            fail("expected an IOException");
        } catch (IOException expected) {
            // cannot read a directory as a file
        }
    }

    @Test
    public void testCreateDirectories() throws IOException {
        Files.createDirectories(fs.getPath("/a/b/c"));
        assertTrue(Files.isDirectory(fs.getPath("/a/b/c")));
        Files.createDirectories(fs.getPath("/a/b/c"));
        try {
            Files.createDirectory(fs.getPath("/a"));
            fail("expected FileAlreadyExistsException");
        } catch (FileAlreadyExistsException expected) {
            // already present
        }
    }

    @Test
    public void testDeleteSemantics() throws IOException {
        Files.createDirectories(fs.getPath("/d"));
        Files.createFile(fs.getPath("/d/f"));
        try {
            Files.delete(fs.getPath("/d"));
            fail("expected DirectoryNotEmptyException");
        } catch (DirectoryNotEmptyException expected) {
            // non-empty directory
        }
        Files.delete(fs.getPath("/d/f"));
        Files.delete(fs.getPath("/d"));
        assertFalse(Files.exists(fs.getPath("/d")));
        assertFalse(Files.deleteIfExists(fs.getPath("/d")));
        try {
            Files.delete(fs.getPath("/d"));
            fail("expected NoSuchFileException");
        } catch (NoSuchFileException expected) {
            // already gone
        }
    }

    @Test
    public void testCannotDeleteRoot() {
        try {
            Files.delete(root);
            fail("expected an IOException");
        } catch (IOException expected) {
            // root is permanent
        }
    }

    @Test
    public void testListDirectory() throws IOException {
        Files.createDirectory(fs.getPath("/d"));
        Files.createFile(fs.getPath("/d/b"));
        Files.createFile(fs.getPath("/d/a"));
        Files.createDirectory(fs.getPath("/d/c"));
        List<String> names = new ArrayList<String>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(fs.getPath("/d"));
        try {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        } finally {
            ds.close();
        }
        assertEquals(java.util.Arrays.asList("a", "b", "c"), names);
    }

    @Test
    public void testListDirectoryGlob() throws IOException {
        Files.createDirectory(fs.getPath("/d"));
        Files.createFile(fs.getPath("/d/a.txt"));
        Files.createFile(fs.getPath("/d/b.log"));
        List<String> names = new ArrayList<String>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(fs.getPath("/d"), "*.txt");
        try {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        } finally {
            ds.close();
        }
        assertEquals(java.util.Arrays.asList("a.txt"), names);
    }

    @Test
    public void testListNonDirectoryFails() throws IOException {
        Files.createFile(fs.getPath("/f"));
        try {
            Files.newDirectoryStream(fs.getPath("/f"));
            fail("expected NotDirectoryException");
        } catch (NotDirectoryException expected) {
            // not a directory
        }
    }

    @Test
    public void testGlobMatcher() {
        PathMatcher m = fs.getPathMatcher("glob:*.{java,txt}");
        assertTrue(m.matches(fs.getPath("A.java")));
        assertTrue(m.matches(fs.getPath("b.txt")));
        assertFalse(m.matches(fs.getPath("c.log")));
        assertFalse(m.matches(fs.getPath("dir/A.java")));
        assertTrue(fs.getPathMatcher("glob:**/A.java").matches(fs.getPath("x/y/A.java")));
        assertTrue(fs.getPathMatcher("regex:a+").matches(fs.getPath("aaa")));
    }

    @Test
    public void testWalkFileTree() throws IOException {
        Files.createDirectories(fs.getPath("/a/b"));
        Files.createFile(fs.getPath("/a/b/f"));
        Files.createFile(fs.getPath("/a/g"));
        final List<String> seen = new ArrayList<String>();
        Files.walkFileTree(fs.getPath("/a"), new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                seen.add(file.toString());
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        assertEquals(java.util.Arrays.asList("/a/b/f", "/a/g"), seen);
    }

    // Copy and move

    @Test
    public void testMove() throws IOException {
        Files.write(fs.getPath("/old"), bytes("data"));
        Files.move(fs.getPath("/old"), fs.getPath("/new"));
        assertFalse(Files.exists(fs.getPath("/old")));
        assertArrayEquals(bytes("data"), Files.readAllBytes(fs.getPath("/new")));
    }

    @Test
    public void testMoveOntoExistingNeedsReplace() throws IOException {
        Files.write(fs.getPath("/a"), bytes("A"));
        Files.write(fs.getPath("/b"), bytes("B"));
        try {
            Files.move(fs.getPath("/a"), fs.getPath("/b"));
            fail("expected FileAlreadyExistsException");
        } catch (FileAlreadyExistsException expected) {
            // no REPLACE_EXISTING
        }
        Files.move(fs.getPath("/a"), fs.getPath("/b"), StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(bytes("A"), Files.readAllBytes(fs.getPath("/b")));
    }

    @Test
    public void testMoveMissingSource() {
        try {
            Files.move(fs.getPath("/nope"), fs.getPath("/x"));
            fail("expected NoSuchFileException");
        } catch (IOException expected) {
            assertTrue(expected instanceof NoSuchFileException);
        }
    }

    @Test
    public void testMoveDirectoryIntoItselfFails() throws IOException {
        Files.createDirectories(fs.getPath("/d/sub"));
        try {
            Files.move(fs.getPath("/d"), fs.getPath("/d/sub/d"));
            fail("expected an IOException");
        } catch (IOException expected) {
            // would orphan the subtree
        }
    }

    @Test
    public void testMoveDirectoryKeepsChildren() throws IOException {
        Files.createDirectories(fs.getPath("/d"));
        Files.write(fs.getPath("/d/f"), bytes("x"));
        Files.move(fs.getPath("/d"), fs.getPath("/e"));
        assertArrayEquals(bytes("x"), Files.readAllBytes(fs.getPath("/e/f")));
    }

    @Test
    public void testCopyIsIndependent() throws IOException {
        Files.write(fs.getPath("/a"), bytes("orig"));
        Files.copy(fs.getPath("/a"), fs.getPath("/b"));
        Files.write(fs.getPath("/a"), bytes("changed"));
        assertArrayEquals(bytes("orig"), Files.readAllBytes(fs.getPath("/b")));
    }

    @Test
    public void testReplaceNonEmptyDirectoryFails() throws IOException {
        Files.write(fs.getPath("/f"), bytes("x"));
        Files.createDirectories(fs.getPath("/d"));
        Files.createFile(fs.getPath("/d/child"));
        try {
            Files.copy(fs.getPath("/f"), fs.getPath("/d"), StandardCopyOption.REPLACE_EXISTING);
            fail("expected DirectoryNotEmptyException");
        } catch (DirectoryNotEmptyException expected) {
            // refuse to destroy a subtree
        }
    }

    // Attributes

    @Test
    public void testDefaultPermissionsAndAccess() throws IOException {
        Files.createFile(fs.getPath("/f"));
        Files.createDirectory(fs.getPath("/d"));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(fs.getPath("/f"))));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(fs.getPath("/d"))));
        assertTrue(Files.isReadable(fs.getPath("/f")));
        assertTrue(Files.isWritable(fs.getPath("/f")));
        assertFalse(Files.isExecutable(fs.getPath("/f")));
        assertTrue(Files.isExecutable(fs.getPath("/d")));
    }

    @Test
    public void testSetPermissionsDeniesAccess() throws IOException {
        Path f = fs.getPath("/f");
        Files.createFile(f);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("---------"));
        assertFalse(Files.isReadable(f));
        assertFalse(Files.isWritable(f));
        try {
            fs.provider().checkAccess(f, java.nio.file.AccessMode.READ);
            fail("expected AccessDeniedException");
        } catch (AccessDeniedException expected) {
            // no owner read bit
        }
    }

    @Test
    public void testLogicalClockIsMonotonicAndDeterministic() throws IOException {
        Path f = fs.getPath("/f");
        Files.createFile(f);
        FileTime first = Files.getLastModifiedTime(f);
        Files.write(f, bytes("x"));
        FileTime second = Files.getLastModifiedTime(f);
        assertTrue(second.toMillis() > first.toMillis());

        MemoryFileSystem again = MemoryFileSystem.create();
        Files.createFile(again.getPath("/f"));
        assertEquals(first, Files.getLastModifiedTime(again.getPath("/f")));
    }

    @Test
    public void testSetLastModifiedTime() throws IOException {
        Path f = fs.getPath("/f");
        Files.createFile(f);
        Files.setLastModifiedTime(f, FileTime.fromMillis(42));
        assertEquals(42, Files.getLastModifiedTime(f).toMillis());
    }

    @Test
    public void testReadAttributesByName() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("abc"));
        Map<String, Object> attrs = Files.readAttributes(f, "size,isDirectory");
        assertEquals(Long.valueOf(3), attrs.get("size"));
        assertEquals(Boolean.FALSE, attrs.get("isDirectory"));
        Map<String, Object> posix = Files.readAttributes(f, "posix:permissions");
        assertTrue(((java.util.Set<?>) posix.get("permissions"))
                .contains(PosixFilePermission.OWNER_READ));
        assertEquals("memfs", Files.getOwner(f).getName());
    }

    @Test
    public void testFileKeyIdentifiesNode() throws IOException {
        Files.createFile(fs.getPath("/a"));
        Files.createFile(fs.getPath("/b"));
        assertTrue(Files.isSameFile(fs.getPath("/a"), fs.getPath("/a")));
        assertFalse(Files.isSameFile(fs.getPath("/a"), fs.getPath("/b")));
        assertTrue(Files.isHidden(fs.getPath("/.hidden")));
    }

    // FileChannel

    @Test
    public void testChannelPositionalAccess() throws IOException {
        Path f = fs.getPath("/f");
        FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            ch.write(ByteBuffer.wrap(bytes("0123456789")));
            assertEquals(10, ch.position());
            ch.write(ByteBuffer.wrap(bytes("AB")), 2);
            ByteBuffer buf = ByteBuffer.allocate(4);
            assertEquals(4, ch.read(buf, 1));
            assertEquals("1AB4", new String(buf.array(), StandardCharsets.UTF_8));
            assertEquals(10, ch.size());
            ch.position(8);
            buf.clear();
            assertEquals(2, ch.read(buf));
            assertEquals(-1, ch.read(buf));
        } finally {
            ch.close();
        }
    }

    @Test
    public void testChannelWriteBeyondEndZeroFills() throws IOException {
        Path f = fs.getPath("/f");
        FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            ch.position(3);
            ch.write(ByteBuffer.wrap(bytes("x")));
        } finally {
            ch.close();
        }
        assertArrayEquals(new byte[] { 0, 0, 0, 'x' }, Files.readAllBytes(f));
    }

    @Test
    public void testChannelTruncateThenExtendReadsZeros() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("abcdef"));
        FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE);
        try {
            ch.truncate(2);
            ch.position(5);
            ch.write(ByteBuffer.wrap(bytes("z")));
        } finally {
            ch.close();
        }
        assertArrayEquals(new byte[] { 'a', 'b', 0, 0, 0, 'z' }, Files.readAllBytes(f));
    }

    @Test
    public void testChannelPermissionsEnforced() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("x"));
        FileChannel ro = FileChannel.open(f, StandardOpenOption.READ);
        try {
            ro.write(ByteBuffer.wrap(bytes("y")));
            fail("expected NonWritableChannelException");
        } catch (NonWritableChannelException expected) {
            // opened read-only
        } finally {
            ro.close();
        }
        try {
            ro.size();
            fail("expected ClosedChannelException");
        } catch (java.nio.channels.ClosedChannelException expected) {
            // closed
        }
    }

    @Test
    public void testChannelTransfers() throws IOException {
        Path src = fs.getPath("/src");
        Path dst = fs.getPath("/dst");
        Files.write(src, bytes("0123456789"));
        FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
        FileChannel out = FileChannel.open(dst, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            assertEquals(4, in.transferTo(3, 4, out));
            assertEquals(3, out.transferFrom(in, 4, 3));
        } finally {
            in.close();
            out.close();
        }
        assertEquals("3456", new String(Files.readAllBytes(dst), StandardCharsets.UTF_8)
                .substring(0, 4));
    }

    @Test
    public void testChannelIsAWritableByteChannel() throws IOException {
        Path f = fs.getPath("/f");
        WritableByteChannel ch = FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        ch.write(ByteBuffer.wrap(bytes("ok")));
        ch.close();
        assertArrayEquals(bytes("ok"), Files.readAllBytes(f));
    }

    // Symbolic links

    private Path link(String link, String target) throws IOException {
        return Files.createSymbolicLink(fs.getPath(link), fs.getPath(target));
    }

    @Test
    public void testCreateAndReadSymbolicLink() throws IOException {
        Files.write(fs.getPath("/target"), bytes("data"));
        link("/l", "/target");
        assertTrue(Files.isSymbolicLink(fs.getPath("/l")));
        assertFalse(Files.isSymbolicLink(fs.getPath("/target")));
        assertEquals(fs.getPath("/target"), Files.readSymbolicLink(fs.getPath("/l")));
    }

    @Test
    public void testSymbolicLinkErrors() throws IOException {
        Files.createFile(fs.getPath("/f"));
        link("/l", "/f");
        try {
            link("/l", "/f");
            fail("expected FileAlreadyExistsException");
        } catch (FileAlreadyExistsException expected) {
            // link name taken
        }
        try {
            Files.readSymbolicLink(fs.getPath("/f"));
            fail("expected NotLinkException");
        } catch (java.nio.file.NotLinkException expected) {
            // not a link
        }
        try {
            link("/no/parent/l", "/f");
            fail("expected NoSuchFileException");
        } catch (NoSuchFileException expected) {
            // parent missing
        }
    }

    @Test
    public void testReadAndWriteThroughFileLink() throws IOException {
        Files.write(fs.getPath("/target"), bytes("data"));
        link("/l", "/target");
        assertArrayEquals(bytes("data"), Files.readAllBytes(fs.getPath("/l")));
        Files.write(fs.getPath("/l"), bytes("changed"));
        assertArrayEquals(bytes("changed"), Files.readAllBytes(fs.getPath("/target")));
        assertTrue(Files.isSymbolicLink(fs.getPath("/l")));
    }

    @Test
    public void testAttributesFollowLinksUnlessAskedNot() throws IOException {
        Files.write(fs.getPath("/target"), bytes("abc"));
        link("/l", "/target");
        BasicFileAttributes followed = Files.readAttributes(fs.getPath("/l"),
                BasicFileAttributes.class);
        assertTrue(followed.isRegularFile());
        assertFalse(followed.isSymbolicLink());
        assertEquals(3, followed.size());
        BasicFileAttributes own = Files.readAttributes(fs.getPath("/l"),
                BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        assertTrue(own.isSymbolicLink());
        assertFalse(own.isRegularFile());
        assertFalse(own.isDirectory());
    }

    @Test
    public void testDirectoryLinkIsTraversedAndIsADirectory() throws IOException {
        Files.createDirectories(fs.getPath("/real"));
        Files.write(fs.getPath("/real/f"), bytes("x"));
        link("/alias", "/real");
        assertTrue(Files.isDirectory(fs.getPath("/alias")));
        assertFalse(Files.isDirectory(fs.getPath("/alias"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("x"), Files.readAllBytes(fs.getPath("/alias/f")));
        Files.write(fs.getPath("/alias/g"), bytes("y"));
        assertTrue(Files.exists(fs.getPath("/real/g")));
    }

    @Test
    public void testRelativeLinkResolvesAgainstTheLinksDirectory() throws IOException {
        Files.createDirectories(fs.getPath("/a"));
        Files.createDirectories(fs.getPath("/b"));
        Files.write(fs.getPath("/b/f"), bytes("rel"));
        Files.createSymbolicLink(fs.getPath("/a/l"), fs.getPath("../b/f"));
        assertArrayEquals(bytes("rel"), Files.readAllBytes(fs.getPath("/a/l")));
    }

    @Test
    public void testDotDotAfterALinkGoesToTheRealParent() throws IOException {
        Files.createDirectories(fs.getPath("/x"));
        Files.createDirectories(fs.getPath("/y/z"));
        Files.write(fs.getPath("/y/marker"), bytes("real parent"));
        link("/x/l", "/y/z");
        assertArrayEquals(bytes("real parent"),
                Files.readAllBytes(fs.getPath("/x/l/../marker")));
        assertFalse(Files.exists(fs.getPath("/x/marker")));
    }

    @Test
    public void testDanglingLink() throws IOException {
        link("/l", "/missing");
        assertFalse(Files.exists(fs.getPath("/l")));
        assertTrue(Files.exists(fs.getPath("/l"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        try {
            Files.readAllBytes(fs.getPath("/l"));
            fail("expected NoSuchFileException");
        } catch (NoSuchFileException expected) {
            // target is missing
        }
    }

    @Test
    public void testCreateNewDoesNotFollowADanglingLinkButPlainCreateDoes() throws IOException {
        link("/l", "/made");
        try {
            Files.createFile(fs.getPath("/l"));
            fail("expected FileAlreadyExistsException");
        } catch (FileAlreadyExistsException expected) {
            // O_EXCL semantics
        }
        Files.write(fs.getPath("/l"), bytes("through"));
        assertArrayEquals(bytes("through"), Files.readAllBytes(fs.getPath("/made")));
    }

    @Test
    public void testLinkLoopIsAnErrorNotAHang() throws IOException {
        link("/a", "/b");
        link("/b", "/a");
        assertFalse(Files.exists(fs.getPath("/a")));
        try {
            Files.readAllBytes(fs.getPath("/a"));
            fail("expected an IOException");
        } catch (java.nio.file.FileSystemException expected) {
            assertTrue(expected.getReason(), expected.getReason().contains("symbolic links"));
        }
        try {
            fs.getPath("/a").toRealPath();
            fail("expected an IOException");
        } catch (java.nio.file.FileSystemException expected) {
            // too many links
        }
    }

    @Test
    public void testToRealPathResolvesLinks() throws IOException {
        Files.createDirectories(fs.getPath("/real/sub"));
        Files.write(fs.getPath("/real/sub/f"), bytes("x"));
        link("/alias", "/real");
        assertEquals("/real/sub/f", fs.getPath("/alias/sub/f").toRealPath().toString());
        assertEquals("/alias/sub/f", fs.getPath("/alias/sub/f")
                .toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS).toString());
        assertEquals("/real", fs.getPath("/alias/sub/..").toRealPath().toString());
    }

    @Test
    public void testDeleteRemovesTheLinkNotTheTarget() throws IOException {
        Files.createDirectories(fs.getPath("/real"));
        Files.write(fs.getPath("/real/f"), bytes("keep"));
        link("/alias", "/real");
        Files.delete(fs.getPath("/alias"));
        assertFalse(Files.exists(fs.getPath("/alias"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("keep"), Files.readAllBytes(fs.getPath("/real/f")));
    }

    @Test
    public void testWalkFileTreeDoesNotFollowLinksByDefault() throws IOException {
        Files.createDirectories(fs.getPath("/tree"));
        Files.createDirectories(fs.getPath("/outside"));
        Files.write(fs.getPath("/outside/secret"), bytes("s"));
        link("/tree/l", "/outside");
        final List<String> seen = new ArrayList<String>();
        Files.walkFileTree(fs.getPath("/tree"), new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                seen.add(file + (attrs.isSymbolicLink() ? " (link)" : ""));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        assertEquals(java.util.Arrays.asList("/tree/l (link)"), seen);
    }

    @Test
    public void testCopyFollowsLinksUnlessAskedNot() throws IOException {
        Files.write(fs.getPath("/target"), bytes("content"));
        link("/l", "/target");
        Files.copy(fs.getPath("/l"), fs.getPath("/copy"));
        assertFalse(Files.isSymbolicLink(fs.getPath("/copy")));
        assertArrayEquals(bytes("content"), Files.readAllBytes(fs.getPath("/copy")));
        Files.copy(fs.getPath("/l"), fs.getPath("/linkcopy"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        assertTrue(Files.isSymbolicLink(fs.getPath("/linkcopy")));
        assertEquals(fs.getPath("/target"), Files.readSymbolicLink(fs.getPath("/linkcopy")));
    }

    @Test
    public void testMoveMovesTheLinkItself() throws IOException {
        Files.write(fs.getPath("/target"), bytes("t"));
        link("/l", "/target");
        Files.move(fs.getPath("/l"), fs.getPath("/moved"));
        assertTrue(Files.isSymbolicLink(fs.getPath("/moved")));
        assertFalse(Files.exists(fs.getPath("/l"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("t"), Files.readAllBytes(fs.getPath("/target")));
    }

    @Test
    public void testLinkComponentThatIsAFileIsNotADirectory() throws IOException {
        Files.createFile(fs.getPath("/f"));
        link("/l", "/f");
        try {
            Files.createFile(fs.getPath("/l/child"));
            fail("expected an IOException");
        } catch (IOException expected) {
            // a file cannot hold children, even through a link
        }
    }

    // Extended attributes

    private static java.nio.file.attribute.UserDefinedFileAttributeView user(Path p) {
        return Files.getFileAttributeView(p, java.nio.file.attribute.UserDefinedFileAttributeView.class);
    }

    private static String xattr(Path p, String name) throws IOException {
        java.nio.file.attribute.UserDefinedFileAttributeView v = user(p);
        ByteBuffer buf = ByteBuffer.allocate(v.size(name));
        v.read(name, buf);
        return new String(buf.array(), StandardCharsets.UTF_8);
    }

    @Test
    public void testFileStoreAdvertisesUserAttributes() throws IOException {
        Files.createFile(fs.getPath("/f"));
        java.nio.file.FileStore store = Files.getFileStore(fs.getPath("/f"));
        assertTrue(store.supportsFileAttributeView("user"));
        assertTrue(store.supportsFileAttributeView(
                java.nio.file.attribute.UserDefinedFileAttributeView.class));
    }

    @Test
    public void testExtendedAttributeRoundTrip() throws IOException {
        Path f = fs.getPath("/f");
        Files.createFile(f);
        assertEquals(5, user(f).write("user.a", ByteBuffer.wrap(bytes("hello"))));
        user(f).write("user.b", ByteBuffer.wrap(bytes("x")));
        assertEquals("hello", xattr(f, "user.a"));
        assertEquals(5, user(f).size("user.a"));
        assertEquals(java.util.Arrays.asList("user.a", "user.b"), user(f).list());
        user(f).write("user.a", ByteBuffer.wrap(bytes("changed")));
        assertEquals("changed", xattr(f, "user.a"));
        user(f).delete("user.a");
        assertEquals(java.util.Arrays.asList("user.b"), user(f).list());
    }

    @Test
    public void testExtendedAttributesOnDirectories() throws IOException {
        Files.createDirectory(fs.getPath("/d"));
        user(fs.getPath("/d")).write("user.k", ByteBuffer.wrap(bytes("v")));
        assertEquals("v", xattr(fs.getPath("/d"), "user.k"));
    }

    @Test
    public void testMissingExtendedAttributeIsAnIoError() throws IOException {
        Files.createFile(fs.getPath("/f"));
        try {
            user(fs.getPath("/f")).size("user.nope");
            fail("expected an IOException");
        } catch (IOException expected) {
            // no such attribute
        }
        try {
            user(fs.getPath("/f")).delete("user.nope");
            fail("expected an IOException");
        } catch (IOException expected) {
            // no such attribute
        }
    }

    @Test
    public void testExtendedAttributeTooLargeForTheDestinationBuffer() throws IOException {
        Files.createFile(fs.getPath("/f"));
        user(fs.getPath("/f")).write("user.a", ByteBuffer.wrap(bytes("hello")));
        try {
            user(fs.getPath("/f")).read("user.a", ByteBuffer.allocate(2));
            fail("expected an IOException");
        } catch (IOException expected) {
            // buffer too small
        }
    }

    @Test
    public void testExtendedAttributeValueSizeLimit() throws IOException {
        fs.setMaxXattrValueSize(4);
        Files.createFile(fs.getPath("/f"));
        user(fs.getPath("/f")).write("user.ok", ByteBuffer.wrap(bytes("four")));
        try {
            user(fs.getPath("/f")).write("user.big", ByteBuffer.wrap(bytes("fives")));
            fail("expected an IOException");
        } catch (IOException expected) {
            // does not fit
        }
        assertEquals(java.util.Arrays.asList("user.ok"), user(fs.getPath("/f")).list());
    }

    @Test
    public void testExtendedAttributesFollowMoveButNotPlainCopy() throws IOException {
        Files.write(fs.getPath("/a"), bytes("data"));
        user(fs.getPath("/a")).write("user.k", ByteBuffer.wrap(bytes("v")));
        Files.copy(fs.getPath("/a"), fs.getPath("/plain"));
        assertTrue(user(fs.getPath("/plain")).list().isEmpty());
        Files.copy(fs.getPath("/a"), fs.getPath("/attrs"), StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals("v", xattr(fs.getPath("/attrs"), "user.k"));
        Files.move(fs.getPath("/a"), fs.getPath("/moved"));
        assertEquals("v", xattr(fs.getPath("/moved"), "user.k"));
    }

    // Transfer limits

    @Test
    public void testMaxTransferCapsEachReadAndWrite() throws IOException {
        fs.setMaxTransfer(3);
        Path f = fs.getPath("/f");
        FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            ByteBuffer src = ByteBuffer.wrap(bytes("0123456789"));
            assertEquals(3, ch.write(src));
            assertEquals(3, ch.write(src, 3));
            assertEquals("only 6 of 10 bytes were taken", 4, src.remaining());
            ByteBuffer dst = ByteBuffer.allocate(10);
            assertEquals(3, ch.read(dst, 0));
        } finally {
            ch.close();
        }
    }

    @Test
    public void testOpeningRequiresTheMatchingPermissionBit() throws IOException {
        Path f = fs.getPath("/f");
        Files.write(f, bytes("x"));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("-w-------"));
        try {
            Files.readAllBytes(f);
            fail("expected AccessDeniedException");
        } catch (AccessDeniedException expected) {
            // no owner read bit
        }
        Files.write(f, bytes("y"));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"));
        try {
            Files.write(f, bytes("z"));
            fail("expected AccessDeniedException");
        } catch (AccessDeniedException expected) {
            // no owner write bit
        }
        assertArrayEquals(bytes("y"), Files.readAllBytes(f));
    }

    @Test
    public void testUserAttributesCanBeDisabledUnderADirectory() throws IOException {
        Files.createDirectories(fs.getPath("/mounted"));
        Files.createFile(fs.getPath("/mounted/f"));
        Files.createFile(fs.getPath("/g"));
        fs.disableUserAttributesUnder(fs.getPath("/mounted"));
        try {
            user(fs.getPath("/mounted/f")).list();
            fail("expected an IOException");
        } catch (IOException expected) {
            // this mount has no extended attributes
        }
        user(fs.getPath("/g")).write("user.k", ByteBuffer.wrap(bytes("v")));
        assertEquals("v", xattr(fs.getPath("/g"), "user.k"));
        assertFalse(Files.getFileStore(fs.getPath("/mounted/f")).supportsFileAttributeView("user"));
        assertTrue(Files.getFileStore(fs.getPath("/g")).supportsFileAttributeView("user"));
    }
}
