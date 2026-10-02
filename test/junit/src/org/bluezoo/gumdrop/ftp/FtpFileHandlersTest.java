/*
 * FtpFileHandlersTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleAwareFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Tests for the stock FTP connection handlers and the role-aware file system
 * decorator, using a stub realm and an in-memory file system.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpFileHandlersTest {

    private StubRealm realm;
    private BasicFTPFileSystem base;
    private Path root;
    private FtpConnectionMetadata meta;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        base = new BasicFTPFileSystem(root, false);
        realm = new StubRealm();
        realm.addUser("admin", "pw", FtpRoles.ADMIN);
        realm.addUser("deleter", "pw", FtpRoles.DELETE);
        realm.addUser("writer", "pw", FtpRoles.WRITE);
        realm.addUser("reader", "pw", FtpRoles.READ);
        realm.addUser("norole", "pw");
        meta = new FtpConnectionMetadata(
                new InetSocketAddress("127.0.0.1", 5000),
                new InetSocketAddress("127.0.0.1", 21),
                false, null, null, null, 0L, "ftp");
    }

    private FtpConnectionMetadata as(String user) {
        meta.setAuthenticatedUser(user);
        return meta;
    }

    // RoleBasedFTPHandler

    @Test(expected = IllegalArgumentException.class)
    public void testRoleHandlerNullRealm() {
        new RoleBasedFTPHandler(null, base);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRoleHandlerNullFileSystem() {
        new RoleBasedFTPHandler(realm, null);
    }

    @Test
    public void testRoleHandlerAuthenticate() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate(null, "pw", null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate(" ", "pw", null, meta));
        assertEquals(FtpAuthenticationResult.NEED_PASSWORD,
                h.authenticate("admin", null, null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate("ghost", "pw", null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                h.authenticate("admin", "bad", null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate("norole", "pw", null, meta));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                h.authenticate(" reader ", "pw", null, meta));
    }

    @Test
    public void testRoleHandlerWelcomeAndFs() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        assertNull(h.connected(meta));
        h.setWelcomeMessage("hi");
        assertEquals("hi", h.connected(meta));
        assertSame(base, h.getFileSystem(meta));
        assertNull(h.getQuotaManager());
        h.transferStarting("/a", true, 1, as("admin"));
        h.transferProgress("/a", true, ByteBuffer.allocate(1), 1, meta);
        h.transferCompleted("/a", false, 1, true, meta);
        h.disconnected(meta);
    }

    @Test
    public void testRoleHandlerAuthorizationMatrix() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        meta.setAuthenticatedUser(null);
        assertFalse(h.isAuthorized(FtpOperation.READ, "/", meta));
        FtpOperation[] ops = FtpOperation.values();
        for (int i = 0; i < ops.length; i++) {
            assertTrue(h.isAuthorized(ops[i], "/", as("admin")));
        }
        assertTrue(h.isAuthorized(FtpOperation.READ, "/", as("reader")));
        assertTrue(h.isAuthorized(FtpOperation.NAVIGATE, "/", as("reader")));
        assertFalse(h.isAuthorized(FtpOperation.WRITE, "/", as("reader")));
        assertFalse(h.isAuthorized(FtpOperation.CREATE_DIR, "/", as("reader")));
        assertTrue(h.isAuthorized(FtpOperation.WRITE, "/", as("writer")));
        assertTrue(h.isAuthorized(FtpOperation.CREATE_DIR, "/", as("writer")));
        assertFalse(h.isAuthorized(FtpOperation.DELETE, "/", as("writer")));
        assertTrue(h.isAuthorized(FtpOperation.DELETE, "/", as("deleter")));
        assertTrue(h.isAuthorized(FtpOperation.DELETE_DIR, "/", as("deleter")));
        assertTrue(h.isAuthorized(FtpOperation.RENAME, "/", as("deleter")));
        assertTrue(h.isAuthorized(FtpOperation.READ, "/", as("deleter")));
        assertFalse(h.isAuthorized(FtpOperation.SITE_COMMAND, "/", as("deleter")));
        assertFalse(h.isAuthorized(FtpOperation.ADMIN, "/", as("deleter")));
    }

    @Test
    public void testRoleHandlerSiteCommands() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("QUOTA", as("reader")));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                h.handleSiteCommand("SETQUOTA bob 10M", as("reader")));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                h.handleSiteCommand("OTHER", as("reader")));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("OTHER", as("admin")));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA bob 10M", as("admin")));
    }

    // Anonymous / Simple handlers

    @Test
    public void testAnonymousHandler() {
        AnonymousFTPHandler h = new AnonymousFTPHandler(base);
        String banner = h.connected(meta);
        assertTrue(banner.contains("Anonymous"));
        h.setWelcomeMessage("custom");
        assertEquals("custom", h.connected(meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate(null, "x", null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate("bob", "x", null, meta));
        assertEquals(FtpAuthenticationResult.NEED_PASSWORD,
                h.authenticate("Anonymous", null, null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                h.authenticate("ftp", "  ", null, meta));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                h.authenticate("ftp", "me@example.org", null, meta));
        assertSame(base, h.getFileSystem(meta));
        h.transferStarting("/a", true, 1, meta);
        h.transferStarting("/a", false, 5, meta);
        h.transferStarting("/a", false, -1, meta);
        h.transferProgress("/a", false, ByteBuffer.allocate(1), 10 * 1024 * 1024, meta);
        h.transferProgress("/a", true, ByteBuffer.allocate(1), 1, meta);
        h.transferCompleted("/a", true, 1, false, meta);
        h.transferCompleted("/a", false, 2048, true, meta);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                h.handleSiteCommand("X", meta));
        h.disconnected(meta);
    }

    @Test
    public void testSimpleHandlerWithRealm() {
        SimpleFTPHandler h = new SimpleFTPHandler(base, realm);
        assertNull(h.connected(meta));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                h.authenticate(" ", "x", null, meta));
        assertEquals(FtpAuthenticationResult.NEED_PASSWORD,
                h.authenticate("admin", null, null, meta));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                h.authenticate("admin", "pw", null, meta));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                h.authenticate("admin", "no", null, meta));
        assertSame(base, h.getFileSystem(meta));
        h.transferStarting("/a", true, 3, as("admin"));
        h.transferStarting("/a", false, -1, meta);
        h.transferProgress("/a", true, ByteBuffer.allocate(1), 1024 * 1024, meta);
        h.transferProgress("/a", true, ByteBuffer.allocate(1), 7, meta);
        h.transferCompleted("/a", true, 3, true, meta);
        h.transferCompleted("/a", false, 3, false, meta);
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("help", meta));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("nope", meta));
        h.disconnected(meta);
    }

    @Test
    public void testSimpleHandlerWithoutRealm() {
        SimpleFTPHandler h = new SimpleFTPHandler(base);
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                h.authenticate("bob", "  ", null, meta));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                h.authenticate("bob", "x", null, meta));
    }

    // RoleAwareFTPFileSystem

    @Test(expected = NullPointerException.class)
    public void testRoleAwareNullDelegate() {
        new RoleAwareFTPFileSystem(null, realm);
    }

    @Test(expected = NullPointerException.class)
    public void testRoleAwareNullRealm() {
        new RoleAwareFTPFileSystem(base, null);
    }

    @Test
    public void testRoleAwareReadOnlyUser() throws IOException {
        Files.createFile(root.resolve("f.txt"));
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base, realm);
        FtpConnectionMetadata m = as("reader");
        assertNotNull(fs.listDirectory("/", m));
        assertNotNull(fs.getFileInfo("/f.txt", m));
        assertNotNull(fs.changeDirectory("/", "/", m));
        assertNotNull(fs.openForReading("/f.txt", 0, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.createDirectory("/d", m));
        assertNull(fs.openForWriting("/g", false, m));
        assertNull(fs.resolvePathForAsyncWrite("/g", false, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/f.txt", "/h.txt", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.allocateSpace("/g", 1, m));
        FtpFileSystem.UniqueNameResult u = fs.generateUniqueName("/", "n", m);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, u.getResult());
        assertNull(u.getUniquePath());
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.deleteFile("/f.txt", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.removeDirectory("/d", m));
    }

    @Test
    public void testRoleAwareNoUser() {
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base, realm);
        meta.setAuthenticatedUser(null);
        assertNull(fs.listDirectory("/", meta));
        assertNull(fs.getFileInfo("/", meta));
        assertNull(fs.openForReading("/", 0, meta));
        assertNull(fs.resolvePathForAsyncRead("/", 0, meta));
        FtpFileSystem.DirectoryChangeResult r =
                fs.changeDirectory("/", "/cur", meta);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, r.getResult());
        assertEquals("/cur", r.getNewDirectory());
    }

    @Test
    public void testRoleAwareWriterAndDeleter() throws IOException {
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base, realm);
        FtpConnectionMetadata m = as("writer");
        assertEquals(FtpFileOperationResult.SUCCESS, fs.createDirectory("/d", m));
        assertNotNull(fs.generateUniqueName("/", "n", m));
        assertEquals(FtpFileOperationResult.SUCCESS, fs.allocateSpace("/g", 1, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED, fs.removeDirectory("/d", m));
        m = as("deleter");
        assertEquals(FtpFileOperationResult.SUCCESS, fs.removeDirectory("/d", m));
    }

    @Test
    public void testRoleAwareCustomRolesAndConfinement() throws IOException {
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base, realm);
        fs.setReadRole("custom-read");
        fs.setWriteRole("custom-write");
        fs.setDeleteRole("custom-delete");
        realm.addUser("cust", "pw", "custom-read");
        assertNotNull(fs.listDirectory("/", as("cust")));
        assertNull(fs.openForWriting("/x", false, meta));
        Files.createDirectories(root.resolve("home/cust"));
        fs.setHomeDirectoryConfinement(true);
        assertNotNull(fs.listDirectory("/home/cust", as("cust")));
        assertNotNull(fs.listDirectory("/home/cust/", meta));
        assertNull(fs.listDirectory("/home/other", meta));
        assertNull(fs.listDirectory("/home/cust/../other", meta));
        assertNull(fs.listDirectory("", meta));
        assertNull(fs.listDirectory(null, meta));
        meta.setAuthenticatedUser(null);
        assertNull(fs.listDirectory("/home/cust", meta));
    }

    // Stub realm

    static class StubRealm implements Realm {
        private final Map<String, String> passwords = new HashMap<String, String>();
        private final Map<String, Set<String>> roles =
                new HashMap<String, Set<String>>();

        void addUser(String user, String pw, String... userRoles) {
            passwords.put(user, pw);
            Set<String> set = new HashSet<String>();
            for (int i = 0; i < userRoles.length; i++) {
                set.add(userRoles[i]);
            }
            roles.put(user, set);
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return new HashSet<SaslMechanism>();
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            String pw = passwords.get(username);
            return pw != null && pw.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public String getPassword(String username) {
            return passwords.get(username);
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            Set<String> set = roles.get(username);
            return set != null && set.contains(role);
        }

        @Override
        public boolean userExists(String username) {
            return passwords.containsKey(username);
        }
    }
}
