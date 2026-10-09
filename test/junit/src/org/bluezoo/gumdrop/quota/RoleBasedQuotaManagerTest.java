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

import org.bluezoo.gumdrop.auth.SynchronousRealm;
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

    private static final class StubRealm implements SynchronousRealm {
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
        manager.realm(realm);
    }

    /** Resolves the user's roles as a login would, then reads the quota. */
    private Quota quotaOf(String user) {
        final boolean[] ready = new boolean[1];
        manager.prepare(user, null, new Runnable() {
            @Override
            public void run() {
                ready[0] = true;
            }
        });
        assertTrue("an in-memory realm resolves roles inline", ready[0]);
        return manager.getQuota(user);
    }

    @Test
    public void unlimitedWhenNothingConfigured() {
        Quota quota = quotaOf("nobody");
        assertTrue(quota.isStorageUnlimited());
        assertTrue(manager.canStore("nobody", Long.MAX_VALUE / 2));
        assertTrue(manager.canStoreMessage("nobody"));
    }

    @Test
    public void defaultQuotaApplies() {
        manager.defaultQuota("1KB");
        Quota quota = quotaOf("bob");
        assertEquals(1024L, quota.getStorageLimit());
        assertEquals(QuotaSource.DEFAULT, quota.getSource());
        assertTrue(manager.canStore("bob", 1024L));
        assertFalse(manager.canStore("bob", 1025L));
    }

    @Test
    public void defaultPolicyObjectApplies() {
        manager.defaultPolicy(new QuotaPolicy("d", 500L, 2L));
        Quota quota = quotaOf("bob");
        assertEquals(500L, quota.getStorageLimit());
        assertEquals(2L, quota.getMessageLimit());
    }

    @Test
    public void mostGenerousRoleWins() {
        manager.defaultQuota("1KB");
        manager.addRoleQuota("standard", "1MB", "10");
        manager.addRoleQuota("premium", "10MB", "100");
        realm.grant("carol", "standard");
        realm.grant("carol", "premium");

        Quota quota = quotaOf("carol");
        assertEquals(10L * 1024L * 1024L, quota.getStorageLimit());
        assertEquals(100L, quota.getMessageLimit());
        assertEquals(QuotaSource.ROLE, quota.getSource());
        assertEquals("premium", quota.getSourceDetail());
    }

    @Test
    public void unlimitedRoleBeatsLimitedRole() {
        manager.addRoleQuota("standard", "1MB", "10");
        manager.addRoleQuota("admin", "unlimited");
        realm.grant("dave", "standard");
        realm.grant("dave", "admin");

        Quota quota = quotaOf("dave");
        assertTrue(quota.isStorageUnlimited());
        assertTrue(quota.isMessageUnlimited());
    }

    @Test
    public void userWithoutMatchingRoleFallsBackToDefault() {
        manager.defaultQuota("2KB");
        manager.addRoleQuota("premium", "10MB");
        Quota quota = quotaOf("erin");
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

        Quota quota = quotaOf("frank");
        assertEquals(100L, quota.getStorageLimit());
        assertEquals(3L, quota.getMessageLimit());
        assertEquals(QuotaSource.USER, quota.getSource());

        manager.clearUserQuota("frank");
        assertFalse(manager.hasUserQuota("frank"));
        Quota after = quotaOf("frank");
        assertEquals(QuotaSource.ROLE, after.getSource());
    }

    @Test
    public void quotaIsCachedUntilRecalculated() {
        manager.defaultQuota("1KB");
        Quota first = quotaOf("gina");
        assertSame(first, quotaOf("gina"));
        manager.recalculateUsage("gina");
        assertNotSame(first, quotaOf("gina"));
    }

    @Test
    public void bytesAndMessagesAreAccounted() {
        manager.defaultPolicy(new QuotaPolicy("d", 1000L, 2L));
        manager.recordBytesAdded("hal", 400L);
        assertEquals(400L, quotaOf("hal").getStorageUsed());
        manager.recordBytesRemoved("hal", 100L);
        assertEquals(300L, quotaOf("hal").getStorageUsed());

        manager.recordMessageAdded("hal", 50L);
        assertEquals(350L, quotaOf("hal").getStorageUsed());
        assertEquals(1L, quotaOf("hal").getMessageCount());
        assertTrue(manager.canStoreMessage("hal"));
        manager.recordMessageAdded("hal", 50L);
        assertFalse(manager.canStoreMessage("hal"));
        manager.recordMessageRemoved("hal", 50L);
        assertEquals(1L, quotaOf("hal").getMessageCount());
        assertTrue(manager.canStoreMessage("hal"));
    }

    @Test
    public void persistenceIsNoOpWithoutStorageDir() {
        manager.defaultQuota("1KB");
        manager.recordBytesAdded("ivy", 10L);
        manager.saveUsageData();
        manager.loadUsageData();
        assertEquals(10L, quotaOf("ivy").getStorageUsed());
    }

    @Test
    public void prepareWaitsForASlowRealmThenQuotaUsesItsRoles() {
        manager.defaultQuota("1KB");
        manager.addRoleQuota("premium", "10MB", "100");
        final java.util.List<Runnable> held = new java.util.ArrayList<Runnable>();
        manager.realm(new SynchronousRealm() {
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
            public boolean isUserInRole(String username, String role) {
                return "premium".equals(role);
            }

            @Override
            public void isUserInRole(final String username, final String role,
                    final org.bluezoo.gumdrop.auth.RealmCallback<Boolean> callback) {
                held.add(new Runnable() {
                    @Override
                    public void run() {
                        callback.completed(Boolean.valueOf(isUserInRole(username, role)));
                    }
                });
            }
        });
        final boolean[] ready = new boolean[1];
        manager.prepare("zoe", null, new Runnable() {
            @Override
            public void run() {
                ready[0] = true;
            }
        });
        assertFalse("must not report ready before the realm answers", ready[0]);
        while (!held.isEmpty()) {
            held.remove(0).run();
        }
        assertTrue(ready[0]);
        assertEquals(10L * 1024L * 1024L, manager.getQuota("zoe").getStorageLimit());
    }

    @Test
    public void prepareWithFailingRealmGrantsNoRoleQuota() {
        manager.defaultQuota("1KB");
        manager.addRoleQuota("premium", "10MB", "100");
        manager.realm(new SynchronousRealm() {
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
            public boolean isUserInRole(String username, String role) {
                throw new IllegalStateException("realm down");
            }
        });
        Quota quota = quotaOf("yan");
        assertEquals(1024L, quota.getStorageLimit());
    }
}
