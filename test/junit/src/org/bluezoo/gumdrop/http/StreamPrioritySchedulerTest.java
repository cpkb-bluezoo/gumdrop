/*
 * StreamPrioritySchedulerTest.java
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

package org.bluezoo.gumdrop.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic tests of {@link StreamPriorityScheduler}: selection by
 * priority, burst control, starvation prevention (driven by a manual
 * clock), bandwidth accounting and the proportional allocation helper.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("deprecation")
public class StreamPrioritySchedulerTest {

    private static final class ManualScheduler extends StreamPriorityScheduler {
        long now = 1000L;

        ManualScheduler(StreamPriorityTree tree) {
            super(tree);
        }

        @Override
        long currentTimeMillis() {
            return now;
        }
    }

    private StreamPriorityTree tree;
    private ManualScheduler scheduler;

    @Before
    public void setUp() {
        tree = new StreamPriorityTree();
        tree.updateStreamPriority(1, 200, 0, false);
        tree.updateStreamPriority(3, 10, 0, false);
        tree.updateStreamPriority(5, 50, 0, false);
        scheduler = new ManualScheduler(tree);
    }

    private static List<Integer> ids(Integer... values) {
        return new ArrayList<Integer>(Arrays.asList(values));
    }

    @Test
    public void emptyReadySetYieldsMinusOne() {
        assertEquals(-1, scheduler.scheduleNextStream(Collections.<Integer>emptyList()));
    }

    @Test
    public void unknownStreamsFallBackToFirstAvailable() {
        assertEquals(77, scheduler.scheduleNextStream(ids(77, 79)));
    }

    @Test
    public void highestPriorityStreamWins() {
        assertEquals(1, scheduler.scheduleNextStream(ids(3, 1, 5)));
    }

    @Test
    public void burstControlYieldsToLowerPriorityThenReturns() {
        assertEquals(1, scheduler.scheduleNextStream(ids(1, 3, 5)));
        for (int i = 0; i < 10; i++) {
            scheduler.recordStreamProcessing(1, 100L, 10L);
        }
        // stream 1 has run a full burst; the next best candidate has no allocation yet
        int next = scheduler.scheduleNextStream(ids(1, 3, 5));
        assertEquals(5, next);
        for (int i = 0; i < 5; i++) {
            scheduler.recordStreamProcessing(5, 10L, 10L);
        }
        // stream 5 has used half a burst; stream 3 is still a candidate
        assertEquals(3, scheduler.scheduleNextStream(ids(1, 3, 5)));
        for (int i = 0; i < 5; i++) {
            scheduler.recordStreamProcessing(3, 10L, 10L);
        }
        // every candidate is busy: the highest priority stream runs again
        assertEquals(1, scheduler.scheduleNextStream(ids(1, 3, 5)));
    }

    @Test
    public void starvedStreamIsScheduledAfterTimeSlice() {
        assertEquals(1, scheduler.scheduleNextStream(ids(1, 3)));
        assertEquals(3, scheduler.scheduleNextStream(ids(3)));
        scheduler.now += 50L;
        assertEquals(1, scheduler.scheduleNextStream(ids(1, 3)));
        scheduler.now += 500L;
        // both have waited past the slice; the lowest priority is scanned first
        assertEquals(3, scheduler.scheduleNextStream(ids(1, 3)));
    }

    @Test
    public void bandwidthAccountingAndStats() {
        scheduler.recordStreamProcessing(1, 10L, 10L);
        assertTrue(scheduler.getSchedulingStats().contains("Active streams: 0"));
        scheduler.scheduleNextStream(ids(1));
        scheduler.recordStreamProcessing(1, 1000L, 0L);
        scheduler.recordStreamProcessing(1, 1000L, 10L);
        scheduler.scheduleNextStream(ids(77));
        String stats = scheduler.getSchedulingStats();
        assertTrue(stats, stats.contains("Active streams: 1"));
        assertTrue(stats, stats.contains("Stream 1"));
        assertTrue(stats, stats.contains("bytes=2000"));
        scheduler.removeStream(1);
        assertTrue(scheduler.getSchedulingStats().contains("Active streams: 0"));
    }

    @Test
    public void statsForStreamAbsentFromTreeUseZeroPriority() {
        scheduler.scheduleNextStream(ids(1));
        tree.removeStream(1);
        scheduler.recordStreamProcessing(1, 5L, 5L);
        assertTrue(scheduler.getSchedulingStats().contains("priority=0.000"));
    }

    @Test
    public void resourceAllocationsAreProportional() {
        assertTrue(scheduler.getResourceAllocations(Collections.<Integer>emptyList()).isEmpty());
        Map<Integer, Double> equal = scheduler.getResourceAllocations(ids(100, 102));
        assertEquals(0.5, equal.get(100).doubleValue(), 0.0001);
        assertEquals(0.5, equal.get(102).doubleValue(), 0.0001);
        Map<Integer, Double> prop = scheduler.getResourceAllocations(ids(1, 3, 100));
        double sum = prop.get(1).doubleValue() + prop.get(3).doubleValue() + prop.get(100).doubleValue();
        assertEquals(1.0, sum, 0.0001);
        assertEquals(0.0, prop.get(100).doubleValue(), 0.0001);
        assertTrue(prop.get(1).doubleValue() > prop.get(3).doubleValue());
    }
}
