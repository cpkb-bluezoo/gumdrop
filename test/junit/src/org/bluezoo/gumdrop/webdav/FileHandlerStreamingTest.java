/*
 * FileHandlerStreamingTest.java
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.DelegatingResponseState;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

/**
 * WebDAV responses are streamed: sent in chunks as they are written, with no
 * Content-Length, and PROPFIND walks the tree a batch at a time, waiting for
 * the transport between batches, instead of gathering the whole tree first.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHandlerStreamingTest {

    private static final int FILES = 200;

    private Path root;

    /** A response that counts chunks and can hold back writability. */
    private static final class Paced extends FileHandlerTest.RecordingState {
        final boolean paced;
        final List<Runnable> deferred = new ArrayList<Runnable>();
        int chunks;

        Paced(boolean paced) {
            this.paced = paced;
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            chunks++;
            super.bodyContent(data);
        }

        @Override
        public void onWritable(Runnable callback) {
            if (paced) {
                deferred.add(callback);
            } else {
                super.onWritable(callback);
            }
        }

        boolean runOne() {
            if (deferred.isEmpty()) {
                return false;
            }
            deferred.remove(0).run();
            return true;
        }

        boolean completed() throws InterruptedException {
            return await(0, TimeUnit.MILLISECONDS);
        }
    }

    @Before
    public void setUp() throws Exception {
        Logger.getLogger(FileHandler.class.getName()).setLevel(Level.SEVERE);
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/webroot");
        Files.createDirectories(root);
        for (int i = 0; i < FILES; i++) {
            Files.write(root.resolve(String.format("f%03d.txt", Integer.valueOf(i))),
                    "x".getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(root.resolve("sub/deeper"));
        Files.write(root.resolve("sub/a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("sub/deeper/b.txt"), "b".getBytes(StandardCharsets.UTF_8));
    }

    private final Map<FileHandler, DelegatingResponseState> responses =
            new java.util.IdentityHashMap<FileHandler, DelegatingResponseState>();

    private FileHandler handler() {
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        DelegatingResponseState response = new DelegatingResponseState();
        FileHandler h = new FileHandler(response, root, true, true, "GET, PUT",
                new String[]{"index.html"}, types, new WebDAVLockManager(), null, null);
        responses.put(h, response);
        return h;
    }

    private void start(FileHandler h, Paced st, String method, String path, String... kv)
            throws Exception {
        responses.get(h).setTarget(st);
        List<Header> req = new ArrayList<Header>();
        HeaderFields.add(req, ":method", method);
        HeaderFields.add(req, ":path", path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            HeaderFields.add(req, kv[i], kv[i + 1]);
        }
        MessageEvents.headers(h, req);
        h.endMessage();
    }

    private static String text(Paced st) {
        return new String(st.body(), StandardCharsets.UTF_8);
    }

    private static Document parse(Paced st) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(st.body()));
    }

    private static List<String> hrefs(Document doc) {
        List<String> out = new ArrayList<String>();
        NodeList list = doc.getElementsByTagNameNS("DAV:", "href");
        for (int i = 0; i < list.getLength(); i++) {
            out.add(list.item(i).getTextContent());
        }
        return out;
    }

    @Test
    public void propfindIsStreamedInChunksWithoutAContentLength() throws Exception {
        Paced st = new Paced(false);
        start(handler(), st, "PROPFIND", "/", "Depth", "1");
        assertTrue(st.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        assertNull(st.header("Content-Length"));
        assertTrue("sent in " + st.chunks + " chunk(s)", st.chunks > 1);
        // root + FILES files + sub
        assertEquals(FILES + 2, hrefs(parse(st)).size());
    }

    @Test
    public void propfindWaitsForTheTransportBetweenBatches() throws Exception {
        Paced st = new Paced(true);
        start(handler(), st, "PROPFIND", "/", "Depth", "1");
        assertFalse("the whole tree was written without waiting", st.completed());
        assertFalse(st.deferred.isEmpty());
        assertFalse(text(st).contains("f199.txt"));
        int rounds = 0;
        while (st.runOne()) {
            rounds++;
        }
        assertTrue("took " + rounds + " round(s)", rounds > 1);
        assertTrue(st.completed());
        assertTrue(text(st).contains("f199.txt"));
        assertEquals(FILES + 2, hrefs(parse(st)).size());
    }

    @Test
    public void depthInfinityListsEachResourceOnce() throws Exception {
        Paced st = new Paced(false);
        start(handler(), st, "PROPFIND", "/", "Depth", "infinity");
        assertTrue(st.await(5, TimeUnit.SECONDS));
        List<String> all = hrefs(parse(st));
        Set<String> unique = new HashSet<String>(all);
        assertEquals("duplicates in " + all, unique.size(), all.size());
        assertTrue(unique.contains("/sub/"));
        assertTrue(unique.contains("/sub/deeper/"));
        assertTrue(unique.contains("/sub/deeper/b.txt"));
        assertEquals(FILES + 5, all.size());
    }

    @Test
    public void depthZeroIsJustTheResource() throws Exception {
        Paced st = new Paced(false);
        start(handler(), st, "PROPFIND", "/sub", "Depth", "0");
        assertTrue(st.await(5, TimeUnit.SECONDS));
        assertEquals(1, hrefs(parse(st)).size());
    }

    @Test
    public void missingResourceIsStillAnOrdinaryError() throws Exception {
        Paced st = new Paced(false);
        start(handler(), st, "PROPFIND", "/nope", "Depth", "1");
        assertTrue(st.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NOT_FOUND.code, st.status());
    }

    @Test
    public void aFailedConnectionStopsTheWalk() throws Exception {
        Paced st = new Paced(true);
        FileHandler h = handler();
        start(h, st, "PROPFIND", "/", "Depth", "1");
        assertTrue(st.runOne());
        int sofar = st.body().length;
        h.failed(new IOException("connection reset"));
        while (st.runOne()) {
            // whatever was pending must do nothing
        }
        assertFalse(st.completed());
        assertEquals(sofar, st.body().length);
    }

    @Test
    public void lockResponseIsStreamedWithoutAContentLength() throws Exception {
        Paced st = new Paced(false);
        start(handler(), st, "LOCK", "/f000.txt");
        assertTrue(st.await(5, TimeUnit.SECONDS));
        assertTrue(st.status() == 200 || st.status() == 201);
        assertNull(st.header("Content-Length"));
        assertNotNull(st.header("Lock-Token"));
        assertEquals(1, parse(st).getElementsByTagNameNS("DAV:", "activelock").getLength());
    }
}
