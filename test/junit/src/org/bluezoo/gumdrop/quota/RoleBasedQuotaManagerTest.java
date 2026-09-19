/*
 * RoleBasedQuotaManagerTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RoleBasedQuotaManager} quota resolution and usage
 * accounting. No storage directory is configured, so nothing touches disk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RoleBasedQuotaManagerTest {

    private static final class StubRealm implements Realm {
        private final Map<String, Set<String>> roles = new HashMap<String, Set<String>>();

        void grant(String user, String role) {
            Set<String> set = roles.get(user);
            if (set == null) {
                set = new HashSet<String>();
                roles.put(user, set);
            }
            set.add(role);
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return false;
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public String getPassword(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            Set<String> set = roles.get(username);
            return set != null && set.contains(role);
        }
    }

    private RoleBasedQuotaManager manager;
    private StubRealm realm;

    @Before
    public void setUp() {
        manager = new RoleBasedQuotaManager();
        realm = new StubRealm();
        manager.setRealm(realm);
    }

    @Test
    public void unlimitedWhenNothingConfigured() {
        Quota quota = manager.getQuota("nobody");
        assertTrue(quota.isStorageUnlimited());
        assertTrue(manager.canStore("nobody", Long.MAX_VALUE / 2));
        assertTrue(manager.canStoreMessage("nobody"));
    }

    @Test
    public void defaultQuotaApplies() {
        manager.setDefaultQuota("1KB");
        Quota quota = manager.getQuota("bob");
        assertEquals(1024L, quota.getStorageLimit());
        assertEquals(QuotaSource.DEFAULT, quota.getSource());
        assertTrue(manager.canStore("bob", 1024L));
        assertFalse(manager.canStore("bob", 1025L));
    }

    @Test
    public void defaultPolicyObjectApplies() {
        manager.setDefaultPolicy(new QuotaPolicy("d", 500L, 2L));
        Quota quota = manager.getQuota("bob");
        assertEquals(500L, quota.getStorageLimit());
        assertEquals(2L, quota.getMessageLimit());
    }

    @Test
    public void mostGenerousRoleWins() {
        manager.setDefaultQuota("1KB");
        manager.addRoleQuota("standard", "1MB", "10");
        manager.addRoleQuota("premium", "10MB", "100");
        realm.grant("carol", "standard");
        realm.grant("carol", "premium");

        Quota quota = manager.getQuota("carol");
        assertEquals(10L * 1024L * 1024L, quota.getStorageLimit());
        assertEquals(100L, quota.getMessageLimit());
        assertEquals(QuotaSource.ROLE, quota.getSource());
        assertEquals("premium", quota.getSourceDetail());
    }

    @Test
    public void unlimitedRoleBeatsLimitedRole() {
        manager.addRoleQuota("standard", "1MB", "10");
        manager.setRoleQuota("admin", "unlimited");
        realm.grant("dave", "standard");
        realm.grant("dave", "admin");

        Quota quota = manager.getQuota("dave");
        assertTrue(quota.isStorageUnlimited());
        assertTrue(quota.isMessageUnlimited());
    }

    @Test
    public void userWithoutMatchingRoleFallsBackToDefault() {
        manager.setDefaultQuota("2KB");
        manager.addRoleQuota("premium", "10MB");
        Quota quota = manager.getQuota("erin");
        assertEquals(2048L, quota.getStorageLimit());
        assertEquals(QuotaSource.DEFAULT, quota.getSource());
    }

    @Test
    public void userOverrideBeatsRoles() {
        manager.addRoleQuota("premium", "10MB");
        realm.grant("frank", "premium");
        assertFalse(manager.hasUserQuota("frank"));
        manager.setUserQuota("frank", 100L, 3L);
        assertTrue(manager.hasUserQuota("frank"));

        Quota quota = manager.getQuota("frank");
        assertEquals(100L, quota.getStorageLimit());
        assertEquals(3L, quota.getMessageLimit());
        assertEquals(QuotaSource.USER, quota.getSource());

        manager.clearUserQuota("frank");
        assertFalse(manager.hasUserQuota("frank"));
        Quota after = manager.getQuota("frank");
        assertEquals(QuotaSource.ROLE, after.getSource());
    }

    @Test
    public void quotaIsCachedUntilRecalculated() {
        manager.setDefaultQuota("1KB");
        Quota first = manager.getQuota("gina");
        assertSame(first, manager.getQuota("gina"));
        manager.recalculateUsage("gina");
        assertNotSame(first, manager.getQuota("gina"));
    }

    @Test
    public void bytesAndMessagesAreAccounted() {
        manager.setDefaultPolicy(new QuotaPolicy("d", 1000L, 2L));
        manager.recordBytesAdded("hal", 400L);
        assertEquals(400L, manager.getQuota("hal").getStorageUsed());
        manager.recordBytesRemoved("hal", 100L);
        assertEquals(300L, manager.getQuota("hal").getStorageUsed());

        manager.recordMessageAdded("hal", 50L);
        assertEquals(350L, manager.getQuota("hal").getStorageUsed());
        assertEquals(1L, manager.getQuota("hal").getMessageCount());
        assertTrue(manager.canStoreMessage("hal"));
        manager.recordMessageAdded("hal", 50L);
        assertFalse(manager.canStoreMessage("hal"));
        manager.recordMessageRemoved("hal", 50L);
        assertEquals(1L, manager.getQuota("hal").getMessageCount());
        assertTrue(manager.canStoreMessage("hal"));
    }

    @Test
    public void persistenceIsNoOpWithoutStorageDir() {
        manager.setDefaultQuota("1KB");
        manager.recordBytesAdded("ivy", 10L);
        manager.saveUsageData();
        manager.loadUsageData();
        assertEquals(10L, manager.getQuota("ivy").getStorageUsed());
    }
}
