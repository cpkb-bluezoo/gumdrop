/*
 * RoleBasedQuotaManagerPersistenceTest.java
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

package org.bluezoo.gumdrop.quota;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the persistence of {@link RoleBasedQuotaManager} (usage and
 * per-user policy files in an in-memory file system). With no live Gumdrop
 * runtime the usage save runs inline, so every assertion is deterministic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RoleBasedQuotaManagerPersistenceTest {

    private Path dir;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem fs = MemoryFileSystem.create();
        dir = fs.getPath("/quota");
        Files.createDirectories(dir);
    }

    private RoleBasedQuotaManager manager() {
        RoleBasedQuotaManager m = new RoleBasedQuotaManager();
        m.storageDir(dir);
        return m;
    }

    private void write(String name, String content) throws IOException {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void setStorageDirCreatesMissingDirectory() {
        Path sub = dir.resolve("a").resolve("b");
        RoleBasedQuotaManager m = new RoleBasedQuotaManager();
        m.storageDir(sub);
        assertTrue(Files.isDirectory(sub));
    }

    @Test
    public void recordedUsageIsWrittenAndReloaded() {
        RoleBasedQuotaManager m = manager();
        m.recordBytesAdded("alice", 100L);
        m.recordMessageAdded("alice", 50L);
        m.recordBytesRemoved("alice", 20L);
        m.recordMessageRemoved("alice", 10L);
        assertTrue(Files.isRegularFile(dir.resolve("alice.usage")));
        RoleBasedQuotaManager second = manager();
        Quota q = second.getQuota("alice");
        assertEquals(120L, q.getStorageUsed());
        assertEquals(0L, q.getMessageCount());
    }

    @Test
    public void saveUsageDataWritesEveryCachedUser() {
        RoleBasedQuotaManager m = manager();
        Quota a = m.getQuota("u1");
        a.addStorageUsed(5L);
        Quota b = m.getQuota("u2");
        b.addStorageUsed(7L);
        m.saveUsageData();
        assertTrue(Files.isRegularFile(dir.resolve("u1.usage")));
        assertTrue(Files.isRegularFile(dir.resolve("u2.usage")));
    }

    @Test
    public void userPolicyPersistsAndLoads() {
        RoleBasedQuotaManager m = manager();
        m.setUserQuota("carol", 2048L, 9L);
        assertTrue(m.hasUserQuota("carol"));
        assertTrue(Files.isRegularFile(dir.resolve("carol.policy")));
        RoleBasedQuotaManager second = manager();
        assertFalse(second.hasUserQuota("carol"));
        second.loadUsageData();
        assertTrue(second.hasUserQuota("carol"));
        Quota q = second.getQuota("carol");
        assertEquals(2048L, q.getStorageLimit());
        assertEquals(QuotaSource.USER, q.getSource());
    }

    @Test
    public void clearUserQuotaDeletesPolicyFile() {
        RoleBasedQuotaManager m = manager();
        m.setUserQuota("dave", 10L, 1L);
        Path f = dir.resolve("dave.policy");
        assertTrue(Files.isRegularFile(f));
        m.clearUserQuota("dave");
        assertFalse(Files.exists(f));
        assertFalse(m.hasUserQuota("dave"));
        m.clearUserQuota("dave");
    }

    @Test
    public void loadUsageDataReadsUsageAndSkipsOtherFiles() throws IOException {
        write("erin.usage", "storage.used=300\nmessage.count=4\n");
        write("notes.txt", "ignored");
        RoleBasedQuotaManager m = manager();
        m.loadUsageData();
        Quota q = m.getQuota("erin");
        assertEquals(300L, q.getStorageUsed());
        assertEquals(4L, q.getMessageCount());
    }

    @Test
    public void usageFileWithMissingKeysLeavesDefaults() throws IOException {
        write("frank.usage", "other=1\n");
        RoleBasedQuotaManager m = manager();
        Quota q = m.getQuota("frank");
        assertEquals(0L, q.getStorageUsed());
        assertEquals(0L, q.getMessageCount());
    }

    @Test
    public void corruptUsageFileIsIgnored() throws IOException {
        write("gina.usage", "storage.used=notanumber\n");
        RoleBasedQuotaManager m = manager();
        Quota q = m.getQuota("gina");
        assertEquals(0L, q.getStorageUsed());
    }

    @Test
    public void corruptPolicyFileIsIgnored() throws IOException {
        write("hal.policy", "storage.limit=zzz\n");
        RoleBasedQuotaManager m = manager();
        m.loadUsageData();
        assertFalse(m.hasUserQuota("hal"));
    }

    @Test
    public void policyFileWithoutKeysIsUnlimited() throws IOException {
        write("ida.policy", "unrelated=1\n");
        RoleBasedQuotaManager m = manager();
        m.loadUsageData();
        assertTrue(m.hasUserQuota("ida"));
        Quota q = m.getQuota("ida");
        assertTrue(q.isStorageUnlimited());
    }

    @Test
    public void loadUsageDataWithoutStorageDirIsNoOp() {
        RoleBasedQuotaManager m = new RoleBasedQuotaManager();
        m.loadUsageData();
        m.recalculateUsage("anyone");
        assertTrue(m.getQuota("anyone").isStorageUnlimited());
    }

    @Test
    public void loadUsageDataWithRemovedDirectoryIsNoOp() throws IOException {
        Path sub = dir.resolve("gone");
        RoleBasedQuotaManager m = new RoleBasedQuotaManager();
        m.storageDir(sub);
        Files.delete(sub);
        m.loadUsageData();
    }

    @Test
    public void unreadableUsagePathIsHandled() throws IOException {
        RoleBasedQuotaManager m = manager();
        Files.createDirectory(dir.resolve("jo.usage"));
        Quota q = m.getQuota("jo");
        assertEquals(0L, q.getStorageUsed());
        q.addStorageUsed(3L);
        m.saveUsageData();
    }

    @Test
    public void unwritableUsagePathIsHandled() throws IOException {
        RoleBasedQuotaManager m = manager();
        Files.createDirectory(dir.resolve("kim.policy"));
        m.setUserQuota("kim", 1L, 1L);
        assertTrue(m.hasUserQuota("kim"));
    }

    @Test
    public void addRoleQuotaWithMessageLimit() {
        RoleBasedQuotaManager m = new RoleBasedQuotaManager();
        m.addRoleQuota("staff", "1KB", "5");
        m.addRoleQuota("plain", "2KB");
        m.defaultQuota("1KB");
        Quota q = m.getQuota("x");
        assertEquals(1024L, q.getStorageLimit());
    }
}
