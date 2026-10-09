/*
 * FtpFileHandlersQuotaTest.java
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
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ftp.file.AnonymousFTPHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleAwareFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler;
import org.bluezoo.gumdrop.ftp.file.SimpleFTPHandler;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SynchronousRealm;
import org.bluezoo.gumdrop.ftp.FtpFileHandlersTest.StubRealm;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaSource;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Quota-aware SITE command handling in the role-based handler, the
 * confinement and role matrix of the role-aware file system, and the
 * address-less and failing-realm paths of the stock handlers.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpFileHandlersQuotaTest {

    private StubRealm realm;
    private BasicFTPFileSystem base;
    private FtpConnectionMetadata meta;
    private FtpConnectionMetadata noAddr;
    private MapQuota quotas;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        base = new BasicFTPFileSystem(root, false);
        realm = new StubRealm();
        realm.addUser("admin", "pw", FtpRoles.ADMIN);
        realm.addUser("reader", "pw", FtpRoles.READ);
        realm.addUser("all", "pw", FtpRoles.READ, FtpRoles.WRITE, FtpRoles.DELETE);
        meta = new FtpConnectionMetadata(
                new InetSocketAddress("127.0.0.1", 5000),
                new InetSocketAddress("127.0.0.1", 21),
                false, null, null, null, 0L, "ftp");
        noAddr = new FtpConnectionMetadata(null, null,
                false, null, null, null, 0L, "ftp");
        quotas = new MapQuota();
    }

    private FtpConnectionMetadata as(String user) {
        meta.setAuthenticatedUser(user);
        meta.setRoles(realm.rolesOf(user));
        return meta;
    }

    // SITE QUOTA

    @Test
    public void testSiteQuotaReportsOwnUsageByRoleSource() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        h.setQuotaManager(quotas);
        assertSame(quotas, h.getQuotaManager());
        Quota q = new Quota(10L * 1024 * 1024, 100);
        q.setSource(QuotaSource.ROLE, "gold");
        q.setStorageUsed(5L * 1024 * 1024);
        quotas.quotas.put("reader", q);
        FtpConnectionMetadata m = as("reader");
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("QUOTA", m));
        String response = m.getSiteCommandResponse();
        assertTrue(response, response.contains("reader"));
        assertTrue(response, response.contains("gold"));
        assertTrue(response, response.contains("50"));
    }

    @Test
    public void testSiteQuotaSourcesAndUnlimited() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        h.setQuotaManager(quotas);
        FtpConnectionMetadata m = as("reader");
        Quota user = new Quota(2048, 5);
        user.setSource(QuotaSource.USER, null);
        quotas.quotas.put("reader", user);
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("quota", m));
        String userResponse = m.getSiteCommandResponse();
        Quota dflt = new Quota(2048, 5);
        dflt.setSource(QuotaSource.DEFAULT, null);
        quotas.quotas.put("reader", dflt);
        h.handleSiteCommand("QUOTA", m);
        String defaultResponse = m.getSiteCommandResponse();
        Quota none = Quota.unlimited();
        quotas.quotas.put("reader", none);
        h.handleSiteCommand("QUOTA", m);
        String noneResponse = m.getSiteCommandResponse();
        assertFalse(userResponse.equals(defaultResponse));
        assertFalse(defaultResponse.equals(noneResponse));
        assertFalse(userResponse.equals(noneResponse));
    }

    @Test
    public void testSiteQuotaAdminMayQueryOtherUserButReaderMayNot() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        h.setQuotaManager(quotas);
        Quota q = new Quota(4096, 5);
        quotas.quotas.put("bob", q);
        quotas.quotas.put("reader", new Quota(8192, 5));
        FtpConnectionMetadata m = as("admin");
        quotas.quotas.put("admin", new Quota(1, 1));
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("QUOTA bob", m));
        assertTrue(m.getSiteCommandResponse().contains("bob"));
        m = as("reader");
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("QUOTA bob", m));
        assertTrue(m.getSiteCommandResponse().contains("reader"));
        assertFalse(m.getSiteCommandResponse().contains("bob"));
    }

    // SITE SETQUOTA

    @Test
    public void testSiteSetQuotaParsesArguments() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        h.setQuotaManager(quotas);
        FtpConnectionMetadata m = as("admin");
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("SETQUOTA bob 10M", m));
        assertEquals("bob", quotas.lastUser);
        assertEquals(10L * 1024 * 1024, quotas.lastStorage);
        assertEquals(-1L, quotas.lastMessages);
        assertTrue(m.getSiteCommandResponse().contains("bob"));
        assertEquals(FtpFileOperationResult.SUCCESS,
                h.handleSiteCommand("SETQUOTA   carol   1G   250  extra", m));
        assertEquals("carol", quotas.lastUser);
        assertEquals(1024L * 1024 * 1024, quotas.lastStorage);
        assertEquals(250L, quotas.lastMessages);
    }

    @Test
    public void testSiteSetQuotaRejectsBadArguments() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        h.setQuotaManager(quotas);
        FtpConnectionMetadata m = as("admin");
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA bob", m));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA ", m));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA bob lots", m));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA bob 1M many", m));
        assertNull(quotas.lastUser);
    }

    @Test
    public void testSiteQuotaWithoutManagerAndNoAddress() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        noAddr.setAuthenticatedUser("admin");
        noAddr.setRoles(realm.rolesOf("admin"));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("QUOTA", noAddr));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED,
                h.handleSiteCommand("SETQUOTA bob 1M", noAddr));
        assertEquals(FtpAuthenticationResult.INVALID_USER,
                FtpFileHandlersTest.auth(h, "ghost", "pw", null, noAddr));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                FtpFileHandlersTest.auth(h, "reader", "pw", null, noAddr));
    }

    @Test
    public void testRoleHandlerUnauthenticatedIsNotAuthorizedForAnything() {
        RoleBasedFTPHandler h = new RoleBasedFTPHandler(realm, base);
        noAddr.setAuthenticatedUser(null);
        FtpOperation[] ops = FtpOperation.values();
        for (int i = 0; i < ops.length; i++) {
            assertFalse(h.isAuthorized(ops[i], "/", noAddr));
        }
        FtpConnectionMetadata m = as("all");
        for (int i = 0; i < ops.length; i++) {
            boolean expected = ops[i] != FtpOperation.SITE_COMMAND
                    && ops[i] != FtpOperation.ADMIN;
            assertEquals(ops[i].name(), expected, h.isAuthorized(ops[i], "/", m));
        }
    }

    // Role-aware file system: every operation, each guard in turn

    @Test
    public void testRoleAwareConfinementDeniesEveryOperationOutsideHome() {
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base);
        fs.homeDirectoryConfinement(true);
        FtpConnectionMetadata m = as("all");
        assertNull(fs.listDirectory("/etc", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.changeDirectory("/etc", "/home/all", m).getResult());
        assertNull(fs.getFileInfo("/etc", m));
        assertNull(fs.openForReading("/etc", 0, m));
        assertNull(fs.resolvePathForAsyncRead("/etc", 0, m));
        assertNull(fs.openForWriting("/etc/x", false, m));
        assertNull(fs.resolvePathForAsyncWrite("/etc/x", false, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.createDirectory("/etc/d", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/home/all/a", "/etc/b", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/etc/a", "/home/all/b", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.generateUniqueName("/etc", "n", m).getResult());
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.allocateSpace("/etc/x", 1, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.deleteFile("/etc/x", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.removeDirectory("/etc/d", m));
    }

    @Test
    public void testRoleAwareConfinementAllowsHomeForEveryOperation() throws IOException {
        Path root = base.getRootPath();
        Files.createDirectories(root.resolve("home/all"));
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base);
        fs.homeDirectoryConfinement(true);
        FtpConnectionMetadata m = as("all");
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.createDirectory("/home/all/d", m));
        assertNotNull(fs.openForWriting("/home/all/f", false, m));
        assertNotNull(fs.resolvePathForAsyncWrite("/home/all/f", false, m));
        assertNotNull(fs.resolvePathForAsyncRead("/home/all/f", 0, m));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.rename("/home/all/f", "/home/all/g", m));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.generateUniqueName("/home/all", "n", m).getResult());
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.allocateSpace("/home/all/x", 1, m));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.deleteFile("/home/all/g", m));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.removeDirectory("/home/all/d", m));
        assertEquals(FtpFileOperationResult.SUCCESS,
                fs.changeDirectory("/home/all", "/", m).getResult());
    }

    @Test
    public void testRoleAwareWithoutAnyRoleDeniesEveryOperation() {
        realm.addUser("nobody", "pw");
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base);
        FtpConnectionMetadata m = as("nobody");
        assertNull(fs.listDirectory("/", m));
        assertNull(fs.getFileInfo("/", m));
        assertNull(fs.openForReading("/", 0, m));
        assertNull(fs.resolvePathForAsyncRead("/", 0, m));
        assertNull(fs.openForWriting("/x", true, m));
        assertNull(fs.resolvePathForAsyncWrite("/x", true, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.changeDirectory("/", "/", m).getResult());
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.createDirectory("/d", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.deleteFile("/d", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.removeDirectory("/d", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/a", "/b", m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.allocateSpace("/a", 1, m));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.generateUniqueName("/", "n", m).getResult());
    }

    @Test
    public void testRoleAwareNoUserDeniesWritesAndDeletes() {
        RoleAwareFTPFileSystem fs = new RoleAwareFTPFileSystem(base);
        noAddr.setAuthenticatedUser(null);
        assertNull(fs.openForWriting("/x", false, noAddr));
        assertNull(fs.resolvePathForAsyncWrite("/x", false, noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.createDirectory("/d", noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.deleteFile("/d", noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.removeDirectory("/d", noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.rename("/a", "/b", noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.allocateSpace("/a", 1, noAddr));
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                fs.generateUniqueName("/", "n", noAddr).getResult());
    }

    // Stock handlers without a client address / with a failing realm

    @Test
    public void testSimpleHandlerWithoutClientAddress() {
        SimpleFTPHandler h = new SimpleFTPHandler(base);
        assertNull(h.connected(noAddr));
        assertEquals(FtpAuthenticationResult.SUCCESS,
                FtpFileHandlersTest.auth(h, "bob", "x", null, noAddr));
        h.disconnected(noAddr);
        SimpleFTPHandler r = new SimpleFTPHandler(base, realm);
        assertNull(r.connected(noAddr));
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                FtpFileHandlersTest.auth(r, "admin", "wrong", null, noAddr));
    }

    @Test
    public void testSimpleHandlerRealmFailureIsInvalidPassword() {
        Realm failing = new FailingRealm();
        SimpleFTPHandler h = new SimpleFTPHandler(base, failing);
        assertEquals(FtpAuthenticationResult.INVALID_PASSWORD,
                FtpFileHandlersTest.auth(h, "admin", "pw", null, meta));
    }

    @Test
    public void testAnonymousHandlerTransferEdges() {
        AnonymousFTPHandler h = new AnonymousFTPHandler(base);
        h.transferProgress("/a", false, ByteBuffer.allocate(1),
                10L * 1024 * 1024, noAddr);
        h.transferProgress("/a", false, ByteBuffer.allocate(1), 3, noAddr);
        h.transferProgress("/a", true, ByteBuffer.allocate(1),
                10L * 1024 * 1024, noAddr);
        h.transferStarting("/a", true, 1, noAddr);
        h.transferCompleted("/a", true, 1, true, noAddr);
        h.transferCompleted("/a", false, 5000, false, noAddr);
        h.disconnected(noAddr);
        assertEquals(FtpFileOperationResult.ACCESS_DENIED,
                h.handleSiteCommand("HELP", noAddr));
    }

    private static final class FailingRealm implements SynchronousRealm {
        private final StubRealm inner = new StubRealm();

        @Override
        public java.util.Set<org.bluezoo.gumdrop.auth.SaslMechanism> getSupportedSASLMechanisms() {
            return inner.getSupportedSASLMechanisms();
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            throw new IllegalStateException("realm down");
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public boolean userExists(String username) {
            return false;
        }
    }

    private static final class MapQuota implements QuotaManager {
        final Map<String, Quota> quotas = new HashMap<String, Quota>();
        String lastUser;
        long lastStorage;
        long lastMessages;

        @Override
        public Quota getQuota(String u) {
            return quotas.get(u);
        }

        @Override
        public void recalculateUsage(String u) {
        }

        @Override
        public boolean canStore(String u, long b) {
            return true;
        }

        @Override
        public boolean canStoreMessage(String u) {
            return true;
        }

        @Override
        public void recordBytesAdded(String u, long b) {
        }

        @Override
        public void recordBytesRemoved(String u, long b) {
        }

        @Override
        public void recordMessageAdded(String u, long s) {
        }

        @Override
        public void recordMessageRemoved(String u, long s) {
        }

        @Override
        public void setUserQuota(String u, long s, long m) {
            lastUser = u;
            lastStorage = s;
            lastMessages = m;
        }

        @Override
        public void clearUserQuota(String u) {
        }

        @Override
        public boolean hasUserQuota(String u) {
            return false;
        }

        @Override
        public void saveUsageData() {
        }

        @Override
        public void loadUsageData() {
        }
    }

}
