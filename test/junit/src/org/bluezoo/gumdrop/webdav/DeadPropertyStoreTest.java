/*
 * DeadPropertyStoreTest.java
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

package org.bluezoo.gumdrop.webdav;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DeadPropertyStore} on an in-memory file system, which
 * supports extended attributes, so the primary xattr backend is exercised as
 * well as the sidecar-file fallback. With no runtime set, the store runs its
 * work, and completes its callbacks, on the calling thread.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeadPropertyStoreTest {

    private static final String NS = "urn:example:props";

    private MemoryFileSystem mem;
    private Path root;
    private Path file;
    private DeadPropertyStore store;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        root = mem.getPath("/dav");
        Files.createDirectories(root);
        file = root.resolve("doc.txt");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        store = new DeadPropertyStore();
    }

    // Synchronous view of the callback API

    private static final class Outcome implements DeadPropertyCallback {
        Map<String, DeadProperty> properties;
        String error;
        boolean done;

        @Override
        public void onProperties(Map<String, DeadProperty> properties) {
            this.properties = properties;
            this.done = true;
        }

        @Override
        public void onError(String error) {
            this.error = error;
            this.done = true;
        }
    }

    private Map<String, DeadProperty> get(Path resource) {
        Outcome o = new Outcome();
        store.getProperties(resource, o);
        assertTrue("callback was not invoked", o.done);
        assertNull(o.error, o.error);
        return o.properties;
    }

    private String trySet(Path resource, String name, String value, boolean xml) {
        Outcome o = new Outcome();
        store.setProperty(resource, NS, name, value, xml, o);
        assertTrue("callback was not invoked", o.done);
        return o.error;
    }

    private void set(Path resource, String name, String value) {
        assertNull(trySet(resource, name, value, false));
    }

    private void remove(Path resource, String name) {
        Outcome o = new Outcome();
        store.removeProperty(resource, NS, name, o);
        assertTrue(o.done);
        assertNull(o.error, o.error);
    }

    private static String valueOf(Map<String, DeadProperty> props, String name) {
        DeadProperty p = props.get(DeadProperty.makeKey(NS, name));
        return p == null ? null : p.getValue();
    }

    private Path sidecarOf(Path resource) {
        return DeadPropertyStore.sidecarPath(resource, Files.isDirectory(resource));
    }

    private int xattrCount(Path resource) throws IOException {
        return Files.getFileAttributeView(resource, UserDefinedFileAttributeView.class)
                .list().size();
    }

    // Extended attribute backend (the default)

    @Test
    public void testPropertyIsStoredAsAnExtendedAttribute() throws IOException {
        set(file, "color", "blue");
        assertEquals("blue", valueOf(get(file), "color"));
        assertEquals(1, xattrCount(file));
        assertFalse("no sidecar is needed", Files.exists(sidecarOf(file)));
    }

    @Test
    public void testNothingStoredMeansNoProperties() {
        assertTrue(get(file).isEmpty());
    }

    @Test
    public void testSeveralPropertiesAreKeptSeparately() {
        set(file, "a", "1");
        set(file, "b", "2");
        Map<String, DeadProperty> props = get(file);
        assertEquals(2, props.size());
        assertEquals("1", valueOf(props, "a"));
        assertEquals("2", valueOf(props, "b"));
    }

    @Test
    public void testSettingAgainReplacesTheValue() {
        set(file, "a", "old");
        set(file, "a", "new");
        assertEquals("new", valueOf(get(file), "a"));
        assertEquals(1, get(file).size());
    }

    @Test
    public void testSameLocalNameInDifferentNamespacesDoesNotCollide() throws IOException {
        Outcome o = new Outcome();
        store.setProperty(file, "urn:one", "p", "1", false, o);
        store.setProperty(file, "urn:two", "p", "2", false, new Outcome());
        Map<String, DeadProperty> props = get(file);
        assertEquals(2, props.size());
        assertEquals("1", props.get(DeadProperty.makeKey("urn:one", "p")).getValue());
        assertEquals("2", props.get(DeadProperty.makeKey("urn:two", "p")).getValue());
    }

    @Test
    public void testRemovingAPropertyLeavesTheOthers() {
        set(file, "a", "1");
        set(file, "b", "2");
        remove(file, "a");
        Map<String, DeadProperty> props = get(file);
        assertNull(valueOf(props, "a"));
        assertEquals("2", valueOf(props, "b"));
    }

    @Test
    public void testXmlFlagAndEmptyValueSurvive() {
        assertNull(trySet(file, "frag", "<x:y xmlns:x='urn:x'>1</x:y>", true));
        assertNull(trySet(file, "empty", null, false));
        Map<String, DeadProperty> props = get(file);
        assertTrue(props.get(DeadProperty.makeKey(NS, "frag")).isXML());
        assertFalse(props.get(DeadProperty.makeKey(NS, "empty")).isXML());
        assertEquals("", valueOf(props, "empty"));
    }

    @Test
    public void testCollectionsCarryPropertiesToo() throws IOException {
        Path dir = Files.createDirectory(root.resolve("coll"));
        store.setProperty(dir, Boolean.TRUE, NS, "own", "v", false, new Outcome());
        Outcome o = new Outcome();
        store.getProperties(dir, Boolean.TRUE, o);
        assertEquals("v", valueOf(o.properties, "own"));
    }

    @Test
    public void testUnicodeValuesRoundTrip() {
        set(file, "u", "caf\u00e9 \u2603");
        assertEquals("caf\u00e9 \u2603", valueOf(get(file), "u"));
    }

    // Modes

    @Test
    public void testModeNoneStoresNothingAndRefusesWrites() {
        store.setMode(DeadPropertyStore.Mode.NONE);
        assertNotNull(trySet(file, "a", "1", false));
        assertTrue(get(file).isEmpty());
        assertEquals(DeadPropertyStore.Mode.NONE, store.getMode());
    }

    @Test
    public void testModeXattrHasNoSidecarFallback() {
        store.setMode(DeadPropertyStore.Mode.XATTR);
        mem.setMaxXattrValueSize(32);   // an encoded property is ns + name + flag + value
        assertNull(trySet(file, "small", "ok", false));
        assertNotNull("too big for an xattr and no fallback allowed",
                trySet(file, "big", "this value is far too long", false));
        assertFalse(Files.exists(sidecarOf(file)));
    }

    // Sidecar backend

    @Test
    public void testSidecarModeStoresAnXmlFileBesideTheResource() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        assertEquals("1", valueOf(get(file), "a"));
        assertTrue(Files.isRegularFile(root.resolve(".webdav_doc.txt")));
        assertEquals(0, xattrCount(file));
        String xml = new String(Files.readAllBytes(root.resolve(".webdav_doc.txt")),
                StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("urn:gumdrop:webdav-props"));
    }

    @Test
    public void testSidecarOfACollectionLivesInsideIt() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path dir = Files.createDirectory(root.resolve("coll"));
        store.setProperty(dir, Boolean.TRUE, NS, "own", "v", false, new Outcome());
        assertTrue(Files.isRegularFile(dir.resolve(".webdav_.")));
    }

    @Test
    public void testSidecarIsRemovedWhenItsLastPropertyGoes() {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        remove(file, "a");
        assertFalse(Files.exists(sidecarOf(file)));
        assertTrue(get(file).isEmpty());
    }

    @Test
    public void testSidecarSeveralPropertiesAndXml() {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        assertNull(trySet(file, "frag", "<y xmlns='urn:x'>2</y>", true));
        Map<String, DeadProperty> props = get(file);
        assertEquals("1", valueOf(props, "a"));
        assertTrue(props.get(DeadProperty.makeKey(NS, "frag")).isXML());
    }

    @Test
    public void testEmptySidecarMeansNoProperties() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Files.createFile(sidecarOf(file));
        assertTrue(get(file).isEmpty());
    }

    @Test
    public void testCorruptSidecarReadsAsNoProperties() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Files.write(sidecarOf(file), "<not really xml".getBytes(StandardCharsets.UTF_8));
        assertTrue(get(file).isEmpty());
    }

    @Test
    public void testOversizedSidecarReadsAsNoProperties() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Files.write(sidecarOf(file), new byte[1024 * 1024 + 1]);
        assertTrue(get(file).isEmpty());
    }

    @Test
    public void testAutoModeFallsBackToASidecarWhenAValueDoesNotFitAnXattr() throws IOException {
        mem.setMaxXattrValueSize(64);
        set(file, "small", "fits");
        String big = "0123456789012345678901234567890123456789012345678901234567890123456789";
        set(file, "big", big);
        Map<String, DeadProperty> props = get(file);
        assertEquals("fits", valueOf(props, "small"));
        assertEquals(big, valueOf(props, "big"));
        assertEquals("the small one stayed an xattr", 1, xattrCount(file));
        assertTrue(Files.isRegularFile(sidecarOf(file)));
    }

    // Copying and deleting

    @Test
    public void testCopyPropertiesCopiesTheSidecar() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        Path copy = root.resolve("copy.txt");
        Files.copy(file, copy);
        store.copyProperties(file, copy);
        assertEquals("1", valueOf(get(copy), "a"));
        assertEquals("the original keeps its own", "1", valueOf(get(file), "a"));
    }

    @Test
    public void testCopyPropertiesReplacesTheTargetsOldSidecar() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path other = root.resolve("other.txt");
        Files.write(other, "x".getBytes(StandardCharsets.UTF_8));
        set(other, "stale", "old target property");
        Files.copy(file, other, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.copyProperties(file, other);
        assertTrue("the overwritten resource keeps none of its old properties",
                get(other).isEmpty());
    }

    @Test
    public void testDeletePropertiesRemovesTheSidecar() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        store.deleteProperties(file);
        assertFalse(Files.exists(sidecarOf(file)));
    }

    @Test
    public void testSidecarNames() {
        assertTrue(DeadPropertyStore.isSidecarName(".webdav_doc.txt"));
        assertFalse(DeadPropertyStore.isSidecarName("doc.txt"));
        assertTrue(DeadPropertyStore.isSidecarFile(root.resolve(".webdav_doc.txt")));
        assertFalse(DeadPropertyStore.isSidecarFile(file));
    }

    // -- Suspected bugs: each is expected to fail until fixed --

    @Test
    public void testFallbackToASidecarDoesNotLeaveAStaleXattrBehind() {
        mem.setMaxXattrValueSize(64);
        set(file, "p", "old");
        String big = "0123456789012345678901234567890123456789012345678901234567890123456789";
        set(file, "p", big);
        assertEquals("the new value, not the shadowing old xattr", big,
                valueOf(get(file), "p"));
    }

    @Test
    public void testCopyPropertiesCarriesExtendedAttributeProperties() throws IOException {
        set(file, "a", "1");
        Path copy = root.resolve("copy.txt");
        Files.copy(file, copy);
        store.copyProperties(file, copy);
        assertEquals("RFC 4918 9.8.2: COPY preserves dead properties", "1",
                valueOf(get(copy), "a"));
    }

    @Test
    public void testSidecarSurvivesTransfersShorterThanRequested() {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        mem.setMaxTransfer(3);
        set(file, "a", "a value long enough to need several transfers");
        assertEquals("a value long enough to need several transfers",
                valueOf(get(file), "a"));
    }

    @Test
    public void testSymbolicLinkSidecarIsNotReadThrough() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path outside = mem.getPath("/outside/props.xml");
        Files.createDirectories(outside.getParent());
        store.setProperty(file, NS, "planted", "secret", false, new Outcome());
        Files.move(sidecarOf(file), outside);
        Files.createSymbolicLink(sidecarOf(file), outside);
        assertTrue("a linked sidecar is ignored", get(file).isEmpty());
    }

    @Test
    public void testSymbolicLinkSidecarIsNotWrittenThrough() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path outside = mem.getPath("/outside/precious.txt");
        Files.createDirectories(outside.getParent());
        Files.write(outside, "do not touch".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(sidecarOf(file), outside);
        assertNotNull("the write is refused", trySet(file, "a", "1", false));
        assertEquals("do not touch", new String(Files.readAllBytes(outside),
                StandardCharsets.UTF_8));
    }

    @Test
    public void testCopyPropertiesIgnoresALinkedSidecar() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path outside = mem.getPath("/outside/other.xml");
        Files.createDirectories(outside.getParent());
        Files.write(outside, "outside content".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(sidecarOf(file), outside);
        Path copy = root.resolve("copy.txt");
        Files.copy(file, copy);
        store.copyProperties(file, copy);
        assertFalse("nothing was copied through the link", Files.exists(sidecarOf(copy)));
    }

    // -- Read errors must not destroy existing properties --

    private byte[] sidecarBytes(Path resource) throws IOException {
        return Files.readAllBytes(sidecarOf(resource));
    }

    @Test
    public void testUnreadableSidecarIsNotOverwrittenBySettingAProperty() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        byte[] before = sidecarBytes(file);
        Files.setPosixFilePermissions(sidecarOf(file),
                java.nio.file.attribute.PosixFilePermissions.fromString("-w-------"));
        assertNotNull("existing properties could not be read, so nothing is written",
                trySet(file, "b", "2", false));
        Files.setPosixFilePermissions(sidecarOf(file),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertTrue(java.util.Arrays.equals(before, sidecarBytes(file)));
        assertEquals("1", valueOf(get(file), "a"));
    }

    @Test
    public void testUnreadableSidecarIsNotOverwrittenByRemovingAProperty() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        set(file, "b", "2");
        Files.setPosixFilePermissions(sidecarOf(file),
                java.nio.file.attribute.PosixFilePermissions.fromString("-w-------"));
        Outcome o = new Outcome();
        store.removeProperty(file, NS, "a", o);
        assertNotNull(o.error);
        Files.setPosixFilePermissions(sidecarOf(file),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertEquals(2, get(file).size());
    }

    @Test
    public void testCorruptSidecarIsNotOverwrittenBySettingAProperty() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        byte[] corrupt = "<properties xmlns='urn:gumdrop:webdav-props'><property".getBytes(
                StandardCharsets.UTF_8);
        Files.write(sidecarOf(file), corrupt);
        assertNotNull(trySet(file, "a", "1", false));
        assertTrue("the damaged sidecar is left for an administrator",
                java.util.Arrays.equals(corrupt, sidecarBytes(file)));
    }

    @Test
    public void testPropertiesTooLargeToReadBackAreNotWritten() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        set(file, "a", "1");
        byte[] before = sidecarBytes(file);
        char[] chars = new char[1024 * 1024 + 1];
        java.util.Arrays.fill(chars, 'x');
        assertNotNull("a sidecar bigger than the reader accepts must not be created",
                trySet(file, "huge", new String(chars), false));
        assertTrue(java.util.Arrays.equals(before, sidecarBytes(file)));
        assertEquals("1", valueOf(get(file), "a"));
    }

    // -- Concurrent updates of one resource --

    @Test
    public void testConcurrentUpdatesOfOneResourceAreNotLost() {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        final Outcome second = new Outcome();
        final boolean[] started = { false };
        DeadPropertyStore.afterSidecarRead = new Runnable() {
            @Override
            public void run() {
                if (!started[0]) {
                    started[0] = true;
                    // Another request updates the same resource right after
                    // the first has read the sidecar.
                    store.setProperty(file, NS, "b", "2", false, second);
                }
            }
        };
        try {
            assertNull(trySet(file, "a", "1", false));
        } finally {
            DeadPropertyStore.afterSidecarRead = null;
        }
        assertTrue("the second update completed", second.done);
        assertNull(second.error);
        Map<String, DeadProperty> props = get(file);
        assertEquals("1", valueOf(props, "a"));
        assertEquals("2", valueOf(props, "b"));
    }

    @Test
    public void testUpdatesOfDifferentResourcesDoNotWaitForEachOther() throws IOException {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        final Path other = root.resolve("other.txt");
        Files.write(other, "x".getBytes(StandardCharsets.UTF_8));
        final Outcome second = new Outcome();
        final boolean[] started = { false };
        DeadPropertyStore.afterSidecarRead = new Runnable() {
            @Override
            public void run() {
                if (!started[0]) {
                    started[0] = true;
                    store.setProperty(other, NS, "b", "2", false, second);
                    assertTrue("a different resource is not held up", second.done);
                }
            }
        };
        try {
            assertNull(trySet(file, "a", "1", false));
        } finally {
            DeadPropertyStore.afterSidecarRead = null;
        }
        assertEquals("2", valueOf(get(other), "b"));
        assertEquals("1", valueOf(get(file), "a"));
    }

    // -- Extended attribute support is a property of where a file is --

    @Test
    public void testExtendedAttributeSupportIsJudgedPerResourceNotOncePerStore()
            throws IOException {
        Path plain = Files.createDirectories(root.resolve("plain"));
        Path plainFile = Files.write(plain.resolve("f"), "x".getBytes(StandardCharsets.UTF_8));
        Path capable = Files.createDirectories(root.resolve("capable"));
        Path capableFile = Files.write(capable.resolve("g"), "x".getBytes(StandardCharsets.UTF_8));
        mem.disableUserAttributesUnder(plain);
        // A property stored earlier on the resource that has xattrs.
        DeadPropertyStore earlier = new DeadPropertyStore();
        earlier.setProperty(capableFile, NS, "kept", "v", false, new Outcome());
        // The first resource this store meets is on the mount without them.
        assertTrue(get(plainFile).isEmpty());
        assertEquals("v", valueOf(get(capableFile), "kept"));
    }

    @Test
    public void testResourceWithoutExtendedAttributesFallsBackToASidecar() throws IOException {
        Path plain = Files.createDirectories(root.resolve("plain"));
        Path plainFile = Files.write(plain.resolve("f"), "x".getBytes(StandardCharsets.UTF_8));
        mem.disableUserAttributesUnder(plain);
        set(plainFile, "a", "1");
        assertEquals("1", valueOf(get(plainFile), "a"));
        assertTrue(Files.isRegularFile(sidecarOf(plainFile)));
    }
}
