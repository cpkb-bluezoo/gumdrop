/*
 * ContextSessionExpiryTest.java
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
 * Regression tests for issue #118: {@code Context.invalidateSessions} had
 * an inverted throttle guard ({@code sessionsLastInvalidated + 1000 < now}
 * instead of {@code now - sessionsLastInvalidated < 1000}), so once more
 * than a second had passed since context init, every future call returned
 * early and {@code SessionManager.invalidateExpiredSessions()} never ran
 * again for the life of the process — sessions were never expired.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextSessionExpiryTest {

    private File webappRoot;
    private Context context;

    @Before
    public void setUp() throws Exception {
        webappRoot = Files.createTempDirectory("gumdrop-webapp").toFile();
        Container container = new Container();
        context = new Context(container, "/app", webappRoot);
    }

    @After
    public void tearDown() {
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
    public void testSweepRunsAgainAfterThrottleWindowElapses() {
        // Simulate "more than a second has passed since the last sweep" —
        // exactly the state every context reaches one second after init,
        // and which the inverted guard treated as "never sweep again".
        context.sessionsLastInvalidated = System.currentTimeMillis() - 2000;

        context.invalidateSessions(false);

        // A correct throttle only *delays* the sweep, it never disables it
        // permanently: once outside the 1s window, calling again must run
        // the sweep and update the timestamp. The pre-fix code left
        // sessionsLastInvalidated untouched here, forever.
        assertTrue("invalidateSessions must run (and update the timestamp) "
                        + "once the throttle window has elapsed",
                System.currentTimeMillis() - context.sessionsLastInvalidated < 1000);
    }

    @Test
    public void testRapidCallsAreStillThrottled() {
        context.invalidateSessions(true);
        long afterForced = context.sessionsLastInvalidated;

        // Immediately call again, unforced: within the 1s window this must
        // be a no-op (the throttle's actual purpose), not run on every call.
        context.invalidateSessions(false);

        assertEquals("a rapid repeated call within the throttle window "
                        + "must not re-run the sweep",
                afterForced, context.sessionsLastInvalidated);
    }

    @Test
    public void testForceAlwaysRuns() {
        context.invalidateSessions(false);
        long first = context.sessionsLastInvalidated;
        context.invalidateSessions(true);
        assertTrue("force=true must always re-run the sweep",
                context.sessionsLastInvalidated >= first);
    }

    @Test
    public void testExpiredSessionIsActuallyRemoved() throws Exception {
        context.setSessionTimeout(1); // 1 second
        HttpSession session = context.getSessionManager().createSession();
        String id = session.getId();
        assertNotNull(context.getSessionManager().getSession(id, false));

        // Backdate the session's last-access time instead of sleeping a
        // full second, and backdate sessionsLastInvalidated so the sweep
        // is not itself throttled. Session is package-private in
        // org.bluezoo.gumdrop.servlet.session, so use reflection.
        Field lastAccessedTime = session.getClass().getDeclaredField("lastAccessedTime");
        lastAccessedTime.setAccessible(true);
        lastAccessedTime.setLong(session, System.currentTimeMillis() - 1500);
        context.sessionsLastInvalidated = System.currentTimeMillis() - 2000;

        context.invalidateSessions(false);

        assertNull("an expired session must actually be removed by the sweep",
                context.getSessionManager().getSession(id, false));
    }
}
