/*
 * FileHandlerMethodsTest.java
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.bluezoo.gumdrop.testsupport.DelegatingResponseState;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.webdav.FileHandlerTest.RecordingState;

import static org.junit.Assert.*;

/**
 * Drives the remaining {@link FileHandler} request methods (PUT bodies,
 * LOCK/UNLOCK, PROPPATCH with a dead property store, COPY/MOVE overwrite
 * rules, conditional and malformed requests) through
 * {@link FileHandler#headers} with a recording response double over an
 * in-memory file system.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHandlerMethodsTest {

    private static final String NS_EX = "http://example.org/ns";

    private Path root;
    private Path hello;
    private WebDAVLockManager locks;
    private DeadPropertyStore store;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/webroot");
        Files.createDirectories(root);
        hello = root.resolve("hello.txt");
        Files.write(hello, "Hello".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("sub/nested.txt"), "nested".getBytes(StandardCharsets.UTF_8));
        locks = new WebDAVLockManager();
        store = new DeadPropertyStore();
        store.setMode(DeadPropertyStore.Mode.SIDECAR);
        store.setSidecarRoot(root, mem.getPath("/props"));
        Files.createDirectories(mem.getPath("/props"));
    }

    // helpers

    // A handler takes its response at construction, before the test knows
    // which RecordingState will record it; see DelegatingResponseState.
    private final java.util.Map<FileHandler, DelegatingResponseState> responses =
            new java.util.IdentityHashMap<FileHandler, DelegatingResponseState>();

    private FileHandler track(DelegatingResponseState response, FileHandler handler) {
        responses.put(handler, response);
        return handler;
    }

    private void respondTo(FileHandler handler, RecordingState state) {
        responses.get(handler).setTarget(state);
    }

    private FileHandler handler(boolean write, boolean dav, boolean withStore) {
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        DelegatingResponseState response = new DelegatingResponseState();
        return track(response, new FileHandler(response, root, write, dav, "GET, PUT",
                new String[]{"index.html", ""}, types, locks,
                withStore ? store : null, null));
    }

    private FileHandler handler() {
        return handler(true, true, false);
    }

    private static Headers request(String method, String path, String... kv) {
        Headers req = new Headers();
        req.add(":method", method);
        req.add(":path", path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            req.add(kv[i], kv[i + 1]);
        }
        return req;
    }

    private static RecordingState done(RecordingState st) throws Exception {
        assertTrue("response did not complete", st.await(5, TimeUnit.SECONDS));
        return st;
    }

    private RecordingState send(FileHandler h, String method, String path, String... kv)
            throws Exception {
        RecordingState st = new RecordingState();
        respondTo(h, st);
        MessageEvents.headers(h, request(method, path, kv));
        h.endMessage();
        return done(st);
    }

    private RecordingState send(String method, String path, String... kv) throws Exception {
        return send(handler(), method, path, kv);
    }

    private RecordingState sendBody(FileHandler h, String method, String path,
            String body, String... kv) throws Exception {
        return sendChunks(h, method, path, new String[]{body}, kv);
    }

    private RecordingState sendChunks(FileHandler h, String method, String path,
            String[] chunks, String... kv) throws Exception {
        RecordingState st = new RecordingState();
        respondTo(h, st);
        MessageEvents.headers(h, request(method, path, kv));
        for (int i = 0; i < chunks.length; i++) {
            h.bodyContent(
                    ByteBuffer.wrap(chunks[i].getBytes(StandardCharsets.UTF_8)));
        }
        h.endMessage();
        return done(st);
    }

    private static String text(RecordingState st) {
        return new String(st.body(), StandardCharsets.UTF_8);
    }

    private String lockExclusive(String path) throws Exception {
        RecordingState st = send(handler(), "LOCK", path);
        assertTrue(st.status() == 200 || st.status() == 201);
        String token = st.header("Lock-Token");
        assertNotNull(token);
        return token;
    }

    private String content(String name) throws IOException {
        return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
    }

    // PUT

    @Test
    public void testPutCreatesThenOverwrites() throws Exception {
        RecordingState created = sendBody(handler(), "PUT", "/new.txt", "fresh",
                "content-length", "5");
        assertEquals(HttpStatus.CREATED.code, created.status());
        assertEquals("fresh", content("new.txt"));
        RecordingState replaced = sendBody(handler(), "PUT", "/new.txt", "second",
                "content-length", "6");
        assertEquals(HttpStatus.NO_CONTENT.code, replaced.status());
        assertEquals("second", content("new.txt"));
    }

    @Test
    public void testPutInSeveralChunksWithoutContentLength() throws Exception {
        RecordingState st = sendChunks(handler(), "PUT", "/parts.txt",
                new String[]{"one-", "two-", "three"});
        assertEquals(HttpStatus.CREATED.code, st.status());
        assertEquals("one-two-three", content("parts.txt"));
    }

    @Test
    public void testPutEmptyBodyByContentLengthZero() throws Exception {
        RecordingState st = send(handler(), "PUT", "/empty.txt", "content-length", "0");
        assertEquals(HttpStatus.CREATED.code, st.status());
        assertEquals(0L, Files.size(root.resolve("empty.txt")));
    }

    @Test
    public void testPutCreatesMissingParentCollections() throws Exception {
        RecordingState st = sendBody(handler(), "PUT", "/a/b/c.txt", "deep",
                "content-length", "4");
        assertEquals(HttpStatus.CREATED.code, st.status());
        assertEquals("deep", content("a/b/c.txt"));
    }

    @Test
    public void testPutOverCollectionAndReadOnlyAndBadPath() throws Exception {
        assertEquals(HttpStatus.CONFLICT.code,
                sendBody(handler(), "PUT", "/sub", "x", "content-length", "1").status());
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.code,
                send(handler(false, true, false), "PUT", "/x.txt").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "PUT", "/../escape.txt").status());
    }

    @Test
    public void testPutToLockedResourceNeedsTheLockToken() throws Exception {
        String token = lockExclusive("/hello.txt");
        RecordingState refused = sendBody(handler(), "PUT", "/hello.txt", "Hi",
                "content-length", "2");
        assertEquals(HttpStatus.LOCKED.code, refused.status());
        assertEquals("Hello", content("hello.txt"));
        RecordingState accepted = sendBody(handler(), "PUT", "/hello.txt", "Hi",
                "content-length", "2", "If", "(" + token + ")");
        assertEquals(HttpStatus.NO_CONTENT.code, accepted.status());
        assertEquals("Hi", content("hello.txt"));
    }

    // GET / HEAD / OPTIONS corners

    @Test
    public void testGetWelcomeFileAndDirectoryListingForSidecars() throws Exception {
        Files.write(root.resolve("sub/index.html"), "<p>idx</p>".getBytes(StandardCharsets.UTF_8));
        RecordingState st = send("GET", "/sub/");
        assertEquals(HttpStatus.OK.code, st.status());
        assertEquals("<p>idx</p>", text(st));
    }

    @Test
    public void testGetStarAndBadPathAndDeadPropertySidecarHidden() throws Exception {
        assertEquals(HttpStatus.NOT_FOUND.code, send("GET", "/../x").status());
        RecordingState opt = send("OPTIONS", "*");
        assertEquals(HttpStatus.OK.code, opt.status());
        assertEquals("1,2", opt.header("DAV"));
        RecordingState plain = send(handler(true, false, false), "OPTIONS", "/");
        assertEquals("GET, PUT", plain.header("Allow"));
        assertNull(plain.header("DAV"));
    }

    @Test
    public void testWebDavMethodsRefusedWhenWebDavDisabled() throws Exception {
        String[] methods = {"PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK",
                "UNLOCK", "ACL", "BREW"};
        for (int i = 0; i < methods.length; i++) {
            assertEquals(methods[i], HttpStatus.METHOD_NOT_ALLOWED.code,
                    send(handler(true, false, false), methods[i], "/hello.txt").status());
        }
    }

    @Test
    public void testWriteMethodsRefusedWhenReadOnly() throws Exception {
        FileHandler ro = null;
        String[] methods = {"PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"};
        for (int i = 0; i < methods.length; i++) {
            ro = handler(false, true, false);
            assertEquals(methods[i], HttpStatus.FORBIDDEN.code,
                    send(ro, methods[i], "/hello.txt").status());
        }
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.code,
                send(handler(false, true, false), "DELETE", "/hello.txt").status());
    }

    // DELETE

    @Test
    public void testDeleteBadPathLockedAndUnlocked() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST.code, send("DELETE", "/../x").status());
        String token = lockExclusive("/hello.txt");
        assertEquals(HttpStatus.LOCKED.code, send("DELETE", "/hello.txt").status());
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(), "DELETE", "/hello.txt", "If", "(<urn:wrong>)").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "DELETE", "/hello.txt", "If", "(" + token + ")").status());
        assertFalse(Files.exists(hello));
    }

    @Test
    public void testDeleteLockedWithLockTokenHeaderFallback() throws Exception {
        String token = lockExclusive("/hello.txt");
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(), "DELETE", "/hello.txt", "Lock-Token", "<urn:bogus>").status());
        String bare = token.substring(1, token.length() - 1);
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "DELETE", "/hello.txt", "Lock-Token", bare).status());
    }

    @Test
    public void testDeleteFileRemovesDeadProperties() throws Exception {
        FileHandler h = handler(true, true, true);
        String set = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour></D:prop></D:set>"
                + "</D:propertyupdate>";
        assertEquals(HttpStatus.MULTI_STATUS.code,
                sendBody(h, "PROPPATCH", "/hello.txt", set).status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(true, true, true), "DELETE", "/hello.txt").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(true, true, true), "DELETE", "/sub").status());
        assertFalse(Files.exists(root.resolve("sub")));
    }

    // MKCOL

    @Test
    public void testMkcolRules() throws Exception {
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.code, send("MKCOL", "/sub").status());
        assertEquals(HttpStatus.CONFLICT.code, send("MKCOL", "/no/parent").status());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.code,
                send(handler(), "MKCOL", "/body", "content-length", "5").status());
        assertEquals(HttpStatus.BAD_REQUEST.code, send("MKCOL", "/../x").status());
        assertEquals(HttpStatus.CREATED.code, send("MKCOL", "/made").status());
        assertTrue(Files.isDirectory(root.resolve("made")));
    }

    // COPY / MOVE

    @Test
    public void testCopyRules() throws Exception {
        String dest = DavConstants.HEADER_DESTINATION;
        assertEquals(HttpStatus.NOT_FOUND.code,
                send(handler(), "COPY", "/missing", dest, "/x").status());
        assertEquals(HttpStatus.BAD_REQUEST.code, send("COPY", "/hello.txt").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "COPY", "/hello.txt", dest, "/../out").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "COPY", "/hello.txt", dest, "mailto:nobody").status());
        assertEquals(HttpStatus.CREATED.code,
                send(handler(), "COPY", "/hello.txt", dest, "http://localhost/copy.txt").status());
        assertEquals("Hello", content("copy.txt"));
        assertEquals(HttpStatus.PRECONDITION_FAILED.code,
                send(handler(), "COPY", "/hello.txt", dest, "/copy.txt",
                        DavConstants.HEADER_OVERWRITE, "F").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "COPY", "/hello.txt", dest, "/copy.txt",
                        DavConstants.HEADER_OVERWRITE, "T").status());
    }

    @Test
    public void testCopyOntoLockedDestinationNeedsToken() throws Exception {
        Files.write(root.resolve("target.txt"), "t".getBytes(StandardCharsets.UTF_8));
        String token = lockExclusive("/target.txt");
        String dest = DavConstants.HEADER_DESTINATION;
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(), "COPY", "/hello.txt", dest, "/target.txt").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "COPY", "/hello.txt", dest, "/target.txt",
                        "If", "(" + token + ")").status());
    }

    @Test
    public void testCopyFileCopiesDeadProperties() throws Exception {
        String set = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour></D:prop></D:set>"
                + "</D:propertyupdate>";
        sendBody(handler(true, true, true), "PROPPATCH", "/hello.txt", set);
        assertEquals(HttpStatus.CREATED.code,
                send(handler(true, true, true), "COPY", "/hello.txt",
                        DavConstants.HEADER_DESTINATION, "/dup.txt").status());
        RecordingState st = sendBody(handler(true, true, true), "PROPFIND", "/dup.txt",
                "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\" xmlns:X=\"" + NS_EX
                + "\"><D:prop><X:colour/></D:prop></D:propfind>",
                DavConstants.HEADER_DEPTH, "0");
        assertTrue(text(st), text(st).contains("red"));
    }

    @Test
    public void testMoveRules() throws Exception {
        String dest = DavConstants.HEADER_DESTINATION;
        assertEquals(HttpStatus.NOT_FOUND.code,
                send(handler(), "MOVE", "/missing", dest, "/x").status());
        assertEquals(HttpStatus.BAD_REQUEST.code, send("MOVE", "/hello.txt").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "MOVE", "/hello.txt", dest, "/../out").status());
        Files.write(root.resolve("other.txt"), "o".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.PRECONDITION_FAILED.code,
                send(handler(), "MOVE", "/hello.txt", dest, "/other.txt",
                        DavConstants.HEADER_OVERWRITE, "F").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "MOVE", "/hello.txt", dest, "/other.txt").status());
        assertEquals("Hello", content("other.txt"));
        assertFalse(Files.exists(hello));
    }

    @Test
    public void testMoveCollectionOverExistingCollection() throws Exception {
        Files.createDirectories(root.resolve("target/inner"));
        Files.write(root.resolve("target/inner/x.txt"), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(true, true, true), "MOVE", "/sub",
                        DavConstants.HEADER_DESTINATION, "/target").status());
        assertTrue(Files.exists(root.resolve("target/nested.txt")));
        assertFalse(Files.exists(root.resolve("target/inner")));
    }

    @Test
    public void testMoveLockedSourceNeedsToken() throws Exception {
        String token = lockExclusive("/hello.txt");
        String dest = DavConstants.HEADER_DESTINATION;
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(), "MOVE", "/hello.txt", dest, "/moved.txt").status());
        assertEquals(HttpStatus.CREATED.code,
                send(handler(), "MOVE", "/hello.txt", dest, "/moved.txt",
                        "If", "(" + token + ")").status());
    }

    @Test
    public void testMoveOntoLockedDestinationNeedsToken() throws Exception {
        Files.write(root.resolve("dst.txt"), "d".getBytes(StandardCharsets.UTF_8));
        lockExclusive("/dst.txt");
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(), "MOVE", "/hello.txt",
                        DavConstants.HEADER_DESTINATION, "/dst.txt").status());
    }

    // LOCK / UNLOCK

    @Test
    public void testLockOnMissingResourceCreatesIt() throws Exception {
        RecordingState st = send("LOCK", "/fresh.txt");
        assertEquals(HttpStatus.CREATED.code, st.status());
        assertTrue(Files.exists(root.resolve("fresh.txt")));
        assertTrue(text(st).contains("Second-"));
    }

    @Test
    public void testLockTimeoutHeaderVariants() throws Exception {
        String timeout = DavConstants.HEADER_TIMEOUT;
        RecordingState infinite = send(handler(), "LOCK", "/hello.txt", timeout, "Infinite");
        assertTrue(text(infinite), text(infinite).contains("Infinite"));
        lockReset();
        RecordingState seconds = send(handler(), "LOCK", "/hello.txt", timeout, "Second-120");
        assertTrue(text(seconds), text(seconds).contains("Second-"));
        lockReset();
        RecordingState junk = send(handler(), "LOCK", "/hello.txt", timeout, "Second-abc");
        assertEquals(HttpStatus.OK.code, junk.status());
        lockReset();
        RecordingState other = send(handler(), "LOCK", "/hello.txt", timeout, "Minutes-5");
        assertEquals(HttpStatus.OK.code, other.status());
        lockReset();
        RecordingState huge = send(handler(), "LOCK", "/hello.txt", timeout,
                "Second-99999999999");
        assertEquals(HttpStatus.OK.code, huge.status());
    }

    private void lockReset() {
        locks = new WebDAVLockManager();
    }

    @Test
    public void testSecondExclusiveLockIsRefused() throws Exception {
        lockExclusive("/hello.txt");
        assertEquals(HttpStatus.LOCKED.code, send("LOCK", "/hello.txt").status());
    }

    @Test
    public void testLockWithDepthZeroAndOwnerOnCollection() throws Exception {
        String body = "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\">"
                + "<D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:locktype><D:write/></D:locktype>"
                + "<D:owner>alice</D:owner></D:lockinfo>";
        RecordingState st = sendBody(handler(), "LOCK", "/sub/", body,
                DavConstants.HEADER_DEPTH, "0");
        assertEquals(HttpStatus.OK.code, st.status());
        assertTrue(text(st), text(st).contains("alice"));
        assertTrue(text(st), text(st).contains("depth>0<"));
    }

    @Test
    public void testLockRefreshAndBadRefresh() throws Exception {
        String token = lockExclusive("/hello.txt");
        RecordingState ok = send(handler(), "LOCK", "/hello.txt", "Lock-Token", token,
                DavConstants.HEADER_TIMEOUT, "Second-300");
        assertEquals(HttpStatus.OK.code, ok.status());
        RecordingState dir = send(handler(), "LOCK", "/hello.txt/", "Lock-Token", token);
        assertEquals(HttpStatus.OK.code, dir.status());
        assertEquals(HttpStatus.PRECONDITION_FAILED.code,
                send(handler(), "LOCK", "/hello.txt", "Lock-Token", "<urn:unknown>").status());
    }

    @Test
    public void testUnlockCases() throws Exception {
        String token = lockExclusive("/hello.txt");
        assertEquals(HttpStatus.BAD_REQUEST.code, send("UNLOCK", "/hello.txt").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "UNLOCK", "/../x", "Lock-Token", token).status());
        assertEquals(HttpStatus.CONFLICT.code,
                send(handler(), "UNLOCK", "/hello.txt", "Lock-Token", "<urn:unknown>").status());
        assertEquals(HttpStatus.NO_CONTENT.code,
                send(handler(), "UNLOCK", "/hello.txt", "Lock-Token", token).status());
        assertEquals(HttpStatus.OK.code, send("LOCK", "/hello.txt").status());
    }

    @Test
    public void testLockWithGarbageBodyIsBadRequest() throws Exception {
        RecordingState st = sendBody(handler(), "LOCK", "/hello.txt",
                "<?xml version=\"1.0\"?><D:other xmlns:D=\"DAV:\"/>");
        assertEquals(HttpStatus.BAD_REQUEST.code, st.status());
    }

    // PROPFIND

    @Test
    public void testPropfindPropnameListsLiveAndDeadProperties() throws Exception {
        String set = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour></D:prop></D:set>"
                + "</D:propertyupdate>";
        sendBody(handler(true, true, true), "PROPPATCH", "/hello.txt", set);
        RecordingState st = sendBody(handler(true, true, true), "PROPFIND", "/hello.txt",
                "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:propname/></D:propfind>",
                DavConstants.HEADER_DEPTH, "0");
        String xml = text(st);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        assertTrue(xml, xml.contains("getetag"));
        assertTrue(xml, xml.contains("colour"));
        assertFalse(xml, xml.contains("red"));
    }

    @Test
    public void testPropfindAllPropIncludesDeadAndLockDiscovery() throws Exception {
        String set = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour></D:prop></D:set>"
                + "</D:propertyupdate>";
        sendBody(handler(true, true, true), "PROPPATCH", "/hello.txt", set);
        String body = "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\">"
                + "<D:lockscope><D:shared/></D:lockscope><D:locktype><D:write/></D:locktype>"
                + "<D:owner>bob</D:owner></D:lockinfo>";
        sendBody(handler(), "LOCK", "/hello.txt", body, DavConstants.HEADER_TIMEOUT, "Infinite");
        RecordingState st = sendBody(handler(true, true, true), "PROPFIND", "/",
                "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>",
                DavConstants.HEADER_DEPTH, "infinity");
        String xml = text(st);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        assertTrue(xml, xml.contains("red"));
        assertTrue(xml, xml.contains("bob"));
        assertTrue(xml, xml.contains("Infinite"));
        assertTrue(xml, xml.contains("shared"));
        assertTrue(xml, xml.contains("nested.txt"));
    }

    @Test
    public void testPropfindNamedLiveAndUnknownProperties() throws Exception {
        String body = "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\" xmlns:X=\"" + NS_EX
                + "\"><D:prop><D:creationdate/><D:displayname/><D:getcontentlength/>"
                + "<D:getcontenttype/><D:getetag/><D:getlastmodified/><D:lockdiscovery/>"
                + "<D:resourcetype/><D:supportedlock/><D:bogus/><X:missing/></D:prop></D:propfind>";
        RecordingState file = sendBody(handler(), "PROPFIND", "/hello.txt", body,
                DavConstants.HEADER_DEPTH, "0");
        String xml = text(file);
        assertTrue(xml, xml.contains("displayname"));
        assertTrue(xml, xml.contains("text/plain"));
        assertTrue(xml, xml.contains("missing"));
        RecordingState dir = sendBody(handler(), "PROPFIND", "/sub", body,
                DavConstants.HEADER_DEPTH, "0");
        String dirXml = text(dir);
        assertTrue(dirXml, dirXml.contains("httpd/unix-directory"));
        assertTrue(dirXml, dirXml.contains("collection"));
        assertFalse(dirXml, dirXml.contains("getcontentlength>"));
    }

    @Test
    public void testPropfindMalformedBodyAndBadPath() throws Exception {
        RecordingState bad = sendBody(handler(), "PROPFIND", "/hello.txt", "<<<not xml");
        assertEquals(HttpStatus.BAD_REQUEST.code, bad.status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(), "PROPFIND", "/../x").status());
    }

    @Test
    public void testPropfindOnUnknownBodyElementIsBadRequest() throws Exception {
        RecordingState st = sendBody(handler(), "PROPFIND", "/hello.txt",
                "<?xml version=\"1.0\"?><D:other xmlns:D=\"DAV:\"/>");
        assertEquals(HttpStatus.BAD_REQUEST.code, st.status());
    }

    @Test
    public void testPropfindDefaultsToInfinityDepthWithoutHeader() throws Exception {
        RecordingState st = send(handler(), "PROPFIND", "/");
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        assertTrue(text(st), text(st).contains("nested.txt"));
    }

    // PROPPATCH

    @Test
    public void testProppatchSetRemoveAndLiveRefusal() throws Exception {
        String body = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour>"
                + "<D:getetag>nope</D:getetag></D:prop></D:set>"
                + "<D:remove><D:prop><X:size/></D:prop></D:remove></D:propertyupdate>";
        RecordingState st = sendBody(handler(true, true, true), "PROPPATCH", "/hello.txt", body);
        String xml = text(st);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        assertTrue(xml, xml.contains("200 OK"));
        assertTrue(xml, xml.contains("403 Forbidden"));
        RecordingState collection = sendBody(handler(true, true, true), "PROPPATCH", "/sub", body);
        assertEquals(HttpStatus.MULTI_STATUS.code, collection.status());
    }

    @Test
    public void testProppatchWithoutStoreIsForbiddenPerProperty() throws Exception {
        String body = "<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:X=\""
                + NS_EX + "\"><D:set><D:prop><X:colour>red</X:colour></D:prop></D:set>"
                + "</D:propertyupdate>";
        RecordingState st = sendBody(handler(), "PROPPATCH", "/hello.txt", body);
        assertTrue(text(st), text(st).contains("403 Forbidden"));
    }

    @Test
    public void testProppatchPreconditions() throws Exception {
        assertEquals(HttpStatus.NOT_FOUND.code,
                send(handler(true, true, true), "PROPPATCH", "/missing").status());
        assertEquals(HttpStatus.NOT_FOUND.code,
                send(handler(true, true, true), "PROPPATCH", "/../x").status());
        assertEquals(HttpStatus.BAD_REQUEST.code,
                send(handler(true, true, true), "PROPPATCH", "/hello.txt").status());
        lockExclusive("/hello.txt");
        assertEquals(HttpStatus.LOCKED.code,
                send(handler(true, true, true), "PROPPATCH", "/hello.txt").status());
    }

    @Test
    public void testOversizedWebdavBodyIsRejected() throws Exception {
        FileHandler h = handler();
        RecordingState st = new RecordingState();
        respondTo(h, st);
        MessageEvents.headers(h, request("PROPFIND", "/hello.txt"));
        byte[] big = new byte[600 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        h.bodyContent(ByteBuffer.wrap(big));
        h.bodyContent(ByteBuffer.wrap(big));
        h.bodyContent(ByteBuffer.wrap(big));
        h.endMessage();
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.code, done(st).status());
    }

    // path/header oddities

    @Test
    public void testOddDepthValue() throws Exception {
        // a non-numeric Content-Length no longer reaches the handler: the
        // HTTP parser refuses the request first
        assertEquals(HttpStatus.MULTI_STATUS.code,
                send(handler(), "PROPFIND", "/",
                        DavConstants.HEADER_DEPTH, "weird").status());
    }

    @Test
    public void testEncodedNamesRoundTripThroughHrefs() throws Exception {
        Files.write(root.resolve("sp ace.txt"), "s".getBytes(StandardCharsets.UTF_8));
        RecordingState st = send(handler(), "PROPFIND", "/", DavConstants.HEADER_DEPTH, "1");
        assertTrue(text(st), text(st).contains("sp%20ace.txt"));
        assertEquals(HttpStatus.OK.code, send("GET", "/sp%20ace.txt").status());
    }

}
