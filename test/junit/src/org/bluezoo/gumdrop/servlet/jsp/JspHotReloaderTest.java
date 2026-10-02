/*
 * JspHotReloaderTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.MemoryFolder;
import org.bluezoo.gumdrop.servlet.MockWatchService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link JspHotReloader} through its package-private key processing
 * and directory registration with mock watch keys over an in-memory file
 * system. The real watch service and thread are covered by the integration
 * test.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspHotReloaderTest {

    public MemoryFolder tmp = new MemoryFolder();

    private Path root;
    private Context context;
    private final List<String> modified = new ArrayList<String>();
    private final List<Set<String>> affected = new ArrayList<Set<String>>();

    /** Watch event stub. */
    private static final class Event implements WatchEvent<Path> {
        private final WatchEvent.Kind<Path> kind;
        private final Path context;

        Event(WatchEvent.Kind<Path> kind, Path context) {
            this.kind = kind;
            this.context = context;
        }

        public WatchEvent.Kind<Path> kind() {
            return kind;
        }

        public int count() {
            return 1;
        }

        public Path context() {
            return context;
        }
    }

    /** Overflow event stub, whose context is not a path. */
    private static final class OverflowEvent implements WatchEvent<Object> {
        public WatchEvent.Kind<Object> kind() {
            return StandardWatchEventKinds.OVERFLOW;
        }

        public int count() {
            return 1;
        }

        public Object context() {
            return null;
        }
    }

    /** Watch key stub with scripted events. */
    private static final class Key implements WatchKey {
        final List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>();
        boolean valid = true;
        int resets;

        public boolean isValid() {
            return valid;
        }

        public List<WatchEvent<?>> pollEvents() {
            List<WatchEvent<?>> copy = new ArrayList<WatchEvent<?>>(events);
            events.clear();
            return copy;
        }

        public boolean reset() {
            resets++;
            return valid;
        }

        public void cancel() {
            valid = false;
        }

        public Watchable watchable() {
            return null;
        }
    }

    private void write(String path, String content) throws IOException {
        MemoryFolder.write(root, path, content);
    }

    private final List<Key> registered = new ArrayList<Key>();

    private JspHotReloader reloader(boolean withCallback) throws Exception {
        JspHotReloader.JspReloadCallback cb = null;
        if (withCallback) {
            cb = new JspHotReloader.JspReloadCallback() {
                public void onJSPModified(String jspPath, Set<String> affectedPaths) {
                    modified.add(jspPath);
                    affected.add(affectedPaths);
                }
            };
        }
        return new JspHotReloader(context, root, cb, new MockWatchService()) {
            @Override
            WatchKey watch(Path dir, WatchEvent.Kind<?>... kinds) {
                Key key = new Key();
                registered.add(key);
                return key;
            }

            @Override
            public synchronized void start() {
                // no live thread in a unit test
            }
        };
    }

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("webapp");
        write("WEB-INF/web.xml",
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"/>");
        write("hello.jsp", "Hello");
        Container container = new Container();
        context = MemoryFolder.context(container, "/app", root);
        container.addContext(context);
        context.load();
    }

    @Test
    public void jspTldAndTagChangesNotifyCallback() throws Exception {
        JspHotReloader r = reloader(true);
        Key key = new Key();
        r.watchKeys.put(key, root);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("a.jsp")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, root.getFileSystem().getPath("B.JSPX")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("c.jspf")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_DELETE, root.getFileSystem().getPath("d.tld")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("e.tag")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("f.tagx")));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("g.txt")));
        r.processKey(key);
        assertEquals(6, modified.size());
        assertEquals("/a.jsp", modified.get(0));
        assertEquals("/B.JSPX", modified.get(1));
        assertEquals("/f.tagx", modified.get(5));
        assertEquals(1, key.resets);
        assertTrue(r.watchKeys.containsKey(key));
    }

    @Test
    public void callbackMayBeAbsent() throws Exception {
        JspHotReloader r = reloader(false);
        Key key = new Key();
        r.watchKeys.put(key, root);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("a.jsp")));
        r.processKey(key);
        assertTrue(modified.isEmpty());
        assertEquals(1, key.resets);
    }

    @Test
    public void invalidatedKeyIsForgotten() throws Exception {
        JspHotReloader r = reloader(true);
        Key key = new Key();
        key.valid = false;
        r.watchKeys.put(key, root);
        r.processKey(key);
        assertFalse(r.watchKeys.containsKey(key));
    }

    @Test
    public void unknownKeyIsResetAndIgnored() throws Exception {
        JspHotReloader r = reloader(true);
        Key key = new Key();
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("a.jsp")));
        r.processKey(key);
        assertEquals(1, key.resets);
        assertTrue(modified.isEmpty());
    }

    @Test
    public void overflowEventsAreSkipped() throws Exception {
        JspHotReloader r = reloader(true);
        Key key = new Key();
        r.watchKeys.put(key, root);
        key.events.add(new OverflowEvent());
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("a.jsp")));
        r.processKey(key);
        assertEquals(1, modified.size());
    }

    @Test
    public void createdDirectoryIsRegistered() throws Exception {
        JspHotReloader r = reloader(true);
        Key key = new Key();
        r.watchKeys.put(key, root);
        Files.createDirectories(root.resolve("newdir"));
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, root.getFileSystem().getPath("newdir")));
        r.processKey(key);
        assertEquals(2, r.watchKeys.size());
        assertTrue(modified.isEmpty());
        r.stopMonitoring();
    }

    @Test
    public void invalidatedJspReportsDependents() throws Exception {
        context.parseJSPFile("/hello.jsp");
        JspHotReloader r = reloader(true);
        Key key = new Key();
        r.watchKeys.put(key, root);
        key.events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, root.getFileSystem().getPath("hello.jsp")));
        r.processKey(key);
        assertEquals(1, modified.size());
        assertTrue(affected.get(0).contains("/hello.jsp"));
    }

    @Test
    public void monitoringRegistersDirectoriesAndStops() throws Exception {
        write("sub/inner/x.jsp", "x");
        write("WEB-INF/classes/skipped.txt", "x");
        write("META-INF/m.txt", "x");
        JspHotReloader r = reloader(true);
        r.startMonitoring();
        // root, WEB-INF, sub, sub/inner (classes and META-INF are skipped)
        assertEquals(4, r.watchKeys.size());
        assertEquals(4, registered.size());
        r.stopMonitoring();
        // stopping twice tolerates an already closed watch service
        r.stopMonitoring();
    }
}
