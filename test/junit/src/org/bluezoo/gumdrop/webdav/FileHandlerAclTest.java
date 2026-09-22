/*
 * FileHandlerAclTest.java
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

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for RFC 3744 (WebDAV ACL) support in {@link FileHandler}:
 * privileges are checked via {@link Realm#isUserInRole} against the
 * already-authenticated {@link HttpResponseState#getPrincipal()}, not
 * against any separate ACL store this class maintains itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3744">RFC 3744</a>
 */
public class FileHandlerAclTest {

    private MemoryFileSystem mem;
    private Path root;
    private BasicRealm realm;

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        root = mem.getPath("/webroot");
        Files.createDirectories(root);
        Files.write(root.resolve("hello.txt"), "hi".getBytes(StandardCharsets.UTF_8));

        realm = new BasicRealm();
        realm.addToRole("alice", "webdav:read");
        realm.addToRole("alice", "webdav:write");
        realm.addToRole("bob", "webdav:read");
        // carol: no roles at all -- authenticated, but holds nothing
    }

    private FileHandler newHandler(Realm realmOrNull) {
        Map<String, String> types = new HashMap<String, String>();
        types.put("txt", "text/plain");
        // Mirrors FileRequestRouter's own allowedOptions construction
        // (only appending ", ACL" when a Realm is actually configured) --
        // this class is driven directly, bypassing that router, so the
        // test has to reproduce it to exercise handleOptions accurately.
        String allowedOptions = "GET, HEAD, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"
                + (realmOrNull != null ? ", ACL" : "");
        return new FileHandler(root, true, true, allowedOptions,
                new String[] { "index.html" }, types,
                new WebDAVLockManager(), null, realmOrNull);
    }

    /**
     * Dispatches a request with no body. Deliberately does NOT set a
     * Content-Length header -- matching HTTP/2, HTTP/3, and HTTP/1.1
     * chunked transfer-coding, none of which require or necessarily
     * carry one; {@link FileHandler} must not rely on it to know
     * whether a body is coming. Calls {@link FileHandler#requestComplete}
     * to match the real event contract (see
     * {@link org.bluezoo.gumdrop.http.server.HttpRequestHandler}): for a
     * genuinely bodyless request neither {@code startRequestBody} nor
     * {@code endRequestBody} ever fires, only this.
     */
    private RecordingState dispatch(FileHandler h, String method, String path, Principal principal)
            throws Exception {
        Headers req = new Headers();
        req.add(":method", method);
        req.add(":path", path);
        RecordingState st = new RecordingState(principal);
        h.headers(st, req);
        h.requestComplete(st);
        assertTrue("Response did not complete within timeout for " + method + " " + path,
                st.await(5, TimeUnit.SECONDS));
        return st;
    }

    /**
     * Dispatches a PROPFIND with a body requesting the given properties.
     * Deliberately does NOT set a Content-Length header (see
     * {@link #dispatch} -- same reasoning); {@link FileHandler} must
     * recognise a body has arrived from {@code requestBodyContent}
     * itself, not from Content-Length.
     */
    private RecordingState propfind(FileHandler h, String path, Principal principal, String... requestedProps)
            throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:prop>");
        for (String p : requestedProps) {
            body.append("<D:").append(p).append("/>");
        }
        body.append("</D:prop></D:propfind>");
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        Headers req = new Headers();
        req.add(":method", "PROPFIND");
        req.add(":path", path);
        req.add(DavConstants.HEADER_DEPTH, "0");
        RecordingState st = new RecordingState(principal);
        h.headers(st, req);
        h.startRequestBody(st);
        h.requestBodyContent(st, ByteBuffer.wrap(bodyBytes));
        h.endRequestBody(st);
        assertTrue("PROPFIND did not complete within timeout for " + path,
                st.await(5, TimeUnit.SECONDS));
        return st;
    }

    // ── OPTIONS / DAV header ────────────────────────────────────────────

    @Test
    public void testOptionsAdvertisesAccessControlWhenRealmConfigured() throws Exception {
        RecordingState st = dispatch(newHandler(realm), "OPTIONS", "/", null);
        assertEquals(HttpStatus.OK.code, st.status());
        assertTrue(st.header(DavConstants.HEADER_DAV).contains("access-control"));
        assertTrue(st.header("Allow").contains("ACL"));
    }

    @Test
    public void testOptionsOmitsAccessControlWhenNoRealmConfigured() throws Exception {
        RecordingState st = dispatch(newHandler(null), "OPTIONS", "/", null);
        assertEquals(HttpStatus.OK.code, st.status());
        assertFalse(st.header(DavConstants.HEADER_DAV).contains("access-control"));
        assertFalse(st.header("Allow").contains("ACL"));
    }

    // ── ACL method ───────────────────────────────────────────────────────

    @Test
    public void testAclMethodAlwaysForbidden() throws Exception {
        Principal alice = new TestPrincipal("alice");
        RecordingState st = dispatch(newHandler(realm), "ACL", "/hello.txt", alice);
        assertEquals(HttpStatus.FORBIDDEN.code, st.status());
        // The resource itself is untouched -- ACL was rejected, not silently ignored.
        assertTrue(Files.exists(root.resolve("hello.txt")));
    }

    @Test
    public void testAclMethodNotRecognisedWithoutRealm() throws Exception {
        RecordingState st = dispatch(newHandler(null), "ACL", "/hello.txt", null);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.code, st.status());
    }

    // ── current-user-privilege-set ──────────────────────────────────────

    @Test
    public void testCurrentUserPrivilegeSetReflectsGrantedRoles() throws Exception {
        Principal alice = new TestPrincipal("alice");
        RecordingState st = propfind(newHandler(realm), "/hello.txt", alice,
                DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("expected DAV:read privilege for alice: " + body,
                body.contains("<D:read/>") || body.contains(":read/>"));
        assertTrue("expected DAV:write privilege for alice: " + body,
                body.contains("<D:write/>") || body.contains(":write/>"));
    }

    @Test
    public void testCurrentUserPrivilegeSetEmptyForUnauthenticated() throws Exception {
        RecordingState st = propfind(newHandler(realm), "/hello.txt", null,
                DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertFalse("unauthenticated request should hold no privileges: " + body,
                body.contains(":read/>") || body.contains(":write/>") || body.contains(":all/>"));
    }

    @Test
    public void testCurrentUserPrivilegeSetEmptyForRoleless() throws Exception {
        Principal carol = new TestPrincipal("carol");
        RecordingState st = propfind(newHandler(realm), "/hello.txt", carol,
                DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertFalse("carol has no roles, so should hold no privileges: " + body,
                body.contains(":read/>") || body.contains(":write/>") || body.contains(":all/>"));
    }

    @Test
    public void testWriteGrantedOnlyThroughAllFourSubPrivileges() throws Exception {
        realm.addToRole("dave", "webdav:write-properties");
        realm.addToRole("dave", "webdav:write-content");
        realm.addToRole("dave", "webdav:bind");
        realm.addToRole("dave", "webdav:unbind");
        Principal dave = new TestPrincipal("dave");
        RecordingState st = propfind(newHandler(realm), "/hello.txt", dave,
                DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("dave holds all four write sub-privileges, so should be granted DAV:write: " + body,
                body.contains(":write/>"));
    }

    @Test
    public void testAllRoleGrantsEveryPrivilege() throws Exception {
        realm.addToRole("erin", "webdav:all");
        Principal erin = new TestPrincipal("erin");
        RecordingState st = propfind(newHandler(realm), "/hello.txt", erin,
                DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET);
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("webdav:all should report DAV:all: " + body, body.contains(":all/>"));
    }

    // ── acl / supported-privilege-set / owner / group / principal-collection-set ──

    @Test
    public void testAclContainsAceForAuthenticatedPrincipal() throws Exception {
        Principal alice = new TestPrincipal("alice");
        RecordingState st = propfind(newHandler(realm), "/hello.txt", alice, DavConstants.PROP_ACL);
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue("acl should contain an ace: " + body, body.contains(":ace>"));
        assertTrue("ace principal should be DAV:authenticated: " + body, body.contains(":authenticated/>"));
    }

    @Test
    public void testAclEmptyForUnauthenticated() throws Exception {
        RecordingState st = propfind(newHandler(realm), "/hello.txt", null, DavConstants.PROP_ACL);
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertFalse("unauthenticated request should get no ACEs: " + body, body.contains(":ace>"));
    }

    @Test
    public void testSupportedPrivilegeSetDescribesAllAndWriteAggregates() throws Exception {
        RecordingState st = propfind(newHandler(realm), "/hello.txt", null,
                DavConstants.PROP_SUPPORTED_PRIVILEGE_SET);
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains(":all/>"));
        assertTrue(body.contains(":write-properties/>"));
        assertTrue(body.contains(":write-acl/>"));
        assertTrue(body.contains(":abstract/>"));
    }

    @Test
    public void testOwnerGroupAndPrincipalCollectionSetAreEmpty() throws Exception {
        RecordingState st = propfind(newHandler(realm), "/hello.txt", null,
                DavConstants.PROP_OWNER, DavConstants.PROP_GROUP, DavConstants.PROP_PRINCIPAL_COLLECTION_SET);
        assertEquals(HttpStatus.MULTI_STATUS.code, st.status());
        String body = new String(st.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains(":owner"));
        assertTrue(body.contains(":group"));
        assertTrue(body.contains(":principal-collection-set"));
    }

    @Test
    public void testAclPropertiesAbsentWithoutRealm() throws Exception {
        // No Content-Length header -> handlePropfind treats this as an
        // allprop request (RFC 4918 §9.1).
        RecordingState allprop = dispatch(newHandler(null), "PROPFIND", "/hello.txt", null);
        assertEquals(HttpStatus.MULTI_STATUS.code, allprop.status());
        String body = new String(allprop.body(), StandardCharsets.UTF_8);
        assertFalse(body.contains(DavConstants.PROP_CURRENT_USER_PRIVILEGE_SET));
        assertFalse(body.contains(DavConstants.PROP_ACL));
    }

    // ── Test doubles ─────────────────────────────────────────────────────

    private static final class TestPrincipal implements Principal {
        private final String name;

        TestPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final class RecordingState implements HttpResponseState {
        private final Principal principal;
        private final Object lock = new Object();
        private final ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Headers responseHeaders;
        private int statusCode = -1;

        RecordingState(Principal principal) {
            this.principal = principal;
        }

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
                return responseHeaders == null ? null : responseHeaders.getValue(name);
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
        public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
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
        public HttpVersion getVersion() {
            return HttpVersion.HTTP_1_1;
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
            return principal;
        }
    }
}
