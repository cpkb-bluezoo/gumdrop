/*
 * FileHandlerTest.java
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

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 tests for the WebDAV {@link FileHandler}, exercising the request
 * methods end-to-end through {@link FileHandler#headers} with a recording
 * {@link HTTPResponseState} double.
 *
 * <p>These tests validate that the {@link org.bluezoo.gumdrop.StorageExecutor}
 * offload refactor preserves response semantics. Because no
 * {@code Gumdrop} instance is started, the handler's {@code offload} helper runs
 * the blocking work inline, so each request completes synchronously (file-body
 * streaming still uses an asynchronous channel and is awaited via a latch).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHandlerTest {

    private Path root;
    private Path helloFile;   // /hello.txt
    private Path subDir;      // /sub
    private Path nestedFile;  // /sub/nested.txt

    private static final String HELLO = "Hello, WebDAV!";

    @Before
    public void setUp() throws Exception {
        Logger.getLogger(FileHandler.class.getName()).setLevel(Level.SEVERE);
        root = Files.createTempDirectory("gumdrop-filehandler-test");
        helloFile = root.resolve("hello.txt");
        Files.write(helloFile, HELLO.getBytes(StandardCharsets.UTF_8));
        subDir = Files.createDirectory(root.resolve("sub"));
        nestedFile = subDir.resolve("nested.txt");
        Files.write(nestedFile, "nested".getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(root);
    }

    private void deleteRecursively(Path p) throws Exception {
        if (p == null || !Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (java.util.stream.Stream<Path> s = Files.list(p)) {
                for (Path child : (Iterable<Path>) s::iterator) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    // ── Handler + dispatch helpers ──

    private FileHandler newHandler(boolean allowWrite) {
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        types.put("html", "text/html");
        return new FileHandler(root, allowWrite, true,
                "GET, HEAD, PUT, DELETE, OPTIONS, PROPFIND, MKCOL, COPY, MOVE",
                new String[]{"index.html"}, types,
                new WebDAVLockManager(), null);
    }

    private RecordingState dispatch(FileHandler h, String method, String path,
            Map<String, String> extraHeaders) throws Exception {
        Headers req = new Headers();
        req.add(":method", method);
        req.add(":path", path);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                req.add(e.getKey(), e.getValue());
            }
        }
        RecordingState st = new RecordingState();
        h.headers(st, req);
        assertTrue("Response did not complete within timeout for "
                + method + " " + path, st.await(5, TimeUnit.SECONDS));
        return st;
    }

    private Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ── GET / HEAD ──

    @Test
    public void testGetMissing() throws Exception {
        RecordingState st = dispatch(newHandler(true), "GET", "/nope.txt", null);
        assertEquals(HTTPStatus.NOT_FOUND.code, st.status());
    }

    @Test
    public void testHeadFileNoBody() throws Exception {
        RecordingState st = dispatch(newHandler(true), "HEAD", "/hello.txt", null);
        assertEquals(HTTPStatus.OK.code, st.status());
        assertEquals(String.valueOf(HELLO.length()),
                st.header("Content-Length"));
        assertEquals("text/plain", st.header("Content-Type"));
        assertEquals(0, st.body().length);
    }

    @Test
    public void testGetFileBody() throws Exception {
        RecordingState st = dispatch(newHandler(true), "GET", "/hello.txt", null);
        assertEquals(HTTPStatus.OK.code, st.status());
        assertEquals(HELLO, new String(st.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void testGetDirectoryListing() throws Exception {
        RecordingState st = dispatch(newHandler(true), "GET", "/", null);
        assertEquals(HTTPStatus.OK.code, st.status());
        assertTrue(st.header("Content-Type").startsWith("text/html"));
        String html = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("listing should mention hello.txt",
                html.contains("hello.txt"));
        assertTrue("listing should mention sub/ directory",
                html.contains("sub/"));
    }

    @Test
    public void testGetNotModified() throws Exception {
        long lm = Files.getLastModifiedTime(helloFile).toMillis();
        // If-Modified-Since at/after the file's mtime -> 304 Not Modified
        String ims = new org.bluezoo.gumdrop.http.HTTPDateFormat()
                .format(lm + 60000);
        RecordingState st = dispatch(newHandler(true), "GET", "/hello.txt",
                headers("If-Modified-Since", ims));
        assertEquals(HTTPStatus.NOT_MODIFIED.code, st.status());
    }

    // ── DELETE ──

    @Test
    public void testDeleteFile() throws Exception {
        RecordingState st = dispatch(newHandler(true), "DELETE", "/hello.txt", null);
        assertEquals(HTTPStatus.NO_CONTENT.code, st.status());
        assertFalse(Files.exists(helloFile));
    }

    @Test
    public void testDeleteMissing() throws Exception {
        RecordingState st = dispatch(newHandler(true), "DELETE", "/nope.txt", null);
        assertEquals(HTTPStatus.NOT_FOUND.code, st.status());
    }

    @Test
    public void testDeleteCollectionRecursive() throws Exception {
        RecordingState st = dispatch(newHandler(true), "DELETE", "/sub", null);
        assertEquals(HTTPStatus.NO_CONTENT.code, st.status());
        assertFalse(Files.exists(nestedFile));
        assertFalse(Files.exists(subDir));
    }

    @Test
    public void testDeleteNotAllowed() throws Exception {
        RecordingState st = dispatch(newHandler(false), "DELETE", "/hello.txt", null);
        assertEquals(HTTPStatus.METHOD_NOT_ALLOWED.code, st.status());
        assertTrue(Files.exists(helloFile));
    }

    // ── MKCOL ──

    @Test
    public void testMkcolCreates() throws Exception {
        RecordingState st = dispatch(newHandler(true), "MKCOL", "/newcol", null);
        assertEquals(HTTPStatus.CREATED.code, st.status());
        assertTrue(Files.isDirectory(root.resolve("newcol")));
    }

    @Test
    public void testMkcolExisting() throws Exception {
        RecordingState st = dispatch(newHandler(true), "MKCOL", "/sub", null);
        assertEquals(HTTPStatus.METHOD_NOT_ALLOWED.code, st.status());
    }

    // ── COPY / MOVE ──

    @Test
    public void testCopyFile() throws Exception {
        RecordingState st = dispatch(newHandler(true), "COPY", "/hello.txt",
                headers(DAVConstants.HEADER_DESTINATION, "/copy.txt"));
        assertEquals(HTTPStatus.CREATED.code, st.status());
        Path copy = root.resolve("copy.txt");
        assertTrue(Files.exists(copy));
        assertEquals(HELLO, new String(Files.readAllBytes(copy),
                StandardCharsets.UTF_8));
        assertTrue("source should remain after COPY", Files.exists(helloFile));
    }

    @Test
    public void testMoveFile() throws Exception {
        RecordingState st = dispatch(newHandler(true), "MOVE", "/hello.txt",
                headers(DAVConstants.HEADER_DESTINATION, "/moved.txt"));
        assertEquals(HTTPStatus.CREATED.code, st.status());
        assertFalse("source should be gone after MOVE", Files.exists(helloFile));
        Path moved = root.resolve("moved.txt");
        assertTrue(Files.exists(moved));
        assertEquals(HELLO, new String(Files.readAllBytes(moved),
                StandardCharsets.UTF_8));
    }

    // ── PROPFIND ──

    @Test
    public void testPropfindFileDepth0() throws Exception {
        RecordingState st = dispatch(newHandler(true), "PROPFIND", "/hello.txt",
                headers(DAVConstants.HEADER_DEPTH, "0"));
        assertEquals(HTTPStatus.MULTI_STATUS.code, st.status());
        String xml = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("hello.txt"));
        assertTrue(xml.contains("HTTP/1.1 200 OK"));
        assertTrue("file should report content length "
                + HELLO.length(),
                xml.contains(String.valueOf(HELLO.length())));
    }

    @Test
    public void testPropfindDirDepth1() throws Exception {
        RecordingState st = dispatch(newHandler(true), "PROPFIND", "/",
                headers(DAVConstants.HEADER_DEPTH, "1"));
        assertEquals(HTTPStatus.MULTI_STATUS.code, st.status());
        String xml = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("depth-1 listing should include hello.txt",
                xml.contains("hello.txt"));
        assertTrue("depth-1 listing should include sub collection",
                xml.contains("sub"));
    }

    @Test
    public void testPropfindMissing() throws Exception {
        RecordingState st = dispatch(newHandler(true), "PROPFIND", "/nope",
                headers(DAVConstants.HEADER_DEPTH, "0"));
        assertEquals(HTTPStatus.NOT_FOUND.code, st.status());
    }

    // ── Recording HTTPResponseState double ──

    /**
     * A minimal {@link HTTPResponseState} that records the response and runs
     * {@code execute}/{@code onWritable} callbacks inline (simulating an
     * always-writable transport on the calling thread).
     */
    private static final class RecordingState implements HTTPResponseState {
        private final Object lock = new Object();
        private final ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Headers responseHeaders;
        private int statusCode = -1;

        boolean await(long t, TimeUnit u) throws InterruptedException {
            return done.await(t, u);
        }

        int status() {
            synchronized (lock) {
                return statusCode;
            }
        }

        String header(String name) {
            synchronized (lock) {
                return responseHeaders == null ? null
                        : responseHeaders.getValue(name);
            }
        }

        byte[] body() {
            synchronized (lock) {
                return bodyOut.toByteArray();
            }
        }

        @Override
        public void headers(Headers headers) {
            synchronized (lock) {
                this.responseHeaders = headers;
                String s = headers.getValue(":status");
                if (s != null) {
                    try {
                        statusCode = Integer.parseInt(s);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            synchronized (lock) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                bodyOut.write(b, 0, b.length);
            }
        }

        @Override
        public void endResponseBody() {
        }

        @Override
        public void complete() {
            done.countDown();
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void pauseRequestBody() {
        }

        @Override
        public void resumeRequestBody() {
        }

        @Override
        public boolean pushPromise(Headers headers) {
            return false;
        }

        @Override
        public void upgradeToWebSocket(String subprotocol,
                WebSocketEventHandler handler) {
        }

        @Override
        public void cancel() {
            done.countDown();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public HTTPVersion getVersion() {
            return HTTPVersion.HTTP_1_1;
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }
    }
}
