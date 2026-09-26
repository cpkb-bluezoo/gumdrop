/*
 * DeadPropertySidecarRootTest.java
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #499: with a sidecar root, sidecars live in their own tree at each
 * resource's path relative to the content root, and the content tree gains
 * nothing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeadPropertySidecarRootTest {

    private static final String NS = "urn:example:props";

    private MemoryFileSystem mem;
    private Path content;
    private Path sidecars;
    private Path file;
    private DeadPropertyStore store;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        content = mem.getPath("/dav");
        sidecars = mem.getPath("/props");
        Files.createDirectories(content.resolve("docs"));
        Files.createDirectories(sidecars);
        file = content.resolve("docs/report.txt");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        store = newStore(content);
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
    }

    private DeadPropertyStore newStore(Path contentRoot) {
        DeadPropertyStore s = new DeadPropertyStore();
        s.setMode(DeadPropertyStore.Mode.SIDECAR);
        s.setSidecarRoot(contentRoot, sidecars);
        return s;
    }

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

    private static Map<String, DeadProperty> get(DeadPropertyStore s, Path resource) {
        Outcome o = new Outcome();
        s.getProperties(resource, o);
        assertTrue(o.done);
        assertNull(o.error, o.error);
        return o.properties;
    }

    private static void set(DeadPropertyStore s, Path resource, String name, String value) {
        Outcome o = new Outcome();
        s.setProperty(resource, NS, name, value, false, o);
        assertTrue(o.done);
        assertNull(o.error, o.error);
    }

    private static String valueOf(Map<String, DeadProperty> props, String name) {
        DeadProperty p = props.get(DeadProperty.makeKey(NS, name));
        return p == null ? null : p.getValue();
    }

    private static long count(Path dir) throws IOException {
        final long[] total = new long[1];
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                total[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    @Test
    public void withNoSidecarRootSiblingPathsAreUnchanged() {
        DeadPropertyStore plain = new DeadPropertyStore();
        assertEquals(content.resolve("docs/.webdav_report.txt"), plain.sidecarFor(file, false));
        assertEquals(content.resolve("docs/.webdav_."), plain.sidecarFor(content.resolve("docs"), true));
        assertTrue(plain.isSidecar(content.resolve("docs/.webdav_report.txt")));
        assertTrue(plain.isSidecarEntry(".webdav_report.txt"));
    }

    @Test
    public void propertiesAreWrittenUnderTheSidecarRootNotBesideTheResource() throws IOException {
        set(store, file, "color", "blue");
        assertTrue(Files.isRegularFile(sidecars.resolve("docs/report.txt")));
        assertEquals("the content directory gains no entry", 1, count(content));
        assertFalse(Files.exists(content.resolve("docs/.webdav_report.txt")));
        assertEquals("blue", valueOf(get(store, file), "color"));
    }

    @Test
    public void aContentFileNamedLikeASidecarIsAnOrdinaryFile() throws IOException {
        Path lookalike = content.resolve("docs/.webdav_report.pdf");
        Files.write(lookalike, "pdf".getBytes(StandardCharsets.UTF_8));
        assertFalse(store.isSidecar(lookalike));
        assertFalse(store.isSidecarEntry(".webdav_report.pdf"));
        set(store, file, "color", "blue");
        assertEquals("pdf", new String(Files.readAllBytes(lookalike), StandardCharsets.UTF_8));
        set(store, lookalike, "kind", "own");
        assertEquals("own", valueOf(get(store, lookalike), "kind"));
    }

    @Test
    public void aCollectionsOwnPropertiesLiveBesideItsChildrensKeys() throws IOException {
        Path docs = content.resolve("docs");
        set(store, docs, "kind", "folder");
        set(store, file, "color", "blue");
        assertTrue(Files.isRegularFile(sidecars.resolve("docs/.webdav_.")));
        assertEquals("folder", valueOf(get(store, docs), "kind"));
        assertEquals("blue", valueOf(get(store, file), "color"));
    }

    @Test
    public void aServerMountingTheTreeElsewhereReadsTheSameProperties() throws IOException {
        set(store, file, "color", "blue");
        Path otherContent = mem.getPath("/mnt/elsewhere/dav");
        Files.createDirectories(otherContent.resolve("docs"));
        Path otherFile = otherContent.resolve("docs/report.txt");
        Files.write(otherFile, "content".getBytes(StandardCharsets.UTF_8));
        DeadPropertyStore other = newStore(otherContent);
        assertEquals("blue", valueOf(get(other, otherFile), "color"));
    }

    @Test
    public void copyPropertiesCarriesTheKey() throws IOException {
        set(store, file, "color", "blue");
        Path copy = content.resolve("docs/copy.txt");
        Files.copy(file, copy);
        store.copyProperties(file, copy);
        assertEquals("blue", valueOf(get(store, copy), "color"));
        assertEquals("blue", valueOf(get(store, file), "color"));
        assertEquals(2, count(sidecars));
    }

    @Test
    public void copyOverANewerResourceReplacesItsOldProperties() throws IOException {
        Path plain = content.resolve("docs/plain.txt");
        Files.write(plain, "x".getBytes(StandardCharsets.UTF_8));
        set(store, plain, "old", "value");
        store.copyProperties(file, plain);
        assertTrue("the source has none, so the target keeps none", get(store, plain).isEmpty());
    }

    @Test
    public void moveRenamesTheKeyOfAFile() throws IOException {
        set(store, file, "color", "blue");
        Path moved = content.resolve("docs/moved.txt");
        Files.move(file, moved);
        store.moveProperties(file, moved, false);
        assertEquals("blue", valueOf(get(store, moved), "color"));
        assertFalse(Files.exists(sidecars.resolve("docs/report.txt")));
    }

    @Test
    public void moveOfACollectionRenamesTheWholeSubtree() throws IOException {
        Path docs = content.resolve("docs");
        set(store, docs, "kind", "folder");
        set(store, file, "color", "blue");
        Path moved = content.resolve("archive/2026");
        Files.createDirectories(moved.getParent());
        Files.move(docs, moved);
        store.moveProperties(docs, moved, true);
        assertEquals("folder", valueOf(get(store, moved), "kind"));
        assertEquals("blue", valueOf(get(store, moved.resolve("report.txt")), "color"));
        assertFalse(Files.exists(sidecars.resolve("docs")));
    }

    @Test
    public void moveOverAnExistingTargetReplacesItsProperties() throws IOException {
        Path target = content.resolve("docs/target.txt");
        Files.write(target, "x".getBytes(StandardCharsets.UTF_8));
        set(store, target, "stale", "yes");
        set(store, file, "color", "blue");
        Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.moveProperties(file, target, false);
        Map<String, DeadProperty> props = get(store, target);
        assertEquals("blue", valueOf(props, "color"));
        assertNull(valueOf(props, "stale"));
    }

    @Test
    public void deletingACollectionRemovesItsSidecarSubtree() throws IOException {
        Path docs = content.resolve("docs");
        set(store, docs, "kind", "folder");
        set(store, file, "color", "blue");
        store.deleteTree(docs);
        assertEquals(0, count(sidecars));
        assertFalse(Files.exists(sidecars.resolve("docs")));
    }

    @Test
    public void deletingAFileRemovesItsKey() throws IOException {
        set(store, file, "color", "blue");
        store.deleteProperties(file);
        assertEquals(0, count(sidecars));
    }

    @Test
    public void autoModeFallsBackToTheSidecarRoot() throws IOException {
        mem.setMaxXattrValueSize(64);
        DeadPropertyStore auto = new DeadPropertyStore();
        auto.setSidecarRoot(content, sidecars);
        set(auto, file, "big", "0123456789012345678901234567890123456789012345678901234567890123456789");
        assertTrue(Files.isRegularFile(sidecars.resolve("docs/report.txt")));
        assertFalse(Files.exists(content.resolve("docs/.webdav_report.txt")));
    }

    @Test
    public void aResourceOutsideTheContentRootIsRefused() {
        try {
            store.sidecarFor(mem.getPath("/elsewhere/x"), false);
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // not under the content root
        }
    }
}
