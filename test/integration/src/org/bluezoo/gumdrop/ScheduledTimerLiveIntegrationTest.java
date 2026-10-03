/*
 * ScheduledTimerLiveIntegrationTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.channels.SelectionKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * {@link ScheduledTimer} with its own thread running: due callbacks run on
 * the timer thread, a callback that throws does not stop later timers, a
 * cancelled timer never fires, a burst of cancellations is swept while the
 * thread is parked, a timer for a handler without a loop is dropped, and
 * shutdown ends the thread. Every wait is a latch; ordering comes from the
 * fire times, not from sleeping.
 *
 * <p>Integration test: needs the timer's live thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ScheduledTimerLiveIntegrationTest {

    private static final long HANG_GUARD_MS = 60000L;

    @Rule
    public Timeout hangGuard = Timeout.seconds(90);

    private ScheduledTimer timer;

    @After
    public void tearDown() throws Exception {
        if (timer != null) {
            timer.shutdown();
            timer.join();
        }
    }

    /** Handler without a selector loop: a due timer for it has nowhere to be dispatched. */
    private static final class LooplessHandler implements ChannelHandler {
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

    private static Runnable countDown(final CountDownLatch latch) {
        return new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        };
    }

    private void startTimer() {
        timer = new ScheduledTimer("live-timer-test");
        timer.start();
        assertTrue(timer.isRunning());
    }

    @Test
    public void startingARunningTimerIsANoOp() {
        startTimer();
        timer.start();
        assertTrue(timer.isRunning());
    }

    @Test
    public void dueCallbackRunsOnTheTimerThread() throws Exception {
        startTimer();
        final CountDownLatch fired = new CountDownLatch(1);
        final Thread[] ran = new Thread[1];
        timer.schedule(null, 1L, new Runnable() {
            @Override
            public void run() {
                ran[0] = Thread.currentThread();
                fired.countDown();
            }
        });
        assertTrue(fired.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertEquals("live-timer-test", ran[0].getName());
    }

    @Test
    public void throwingCallbackDoesNotStopLaterTimers() throws Exception {
        startTimer();
        final CountDownLatch thrown = new CountDownLatch(1);
        final CountDownLatch later = new CountDownLatch(1);
        timer.schedule(null, 1L, new Runnable() {
            @Override
            public void run() {
                thrown.countDown();
                throw new IllegalStateException("callback failure");
            }
        });
        timer.schedule(null, 30L, countDown(later));
        assertTrue(thrown.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertTrue(later.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertTrue(timer.isRunning());
    }

    @Test
    public void cancelledTimerNeverFiresButALaterOneDoes() throws Exception {
        startTimer();
        final AtomicInteger cancelledRuns = new AtomicInteger();
        final CountDownLatch later = new CountDownLatch(1);
        TimerHandle early = timer.schedule(null, 20L, new Runnable() {
            @Override
            public void run() {
                cancelledRuns.incrementAndGet();
            }
        });
        timer.cancel(early);
        timer.cancel(null);
        assertTrue(early.isCancelled());
        timer.schedule(null, 60L, countDown(later));
        assertTrue(later.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertEquals(0, cancelledRuns.get());
    }

    @Test
    public void cancellationBurstIsSweptWhileTheThreadWaits() throws Exception {
        startTimer();
        final CountDownLatch survivor = new CountDownLatch(1);
        timer.schedule(null, 400L, countDown(survivor));
        for (int i = 0; i < 2000; i++) {
            TimerHandle h = timer.schedule(null, 600000L, new Runnable() {
                @Override
                public void run() {
                }
            });
            h.cancel();
        }
        assertTrue(survivor.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertTrue("tombstones were reclaimed", timer.pendingCount() < 2001);
    }

    @Test
    public void dueTimerForAHandlerWithoutALoopIsDropped() throws Exception {
        startTimer();
        final CountDownLatch dropped = new CountDownLatch(1);
        final CountDownLatch after = new CountDownLatch(1);
        timer.schedule(new LooplessHandler(), 1L, countDown(dropped));
        timer.schedule(null, 40L, countDown(after));
        assertTrue(after.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS));
        assertEquals("a loopless handler's timer is not run", 1L, dropped.getCount());
    }

    @Test
    public void shutdownEndsTheThread() throws Exception {
        startTimer();
        timer.shutdown();
        timer.join();
        assertFalse(timer.isRunning());
        timer.join();
    }

    @Test
    public void joinWithoutAStartedThreadReturnsAtOnce() throws Exception {
        ScheduledTimer unstarted = new ScheduledTimer("never-started");
        unstarted.join();
        assertFalse(unstarted.isRunning());
        timer = null;
    }
}
