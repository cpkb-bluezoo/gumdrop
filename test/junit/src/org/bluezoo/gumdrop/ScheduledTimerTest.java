/*
 * ScheduledTimerTest.java
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

package org.bluezoo.gumdrop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for {@link ScheduledTimer}'s reclamation of cancelled
 * timers (performance review finding F6). Cancelling a timer used to only flip
 * a flag and leave the entry in the priority queue until its original fire
 * time, so a cancel-and-reschedule workload grew the queue without bound.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ScheduledTimerTest {

    private ScheduledTimer timer;

    /** Minimal handler; dispatch is a no-op because it has no SelectorLoop. */
    private static final class NoopHandler implements ChannelHandler {
        public Type getChannelType() {
            return Type.TCP;
        }

        public SelectionKey getSelectionKey() {
            return null;
        }

        public void setSelectionKey(SelectionKey key) {
        }

        public SelectorLoop getSelectorLoop() {
            return null;
        }

        public void setSelectorLoop(SelectorLoop loop) {
        }
    }

    @Before
    public void setUp() {
        timer = new ScheduledTimer();
        timer.start();
    }

    @After
    public void tearDown() throws InterruptedException {
        timer.shutdown();
        timer.join();
    }

    @Test(timeout = 15000)
    public void testCancelledTimersAreReclaimed() throws Exception {
        ChannelHandler handler = new NoopHandler();

        // A few long-lived timers that must survive.
        int live = 3;
        List<TimerHandle> keep = new ArrayList<TimerHandle>();
        for (int i = 0; i < live; i++) {
            keep.add(timer.schedule(handler, 60_000, NOOP));
        }

        // A large cancel-and-reschedule burst. Every entry has a far-future
        // fire time, so before the fix all of these would remain queued.
        int churn = 2000;
        for (int i = 0; i < churn; i++) {
            TimerHandle h = timer.schedule(handler, 60_000, NOOP);
            h.cancel();
        }

        // The queue must collapse back to roughly the live set (a bounded
        // number of not-yet-swept tombstones may remain), not grow with churn.
        long deadline = System.currentTimeMillis() + 10_000;
        int depth = timer.pendingCount();
        while (depth > 128 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            depth = timer.pendingCount();
        }

        assertTrue("queue should be reclaimed well below the churn count, was " + depth,
                depth <= 128);
        assertTrue("live timers must remain queued, was " + depth, depth >= live);
        for (TimerHandle h : keep) {
            assertFalse("live timers must not be cancelled", h.isCancelled());
        }
    }

    private static final Runnable NOOP = new Runnable() {
        public void run() {
        }
    };
}
