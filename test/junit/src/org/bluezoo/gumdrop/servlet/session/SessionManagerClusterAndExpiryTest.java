/*
 * SessionManagerClusterAndExpiryTest.java
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

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SessionManager} cluster-facing hooks, activation
 * listeners and expiry, without a live {@link Cluster}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionManagerClusterAndExpiryTest {

    private static final class RecordingActivationListener implements HttpSessionActivationListener {
        final List<String> events = new ArrayList<String>();

        @Override
        public void sessionWillPassivate(HttpSessionEvent event) {
            events.add("passivate:" + event.getSession().getId());
        }

        @Override
        public void sessionDidActivate(HttpSessionEvent event) {
            events.add("activate:" + event.getSession().getId());
        }
    }

    private MockSessionContext context;
    private SessionManager manager;
    private RecordingActivationListener listener;

    @Before
    public void setUp() {
        context = new MockSessionContext();
        context.setSessionTimeout(60);
        listener = new RecordingActivationListener();
        context.addSessionActivationListener(listener);
        manager = new SessionManager(context);
    }

    @Test
    public void getAllSessionsReflectsCreatedSessions() {
        assertTrue(manager.getAllSessions().isEmpty());
        manager.createSession();
        manager.createSession();
        assertEquals(2, manager.getAllSessions().size());
    }

    @Test
    public void removeSessionFiresPassivationListener() {
        Session session = (Session) manager.createSession();
        manager.removeSession(session.getId());
        assertEquals(1, listener.events.size());
        assertEquals("passivate:" + session.getId(), listener.events.get(0));
        manager.removeSession(session.getId());
        assertEquals(1, listener.events.size());
    }

    @Test
    public void passivateClusterSessionRemovesWithoutClusterNotify() {
        Session session = (Session) manager.createSession();
        manager.passivateClusterSession(session.getId());
        assertNull(manager.getSession(session.getId()));
        assertEquals(1, listener.events.size());
    }

    @Test
    public void addClusterSessionInsertsNewSessionAndFiresActivation() {
        Session remote = new Session(context, "0123456789abcdef0123456789abcdef");
        manager.addClusterSession(remote);
        assertSame(remote, manager.getSession(remote.getId()));
        assertEquals("activate:" + remote.getId(), listener.events.get(0));
    }

    @Test
    public void addClusterSessionMergesIntoExisting() {
        Session local = (Session) manager.createSession();
        String id = local.getId();
        Session remote = new Session(context, id);
        remote.lastAccessedTime = local.lastAccessedTime + 5000L;
        manager.addClusterSession(remote);
        assertSame(local, manager.getSession(id));
        assertEquals(remote.lastAccessedTime, local.lastAccessedTime);
    }

    @Test
    public void replicateSessionWithoutClusterIsNoOp() {
        Session session = (Session) manager.createSession();
        manager.replicateSession(session);
        manager.replicateSession(null);
        assertFalse(manager.isClusteringEnabled());
    }

    @Test
    public void expiredSessionsAreInvalidatedAndRemoved() {
        Session stale = (Session) manager.createSession();
        Session fresh = (Session) manager.createSession();
        stale.lastAccessedTime = System.currentTimeMillis() - 120000L;
        manager.invalidateExpiredSessions();
        assertNull(manager.getSession(stale.getId()));
        assertNotNull(manager.getSession(fresh.getId()));
    }

    @Test
    public void nonPositiveTimeoutMeansNeverExpire() {
        context.setSessionTimeout(0);
        Session session = (Session) manager.createSession();
        session.lastAccessedTime = 0L;
        manager.invalidateExpiredSessions();
        assertNotNull(manager.getSession(session.getId()));
    }
}
