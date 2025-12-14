/*
 * SessionTest.java
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

package org.bluezoo.gumdrop.servlet.session;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for the Session class.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic session operations (attributes, timestamps)</li>
 *   <li>Dirty tracking for cluster replication</li>
 *   <li>Delta replication logic</li>
 *   <li>Version ordering</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionTest {

    private MockSessionContext context;
    private static final String TEST_SESSION_ID = "0123456789abcdef0123456789abcdef";

    @Before
    public void setUp() {
        context = new MockSessionContext();
        context.setSessionTimeout(1800);
    }

    // ===== Basic Session Tests =====

    @Test
    public void testSessionCreation() {
        Session session = new Session(context, TEST_SESSION_ID);

        assertEquals(TEST_SESSION_ID, session.getId());
        assertTrue(session.getCreationTime() > 0);
        assertEquals(session.getCreationTime(), session.getLastAccessedTime());
        assertEquals(1800, session.getMaxInactiveInterval());
        assertTrue(session.isNew());
    }

    @Test
    public void testSessionContextPath() {
        context.setContextPath("/myapp");
        Session session = new Session(context, TEST_SESSION_ID);

        assertEquals("/myapp", session.getServletContext().getContextPath());
    }

    @Test
    public void testSetMaxInactiveInterval() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setMaxInactiveInterval(3600);

        assertEquals(3600, session.getMaxInactiveInterval());
    }

    // ===== Attribute Tests =====

    @Test
    public void testSetAndGetAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);

        session.setAttribute("name", "value");

        assertEquals("value", session.getAttribute("name"));
    }

    @Test
    public void testGetNonExistentAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);

        assertNull(session.getAttribute("nonexistent"));
    }

    @Test
    public void testRemoveAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("name", "value");

        session.removeAttribute("name");

        assertNull(session.getAttribute("name"));
    }

    @Test
    public void testReplaceAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("name", "value1");

        session.setAttribute("name", "value2");

        assertEquals("value2", session.getAttribute("name"));
    }

    @Test
    public void testMultipleAttributes() {
        Session session = new Session(context, TEST_SESSION_ID);

        session.setAttribute("attr1", "value1");
        session.setAttribute("attr2", 42);
        session.setAttribute("attr3", true);

        assertEquals("value1", session.getAttribute("attr1"));
        assertEquals(42, session.getAttribute("attr2"));
        assertEquals(true, session.getAttribute("attr3"));
    }

    @Test
    public void testGetAttributeNames() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("a", 1);
        session.setAttribute("b", 2);
        session.setAttribute("c", 3);

        String[] names = session.getValueNames();

        assertEquals(3, names.length);
    }

    // ===== Dirty Tracking Tests =====

    @Test
    public void testNewSessionIsDirty() {
        Session session = new Session(context, TEST_SESSION_ID);

        assertTrue("New session should be dirty", session.isDirty());
    }

    @Test
    public void testNewSessionNeedsFullReplication() {
        Session session = new Session(context, TEST_SESSION_ID);

        assertTrue("New session needs full replication", session.needsFullReplication());
    }

    @Test
    public void testSessionNotDirtyAfterClearDirtyState() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.clearDirtyState();

        assertFalse("Session should not be dirty after clear", session.isDirty());
        assertFalse("Session should not be new after clear", session.isNew);
    }

    @Test
    public void testSetAttributeMarksDirty() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.clearDirtyState();

        session.setAttribute("key", "value");

        assertTrue("Session should be dirty after setAttribute", session.isDirty());
    }

    @Test
    public void testRemoveAttributeMarksDirty() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("key", "value");
        session.clearDirtyState();

        session.removeAttribute("key");

        assertTrue("Session should be dirty after removeAttribute", session.isDirty());
    }

    @Test
    public void testDirtyAttributesTracking() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.clearDirtyState();

        session.setAttribute("attr1", "value1");
        session.setAttribute("attr2", "value2");

        Set<String> dirty = session.getDirtyAttributes();
        assertEquals(2, dirty.size());
        assertTrue(dirty.contains("attr1"));
        assertTrue(dirty.contains("attr2"));
    }

    @Test
    public void testRemovedAttributesTracking() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr1", "value1");
        session.setAttribute("attr2", "value2");
        session.clearDirtyState();

        session.removeAttribute("attr1");

        Set<String> removed = session.getRemovedAttributes();
        assertEquals(1, removed.size());
        assertTrue(removed.contains("attr1"));
    }

    @Test
    public void testSetAfterRemoveClearsFromRemoved() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr", "value1");
        session.clearDirtyState();

        session.removeAttribute("attr");
        session.setAttribute("attr", "value2");

        Set<String> removed = session.getRemovedAttributes();
        assertFalse("Attribute should not be in removed set", removed.contains("attr"));

        Set<String> dirty = session.getDirtyAttributes();
        assertTrue("Attribute should be in dirty set", dirty.contains("attr"));
    }

    @Test
    public void testClearDirtyStateClearsBothSets() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr1", "value1");
        session.setAttribute("attr2", "value2");
        session.removeAttribute("attr2");

        session.clearDirtyState();

        assertTrue(session.getDirtyAttributes().isEmpty());
        assertTrue(session.getRemovedAttributes().isEmpty());
    }

    // ===== Full vs Delta Replication Logic =====

    @Test
    public void testNeedsFullReplicationWhenNew() {
        Session session = new Session(context, TEST_SESSION_ID);

        assertTrue("New session needs full replication", session.needsFullReplication());
    }

    @Test
    public void testNeedsFullReplicationWhenManyChanges() {
        Session session = new Session(context, TEST_SESSION_ID);
        // Add 10 attributes
        for (int i = 0; i < 10; i++) {
            session.setAttribute("attr" + i, "value" + i);
        }
        session.clearDirtyState();

        // Modify more than half
        for (int i = 0; i < 6; i++) {
            session.setAttribute("attr" + i, "newvalue" + i);
        }

        assertTrue("Should need full replication when >50% changed",
                   session.needsFullReplication());
    }

    @Test
    public void testNeedsDeltaReplicationWhenFewChanges() {
        Session session = new Session(context, TEST_SESSION_ID);
        // Add 10 attributes
        for (int i = 0; i < 10; i++) {
            session.setAttribute("attr" + i, "value" + i);
        }
        session.clearDirtyState();

        // Modify only 2 (less than half of 10)
        session.setAttribute("attr0", "newvalue0");
        session.setAttribute("attr1", "newvalue1");

        assertFalse("Should use delta replication when <50% changed",
                    session.needsFullReplication());
    }

    @Test
    public void testNeedsDeltaReplicationForSingleChange() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr1", "value1");
        session.setAttribute("attr2", "value2");
        session.setAttribute("attr3", "value3");
        session.clearDirtyState();

        session.setAttribute("attr1", "newvalue");

        assertFalse("Single change should use delta", session.needsFullReplication());
    }

    // ===== Version Management =====

    @Test
    public void testVersionIncrementsOnSetAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);
        long initialVersion = session.version;

        session.setAttribute("key", "value");

        assertTrue("Version should increment", session.version > initialVersion);
    }

    @Test
    public void testVersionIncrementsOnRemoveAttribute() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("key", "value");
        long versionAfterSet = session.version;

        session.removeAttribute("key");

        assertTrue("Version should increment", session.version > versionAfterSet);
    }

    @Test
    public void testVersionIsUnique() {
        Session session1 = new Session(context, TEST_SESSION_ID);
        Session session2 = new Session(context, "abcdef0123456789abcdef0123456789");

        assertNotEquals("Different sessions should have different versions",
                       session1.version, session2.version);
    }

    // ===== Delta Application Tests =====

    @Test
    public void testApplyDeltaWithHigherVersion() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.clearDirtyState();
        long oldVersion = session.version;

        Map<String, Object> updates = new HashMap<>();
        updates.put("newAttr", "newValue");
        Set<String> removed = new HashSet<>();

        boolean applied = session.applyDelta(oldVersion + 100, updates, removed);

        assertTrue("Delta should be applied", applied);
        assertEquals("newValue", session.getAttribute("newAttr"));
        assertEquals(oldVersion + 100, session.version);
    }

    @Test
    public void testApplyDeltaWithLowerVersionRejected() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr", "original");
        long currentVersion = session.version;

        Map<String, Object> updates = new HashMap<>();
        updates.put("attr", "staleValue");
        Set<String> removed = new HashSet<>();

        boolean applied = session.applyDelta(currentVersion - 1, updates, removed);

        assertFalse("Delta with lower version should be rejected", applied);
        assertEquals("original", session.getAttribute("attr"));
    }

    @Test
    public void testApplyDeltaWithEqualVersionRejected() {
        Session session = new Session(context, TEST_SESSION_ID);
        long currentVersion = session.version;

        Map<String, Object> updates = new HashMap<>();
        updates.put("attr", "value");
        Set<String> removed = new HashSet<>();

        boolean applied = session.applyDelta(currentVersion, updates, removed);

        assertFalse("Delta with equal version should be rejected", applied);
    }

    @Test
    public void testApplyDeltaRemovesAttributes() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("toRemove", "value");
        session.clearDirtyState();
        long oldVersion = session.version;

        Map<String, Object> updates = new HashMap<>();
        Set<String> removed = new HashSet<>();
        removed.add("toRemove");

        session.applyDelta(oldVersion + 1, updates, removed);

        assertNull("Attribute should be removed", session.getAttribute("toRemove"));
    }

    @Test
    public void testApplyDeltaUpdatesAndRemoves() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("keep", "old");
        session.setAttribute("remove", "value");
        session.clearDirtyState();
        long oldVersion = session.version;

        Map<String, Object> updates = new HashMap<>();
        updates.put("keep", "new");
        updates.put("add", "added");
        Set<String> removed = new HashSet<>();
        removed.add("remove");

        session.applyDelta(oldVersion + 1, updates, removed);

        assertEquals("new", session.getAttribute("keep"));
        assertEquals("added", session.getAttribute("add"));
        assertNull(session.getAttribute("remove"));
    }

    // ===== Full Update Tests =====

    @Test
    public void testUpdateFromHigherVersion() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("local", "value");
        long oldVersion = session.version;

        Session received = new Session(context, TEST_SESSION_ID);
        received.version = oldVersion + 100;
        received.setAttribute("remote", "remoteValue");

        session.updateFrom(received);

        assertEquals("remoteValue", session.getAttribute("remote"));
        assertNull("Local attribute should be replaced", session.getAttribute("local"));
        assertEquals(received.version, session.version);
    }

    @Test
    public void testUpdateFromLowerVersionIgnored() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("local", "value");
        // Force version to be high enough
        session.version = 1000;
        long originalVersion = session.version;

        Session received = new Session(context, TEST_SESSION_ID);
        // Set received version lower than current session
        received.version = originalVersion - 1;
        received.setAttribute("remote", "remoteValue");

        session.updateFrom(received);

        assertEquals("value", session.getAttribute("local"));
        assertNull("Remote attribute should not be applied", session.getAttribute("remote"));
        assertEquals(originalVersion, session.version);
    }

    @Test
    public void testUpdateFromClearsDirtyState() {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("local", "value");
        long oldVersion = session.version;

        Session received = new Session(context, TEST_SESSION_ID);
        received.version = oldVersion + 1;

        session.updateFrom(received);

        assertFalse("Dirty state should be cleared", session.isDirty());
    }

    @Test
    public void testUpdateFromCopiesTimestamps() {
        Session session = new Session(context, TEST_SESSION_ID);
        long oldVersion = session.version;

        Session received = new Session(context, TEST_SESSION_ID);
        received.version = oldVersion + 1;
        received.creationTime = 1000L;
        received.lastAccessedTime = 2000L;
        received.maxInactiveInterval = 7200;

        session.updateFrom(received);

        assertEquals(1000L, session.getCreationTime());
        assertEquals(2000L, session.getLastAccessedTime());
        assertEquals(7200, session.getMaxInactiveInterval());
    }

}

