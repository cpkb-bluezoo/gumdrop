/*
 * ContextSessionSweepOffRequestPathTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

import javax.servlet.http.HttpSession;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #311: {@link Context#getRequestDispatcher}
 * used to call {@link Context#invalidateSessions} inside {@code
 * synchronized(this)}, so when the 1-second throttle admitted a sweep every
 * request thread paid O(session-count) cost and blocked all other requests
 * needing the context lock. Session expiry is now driven by a dedicated
 * timer, not inline on the request path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextSessionSweepOffRequestPathTest {

    private static final int SESSION_COUNT = 5000;

    private File webappRoot;
    private Context context;

    @Before
    public void setUp() throws Exception {
        webappRoot = Files.createTempDirectory("gumdrop-session-sweep-path").toFile();
        Container container = new Container();
        context = new Context(container, "/app", webappRoot);
        context.setSessionTimeout(1);
    }

    @After
    public void tearDown() {
        context.destroy();
        deleteRecursively(webappRoot);
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }

    @Test
    public void testGetRequestDispatcherDoesNotSweepExpiredSessions() throws Exception {
        HttpSession session = context.getSessionManager().createSession();
        String id = session.getId();

        Field lastAccessedTime = session.getClass().getDeclaredField("lastAccessedTime");
        lastAccessedTime.setAccessible(true);
        lastAccessedTime.setLong(session, System.currentTimeMillis() - 1500);
        context.sessionsLastInvalidated = System.currentTimeMillis() - 2000;

        context.getRequestDispatcher("/");

        assertNotNull("getRequestDispatcher must not run session expiry inline",
                context.getSessionManager().getSession(id));

        context.invalidateSessions(true);
        assertNull("explicit sweep must still remove expired sessions",
                context.getSessionManager().getSession(id));
    }

    @Test(timeout = 3000)
    public void testGetRequestDispatcherDoesNotPayInlineSweepCost() throws Exception {
        Field lastAccessedTime = null;
        for (int i = 0; i < SESSION_COUNT; i++) {
            HttpSession session = context.getSessionManager().createSession();
            if (lastAccessedTime == null) {
                lastAccessedTime = session.getClass().getDeclaredField("lastAccessedTime");
                lastAccessedTime.setAccessible(true);
            }
            lastAccessedTime.setLong(session, System.currentTimeMillis() - 1500);
        }
        context.sessionsLastInvalidated = System.currentTimeMillis() - 2000;

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertNotNull(context.getRequestDispatcher("/"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue("getRequestDispatcher must not run an inline session sweep "
                        + "(200 lookups with " + SESSION_COUNT + " expired sessions took "
                        + elapsedMs + "ms)",
                elapsedMs < 500);
    }
}
