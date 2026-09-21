/*
 * DeadPropertyStoreIntegrationTest.java
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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DeadPropertyStore} on the real file system, where the sidecar I/O
 * goes through a genuine {@link java.nio.channels.AsynchronousFileChannel}
 * and extended attributes are the host's own.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeadPropertyStoreIntegrationTest {

    private static final String NS = "urn:example:props";

    private Path dir;
    private Path file;
    private DeadPropertyStore store;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("deadprops");
        file = dir.resolve("doc.txt");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        store = new DeadPropertyStore();
    }

    @After
    public void tearDown() throws IOException {
        DirectoryStream<Path> children = Files.newDirectoryStream(dir);
        try {
            for (Path child : children) {
                Files.deleteIfExists(child);
            }
        } finally {
            children.close();
        }
        Files.deleteIfExists(dir);
    }

    private static final class Outcome implements DeadPropertyCallback {
        private final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        volatile Map<String, DeadProperty> properties;
        volatile String error;

        @Override
        public void onProperties(Map<String, DeadProperty> properties) {
            this.properties = properties;
            done.countDown();
        }

        @Override
        public void onError(String error) {
            this.error = error;
            done.countDown();
        }

        Outcome await() throws InterruptedException {
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return this;
        }
    }

    private Outcome set(Path resource, String name, String value) throws Exception {
        Outcome o = new Outcome();
        store.setProperty(resource, NS, name, value, false, o);
        return o.await();
    }

    private Map<String, DeadProperty> get(Path resource) throws Exception {
        Outcome o = new Outcome();
        store.getProperties(resource, o);
        o.await();
        assertNull(o.error, o.error);
        return o.properties;
    }

    @Test
    public void testSidecarRoundTripOnDisk() throws Exception {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        assertNull(set(file, "a", "a value long enough to matter").error);
        assertEquals("a value long enough to matter",
                get(file).get(DeadProperty.makeKey(NS, "a")).getValue());
        assertTrue(Files.isRegularFile(dir.resolve(".webdav_doc.txt")));
    }

    @Test
    public void testSidecarThatIsALinkIsNotWrittenThroughOnDisk() throws Exception {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.write(outside, "do not touch".getBytes(StandardCharsets.UTF_8));
            try {
                Files.createSymbolicLink(dir.resolve(".webdav_doc.txt"), outside);
            } catch (UnsupportedOperationException e) {
                Assume.assumeNoException(e);
            }
            assertNotNull("the write is refused", set(file, "a", "1").error);
            assertEquals("do not touch", new String(Files.readAllBytes(outside),
                    StandardCharsets.UTF_8));
            assertTrue(get(file).isEmpty());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void testExtendedAttributesRoundTripWhereTheHostHasThem() throws Exception {
        Assume.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("user"));
        assertNull(set(file, "a", "1").error);
        assertEquals("1", get(file).get(DeadProperty.makeKey(NS, "a")).getValue());
        assertTrue("stored as an xattr, not a sidecar",
                !Files.exists(dir.resolve(".webdav_doc.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void testCopyPropertiesCarriesExtendedAttributesWhereTheHostHasThem() throws Exception {
        Assume.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("user"));
        assertNull(set(file, "a", "1").error);
        Path copy = dir.resolve("copy.txt");
        Files.copy(file, copy);
        store.copyProperties(file, copy);
        assertEquals("1", get(copy).get(DeadProperty.makeKey(NS, "a")).getValue());
    }

    /**
     * Many requests updating one resource at once, each through a real
     * asynchronous channel, must all take effect.
     */
    @Test
    public void testSimultaneousUpdatesOfOneResourceAreAllKept() throws Exception {
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        final int updaters = 12;
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(updaters);
        final java.util.concurrent.atomic.AtomicInteger failures =
                new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < updaters; i++) {
            final String name = "p" + i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        if (set(file, name, "value of " + name).error != null) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                }
            }).start();
        }
        go.countDown();
        assertTrue(finished.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        Map<String, DeadProperty> props = get(file);
        assertEquals("no update was lost", updaters, props.size());
        for (int i = 0; i < updaters; i++) {
            assertEquals("value of p" + i,
                    props.get(DeadProperty.makeKey(NS, "p" + i)).getValue());
        }
    }
}
