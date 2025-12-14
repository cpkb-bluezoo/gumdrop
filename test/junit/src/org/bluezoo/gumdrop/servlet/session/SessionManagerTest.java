/*
 * SessionManagerTest.java
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

import java.util.UUID;

import javax.servlet.http.HttpSession;

import static org.junit.Assert.*;

/**
 * Unit tests for the SessionManager class.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Session creation and retrieval</li>
 *   <li>Context UUID management</li>
 *   <li>Session lifecycle (removal)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionManagerTest {

    private MockSessionContext context;
    private SessionManager manager;

    @Before
    public void setUp() {
        context = new MockSessionContext();
        context.setSessionTimeout(1800);
        manager = new SessionManager(context);
    }

    // ===== Context UUID Tests =====

    @Test
    public void testContextUuidNotNull() {
        assertNotNull("Context UUID should not be null", manager.getContextUuid());
    }

    @Test
    public void testContextUuidIsRandom() {
        SessionManager manager2 = new SessionManager(context);

        assertNotEquals("Different managers should have different UUIDs",
                       manager.getContextUuid(), manager2.getContextUuid());
    }

    @Test
    public void testRegenerateContextUuid() {
        UUID original = manager.getContextUuid();

        manager.regenerateContextUuid();

        assertNotEquals("UUID should change after regeneration",
                       original, manager.getContextUuid());
    }

    @Test
    public void testContextReference() {
        assertEquals(context, manager.getContext());
    }

    // ===== Clustering Configuration Tests =====

    @Test
    public void testClusteringDisabledByDefault() {
        assertFalse("Clustering should be disabled by default",
                   manager.isClusteringEnabled());
    }

    // ===== Session Creation Tests =====

    @Test
    public void testCreateSession() {
        HttpSession session = manager.createSession();

        assertNotNull("Should create session", session);
        assertNotNull(session.getId());
        assertEquals("Session ID should be 32 hex chars", 32, session.getId().length());
        assertTrue("Session ID should be hex", session.getId().matches("[0-9a-f]+"));
    }

    @Test
    public void testCreateSessionGeneratesUniqueIds() {
        HttpSession session1 = manager.createSession();
        HttpSession session2 = manager.createSession();
        HttpSession session3 = manager.createSession();

        assertNotEquals("Sessions should have unique IDs",
                       session1.getId(), session2.getId());
        assertNotEquals("Sessions should have unique IDs",
                       session2.getId(), session3.getId());
    }

    // ===== Session Retrieval Tests =====

    @Test
    public void testGetSessionCreatesNew() {
        HttpSession session = manager.getSession("nonexistent", true);

        assertNotNull("Should create new session", session);
        assertNotNull(session.getId());
        assertEquals(32, session.getId().length());
    }

    @Test
    public void testGetSessionReturnsNullWhenCreateFalse() {
        HttpSession session = manager.getSession("nonexistent", false);

        assertNull("Should return null when create=false", session);
    }

    @Test
    public void testGetSessionRetrievesExisting() {
        HttpSession created = manager.createSession();
        String sessionId = created.getId();

        HttpSession retrieved = manager.getSession(sessionId, false);

        assertSame("Should return same session", created, retrieved);
    }

    @Test
    public void testGetSessionWithNullIdCreatesNew() {
        HttpSession session = manager.getSession(null, true);

        assertNotNull("Should create new session", session);
    }

    @Test
    public void testGetSessionSimpleRetrieval() {
        HttpSession created = manager.createSession();
        String sessionId = created.getId();

        HttpSession retrieved = manager.getSession(sessionId);

        assertSame("Should return same session", created, retrieved);
    }

    @Test
    public void testGetSessionSimpleReturnsNullForNonExistent() {
        HttpSession retrieved = manager.getSession("nonexistent");

        assertNull("Should return null for non-existent", retrieved);
    }

    @Test
    public void testGetSessionUpdatesLastAccessedTime() throws InterruptedException {
        HttpSession session = manager.createSession();
        long originalTime = session.getLastAccessedTime();

        // Wait a bit
        Thread.sleep(50);

        // Access the session again
        manager.getSession(session.getId(), false);

        assertTrue("Last accessed time should be updated",
                   session.getLastAccessedTime() > originalTime);
    }

    // ===== Session Removal Tests =====

    @Test
    public void testRemoveSession() {
        HttpSession session = manager.createSession();
        String sessionId = session.getId();

        manager.removeSession(sessionId);

        assertNull("Session should be removed",
                   manager.getSession(sessionId));
    }

    @Test
    public void testRemoveSessionWithNotifyCluster() {
        HttpSession session = manager.createSession();
        String sessionId = session.getId();

        manager.removeSession(sessionId, false);

        assertNull("Session should be removed",
                   manager.getSession(sessionId));
    }

    @Test
    public void testRemoveNonExistentSession() {
        // Should not throw
        manager.removeSession("nonexistent");
    }

    // ===== Multiple Sessions Tests =====

    @Test
    public void testMultipleSessions() {
        HttpSession session1 = manager.createSession();
        HttpSession session2 = manager.createSession();
        HttpSession session3 = manager.createSession();

        assertNotNull(manager.getSession(session1.getId()));
        assertNotNull(manager.getSession(session2.getId()));
        assertNotNull(manager.getSession(session3.getId()));

        manager.removeSession(session2.getId());

        assertNotNull(manager.getSession(session1.getId()));
        assertNull(manager.getSession(session2.getId()));
        assertNotNull(manager.getSession(session3.getId()));
    }

    // ===== Session Attribute Preservation =====

    @Test
    public void testSessionAttributesPreserved() {
        HttpSession session = manager.createSession();
        session.setAttribute("key", "value");

        HttpSession retrieved = manager.getSession(session.getId());

        assertEquals("value", retrieved.getAttribute("key"));
    }

}
