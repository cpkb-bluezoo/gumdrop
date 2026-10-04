/*
 * AsyncTimeoutSchedulerTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs the async timeout scheduler loop on the calling thread: every entry
 * is already due when the loop starts, and the last callback shuts the
 * scheduler down, so the loop is driven without clocks or waiting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AsyncTimeoutSchedulerTest {

    private static final class Recording implements AsyncTimeoutCallback {
        final List<String> fired;
        final String name;

        Recording(List<String> fired, String name) {
            this.fired = fired;
            this.name = name;
        }

        @Override
        public void onTimeout() {
            fired.add(name);
        }
    }

    @Test
    public void testDueEntriesFireInOrderAndShutdownEndsTheLoop() {
        final AsyncTimeoutScheduler scheduler = new AsyncTimeoutScheduler();
        final List<String> fired = new ArrayList<String>();
        scheduler.schedule(-30000L, new Recording(fired, "first"));
        scheduler.schedule(-20000L, new AsyncTimeoutCallback() {
            @Override
            public void onTimeout() {
                fired.add("failing");
                throw new IllegalStateException("callback failure is contained");
            }
        });
        AsyncTimeoutHandle cancelled = scheduler.schedule(-10000L, new Recording(fired, "cancelled"));
        assertFalse(cancelled.isCancelled());
        cancelled.cancel();
        assertTrue(cancelled.isCancelled());
        scheduler.schedule(-5000L, new AsyncTimeoutCallback() {
            @Override
            public void onTimeout() {
                fired.add("last");
                scheduler.shutdown();
            }
        });
        scheduler.run();
        assertEquals(3, fired.size());
        assertEquals("first", fired.get(0));
        assertEquals("failing", fired.get(1));
        assertEquals("last", fired.get(2));
    }

    /**
     * The fire times are given outright: taking each from the clock, as
     * {@code schedule} does, makes them equal only when the clock happens
     * not to tick between one call and the next.
     */
    @Test
    public void testEqualFireTimesFireInSchedulingOrder() {
        final AsyncTimeoutScheduler scheduler = new AsyncTimeoutScheduler();
        final List<String> fired = new ArrayList<String>();
        long fireTime = System.currentTimeMillis() - 60000L;
        scheduler.scheduleAt(fireTime, new Recording(fired, "a"));
        scheduler.scheduleAt(fireTime, new Recording(fired, "b"));
        scheduler.scheduleAt(fireTime - 1L, new Recording(fired, "earlier"));
        scheduler.scheduleAt(fireTime, new AsyncTimeoutCallback() {
            @Override
            public void onTimeout() {
                scheduler.shutdown();
            }
        });
        scheduler.run();
        assertEquals(3, fired.size());
        assertEquals("earlier", fired.get(0));
        assertEquals("a", fired.get(1));
        assertEquals("b", fired.get(2));
    }
}
