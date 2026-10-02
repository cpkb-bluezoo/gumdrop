/*
 * HotDeploymentThreadTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the hot-deployment watcher with forged watch keys and events,
 * so no real file-system notification or waiting is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HotDeploymentThreadTest {

    private static final class Event implements WatchEvent<Path> {
        private final WatchEvent.Kind<Path> kind;
        private final Path path;

        Event(WatchEvent.Kind<Path> kind, Path path) {
            this.kind = kind;
            this.path = path;
        }

        @Override
        public Kind<Path> kind() {
            return kind;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Path context() {
            return path;
        }
    }

    private static final class Overflow implements WatchEvent<Object> {
        @Override
        public Kind<Object> kind() {
            return StandardWatchEventKinds.OVERFLOW;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Object context() {
            return null;
        }
    }

    private static final class Key implements WatchKey {
        private final Path watched;
        private final List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>();
        boolean resetResult = true;

        Key(Path watched) {
            this.watched = watched;
        }

        @Override
        public boolean isValid() {
            return resetResult;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            List<WatchEvent<?>> copy = new ArrayList<WatchEvent<?>>(events);
            events.clear();
            return copy;
        }

        @Override
        public boolean reset() {
            return resetResult;
        }

        @Override
        public void cancel() {
            resetResult = false;
        }

        @Override
        public Watchable watchable() {
            return watched;
        }
    }

    private static final String WEB_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
            + "<listener><listener-class>" + ContextInitFaultsTest.Recorder.class.getName()
            + "</listener-class></listener></web-app>";

    public MemoryFolder tmp = new MemoryFolder();

    private String savedFactory;
    private Container container;
    private HotDeploymentThread watcher;

    @Before
    public void setUp() throws Exception {
        ContextInitFaultsTest.EVENTS.clear();
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        SharedContainer.get();
        container = new Container();
        watcher = new HotDeploymentThread(container, new MockWatchService()) {
            @Override
            WatchKey watch(Path dir, WatchEvent.Kind<?>... kinds) {
                return new Key(dir);
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        watcher.watchService.close();
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private static byte[] classBytes() throws IOException {
        String resource = ContextInitFaultsTest.Recorder.class.getName().replace('.', '/') + ".class";
        InputStream in = ContextInitFaultsTest.Recorder.class.getClassLoader().getResourceAsStream(resource);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String classEntry() {
        return "WEB-INF/classes/" + ContextInitFaultsTest.Recorder.class.getName().replace('.', '/') + ".class";
    }

    private Context started(Context context) throws Exception {
        container.addContext(context);
        context.load();
        context.init();
        ContextInitFaultsTest.EVENTS.clear();
        return context;
    }

    private int reloads() {
        int count = 0;
        for (String event : ContextInitFaultsTest.EVENTS) {
            if (event.equals("destroy")) {
                count++;
            }
        }
        return count;
    }

    private Context directoryContext() throws Exception {
        Path root = tmp.newFolder("app");
        Files.createDirectories(root.resolve("WEB-INF/classes/pkg"));
        MemoryFolder.write(root, "WEB-INF/web.xml", WEB_XML);
        MemoryFolder.write(root, classEntry(), classBytes());
        return started(new Context(container, "/app", root));
    }

    private Path war(String name) throws Exception {
        Path war = tmp.getRoot().resolve(name);
        ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war));
        try {
            zip.putNextEntry(new ZipEntry("WEB-INF/web.xml"));
            zip.write(WEB_XML.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(classEntry()));
            zip.write(classBytes());
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return war;
    }

    @Test
    public void testDirectoryContextWatchesRootAndWebInfTree() throws Exception {
        Context context = directoryContext();
        watcher.init(context);
        assertTrue(watcher.watchKeys.size() > 4);
        assertTrue(watcher.warLastModified.isEmpty());
    }

    @Test
    public void testDirectoryContextWithoutWebInfWatchesRootOnly() throws Exception {
        Path root = tmp.newFolder("bare");
        Context context = new Context(container, "/bare", root);
        watcher.init(context);
        assertEquals(1, watcher.watchKeys.size());
    }

    @Test
    public void testWarContextRecordsModificationTime() throws Exception {
        Path war = war("app.war");
        Context context = started(new Context(container, "/war", war));
        watcher.init(context);
        assertEquals(1, watcher.warLastModified.size());
        assertTrue(watcher.watchKeys.isEmpty());
    }

    @Test
    public void testChangedWarIsRedeployedOnce() throws Exception {
        Path war = war("app.war");
        Context context = started(new Context(container, "/war", war));
        watcher.init(context);
        watcher.checkWars();
        assertEquals(0, reloads());
        FileTime before = Files.getLastModifiedTime(war);
        Files.setLastModifiedTime(war, FileTime.fromMillis(before.toMillis() + 5000L));
        watcher.checkWars();
        assertEquals(1, reloads());
        watcher.checkWars();
        assertEquals(1, reloads());
    }

    @Test
    public void testUnknownKeyIsIgnoredAndInvalidKeyForgotten() throws Exception {
        Context context = directoryContext();
        watcher.init(context);
        Key stranger = new Key(tmp.getRoot());
        stranger.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, tmp.getRoot()));
        watcher.handleKey(stranger);
        assertEquals(0, reloads());

        WatchKey known = watcher.watchKeys.keySet().iterator().next();
        int before = watcher.watchKeys.size();
        Key forged = new Key(context.root);
        watcher.watchKeys.put(forged, context);
        forged.resetResult = false;
        watcher.handleKey(forged);
        assertEquals(before, watcher.watchKeys.size());
        assertFalse(watcher.watchKeys.containsKey(forged));
        assertTrue(watcher.watchKeys.containsKey(known));
    }

    @Test
    public void testRootEventsOtherThanWebInfAreIgnored() throws Exception {
        Context context = directoryContext();
        Key key = new Key(context.root);
        watcher.watchKeys.put(key, context);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, context.root.getFileSystem().getPath("index.html")));
        watcher.handleKey(key);
        assertEquals(0, reloads());
    }

    @Test
    public void testWebInfEventAtRootRedeploys() throws Exception {
        Context context = directoryContext();
        Key key = new Key(context.root);
        watcher.watchKeys.put(key, context);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_DELETE, context.root.getFileSystem().getPath("WEB-INF")));
        watcher.handleKey(key);
        assertEquals(1, reloads());
    }

    @Test
    public void testOverflowIsSkippedAndOnlyOneRedeployPerKey() throws Exception {
        Context context = directoryContext();
        Path webInf = context.root.resolve("WEB-INF");
        Key key = new Key(webInf);
        watcher.watchKeys.put(key, context);
        key.events.add(new Overflow());
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, context.root.getFileSystem().getPath("web.xml")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, context.root.getFileSystem().getPath("other.xml")));
        watcher.handleKey(key);
        assertEquals(1, reloads());
    }

    @Test
    public void testNewDirectoryIsWatchedAndTriggersRedeploy() throws Exception {
        Context context = directoryContext();
        Path webInf = context.root.resolve("WEB-INF");
        Path fresh = webInf.resolve("lib");
        Files.createDirectories(fresh);
        Key key = new Key(webInf);
        watcher.watchKeys.put(key, context);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, context.root.getFileSystem().getPath("lib")));
        watcher.handleKey(key);
        assertEquals(1, reloads());
        assertEquals(2, watcher.watchKeys.size());
    }

    @Test
    public void testRedeployReportsFailureWithoutThrowing() throws Exception {
        Context context = directoryContext();
        assertTrue(watcher.redeploy(context));
        assertEquals(1, reloads());
        Files.write(context.root.resolve("WEB-INF/web.xml"),
                "<web-app><unclosed>".getBytes(StandardCharsets.UTF_8));
        assertFalse(watcher.redeploy(context));
    }

    @Test
    public void testRunInitialisesContextsAndStopsWhenInterrupted() throws Exception {
        Path war = war("run.war");
        Context context = new Context(container, "/run", war);
        container.contexts.add(context);
        Thread.currentThread().interrupt();
        try {
            watcher.run();
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, watcher.warLastModified.size());
    }
}
