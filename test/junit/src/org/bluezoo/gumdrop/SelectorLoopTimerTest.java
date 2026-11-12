/*
 * DnsQueryContextTest.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Timers kept on a {@link SelectorLoop} itself: they run on the loop's
 * thread, not before their delay, cannot run once cancelled, repeat without
 * drifting, need no thread of their own, and give the loop no reason to
 * wake when there are none. The clock is a fake, advanced by the test, so
 * none of this depends on real time passing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SelectorLoopTimerTest {

    private static final long MS = 1000000L;

    private static final class TestLoop extends SelectorLoop {
        final AtomicLong now = new AtomicLong(1000000000L);

        TestLoop() {
            super(0);
        }

        @Override
        long timerNanos() {
            return now.get();
        }
    }

    private TestLoop loop;

    @Before
    public void start() {
        loop = new TestLoop();
        loop.start();
    }

    @After
    public void stop() {
        loop.shutdown();
    }

    /** Runs a task on the loop and returns what it returns. */
    private <T> T onLoop(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<T>(task);
        loop.invokeLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }

    /** Moves the clock on by {@code ms}, and has the loop notice, then waits for it to have done so. */
    private void advance(long ms) throws Exception {
        loop.now.addAndGet(ms * MS);
        loop.wakeup();
        // A task queued behind the wake-up runs before the timers that are due
        // are run, in the same turn of the loop; one queued after that
        // completes runs in the next turn, so by then they have been.
        for (int i = 0; i < 2; i++) {
            onLoop(new Callable<Object>() {
                @Override
                public Object call() {
                    return null;
                }
            });
        }
    }

    private static final class Counter implements Runnable {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<Thread>();
        final CountDownLatch first = new CountDownLatch(1);

        @Override
        public void run() {
            thread.set(Thread.currentThread());
            runs.incrementAndGet();
            first.countDown();
        }
    }

    @Test
    public void aTaskRunsOnTheLoopsOwnThread() throws Exception {
        Counter c = new Counter();
        loop.schedule(10, c);
        advance(10);
        assertTrue(c.first.await(5, TimeUnit.SECONDS));
        assertSame(loop.getThread(), c.thread.get());
    }

    @Test
    public void aTaskDoesNotRunBeforeItsDelay() throws Exception {
        Counter c = new Counter();
        loop.schedule(100, c);
        advance(0);
        assertEquals(0, c.runs.get());
        advance(99);
        assertEquals("one millisecond short", 0, c.runs.get());
        advance(1);
        assertEquals(1, c.runs.get());
        advance(1000);
        assertEquals("a one-shot runs once", 1, c.runs.get());
    }

    @Test
    public void aTaskDueAtOnceRuns() throws Exception {
        Counter c = new Counter();
        loop.schedule(0, c);
        advance(0);
        assertEquals(1, c.runs.get());
    }

    @Test
    public void timersRunInDeadlineOrderAndTiesInTheOrderScheduled() throws Exception {
        final List<String> order = new ArrayList<String>();
        loop.schedule(30, new Runnable() {
            @Override
            public void run() {
                order.add("c");
            }
        });
        loop.schedule(10, new Runnable() {
            @Override
            public void run() {
                order.add("a");
            }
        });
        loop.schedule(10, new Runnable() {
            @Override
            public void run() {
                order.add("b");
            }
        });
        advance(50);
        assertEquals("[a, b, c]", order.toString());
    }

    @Test
    public void cancellingBeforeItRunsPreventsIt() throws Exception {
        final Counter c = new Counter();
        final TimerHandle handle = loop.schedule(10, c);
        onLoop(new Callable<Object>() {
            @Override
            public Object call() {
                handle.cancel();
                return null;
            }
        });
        advance(100);
        assertEquals(0, c.runs.get());
        assertTrue(handle.isCancelled());
    }

    @Test
    public void cancellingTwiceIsHarmless() throws Exception {
        final TimerHandle handle = loop.schedule(10, new Counter());
        onLoop(new Callable<Object>() {
            @Override
            public Object call() {
                handle.cancel();
                handle.cancel();
                return null;
            }
        });
        assertTrue(handle.isCancelled());
    }

    @Test
    public void aTimerCancelledByAnEarlierTaskInTheSameBatchDoesNotRun() throws Exception {
        final Counter victim = new Counter();
        final AtomicReference<TimerHandle> victimHandle = new AtomicReference<TimerHandle>();
        loop.schedule(10, new Runnable() {
            @Override
            public void run() {
                victimHandle.get().cancel();
            }
        });
        victimHandle.set(loop.schedule(10, victim));
        advance(10);
        assertEquals("cancel on the loop is final", 0, victim.runs.get());
    }

    @Test
    public void cancellingOnceItHasRunChangesNothing() throws Exception {
        Counter c = new Counter();
        TimerHandle handle = loop.schedule(10, c);
        advance(10);
        handle.cancel();
        assertEquals(1, c.runs.get());
    }

    @Test
    public void aRepeatingTimerKeepsItsPeriod() throws Exception {
        Counter c = new Counter();
        loop.scheduleRepeating(10, 10, c);
        advance(9);
        assertEquals(0, c.runs.get());
        advance(1);
        assertEquals(1, c.runs.get());
        advance(10);
        assertEquals(2, c.runs.get());
        advance(9);
        assertEquals(2, c.runs.get());
        advance(1);
        assertEquals(3, c.runs.get());
    }

    @Test
    public void aRepeatingTimerDoesNotDriftWhenItIsRunLate() throws Exception {
        Counter c = new Counter();
        loop.scheduleRepeating(10, 10, c);
        advance(15);            // 5 ms late for the run due at 10
        assertEquals(1, c.runs.get());
        advance(4);             // 19: the next was due at 20, not 25
        assertEquals(1, c.runs.get());
        advance(1);             // 20
        assertEquals(2, c.runs.get());
    }

    @Test
    public void aRepeatingTimerFarBehindRunsOnceAndRejoinsItsSchedule() throws Exception {
        Counter c = new Counter();
        loop.scheduleRepeating(10, 10, c);
        advance(55);            // missed several: one run, no burst
        assertEquals(1, c.runs.get());
        advance(4);             // 59
        assertEquals(1, c.runs.get());
        advance(1);             // 60, on the original grid
        assertEquals(2, c.runs.get());
    }

    @Test
    public void aRepeatingTimerCanBeCancelledFromItsOwnTask() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<TimerHandle> self = new AtomicReference<TimerHandle>();
        self.set(loop.scheduleRepeating(10, 10, new Runnable() {
            @Override
            public void run() {
                if (runs.incrementAndGet() == 2) {
                    self.get().cancel();
                }
            }
        }));
        advance(10);
        advance(10);
        advance(10);
        advance(10);
        assertEquals(2, runs.get());
    }

    @Test
    public void aRepeatingTimerRefusesAPeriodOfZero() {
        try {
            loop.scheduleRepeating(10, 0, new Counter());
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void aTaskThatThrowsDoesNotStopTheLoopOrTheTimersAfterIt() throws Exception {
        Counter after = new Counter();
        loop.schedule(10, new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("on purpose");
            }
        });
        loop.schedule(10, after);
        advance(10);
        assertEquals(1, after.runs.get());
    }

    @Test
    public void aTaskMayScheduleFurtherTimers() throws Exception {
        final Counter inner = new Counter();
        loop.schedule(10, new Runnable() {
            @Override
            public void run() {
                loop.schedule(5, inner);
            }
        });
        advance(10);
        assertEquals(0, inner.runs.get());
        advance(5);
        assertEquals(1, inner.runs.get());
    }

    @Test
    public void manyThousandsOfTimersNeedNoMoreThreads() throws Exception {
        int before = Thread.getAllStackTraces().size();
        final AtomicInteger fired = new AtomicInteger();
        int count = 20000;
        List<TimerHandle> handles = new ArrayList<TimerHandle>();
        for (int i = 0; i < count; i++) {
            handles.add(loop.schedule(1 + (i % 50), new Runnable() {
                @Override
                public void run() {
                    fired.incrementAndGet();
                }
            }));
        }
        // cancel a quarter of them
        for (int i = 0; i < count; i += 4) {
            handles.get(i).cancel();
        }
        advance(100);
        assertEquals(count - count / 4, fired.get());
        assertTrue("no thread was added: " + before + " -> "
                + Thread.getAllStackTraces().size(),
                Thread.getAllStackTraces().size() <= before);
    }

    @Test
    public void cancelledTimersDoNotPileUp() throws Exception {
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 2000; i++) {
                loop.schedule(3600000, new Counter()).cancel();
            }
        }
        // each held for an hour, but cancelled: the heap does not keep them all
        Integer held = onLoop(new Callable<Integer>() {
            @Override
            public Integer call() {
                return Integer.valueOf(loop.loopTimerCount());
            }
        });
        assertTrue("held " + held, held.intValue() < 10000);
    }

    @Test
    public void aLoopWithoutTimersDoesNotWakeEarly() throws Exception {
        Long none = onLoop(new Callable<Long>() {
            @Override
            public Long call() {
                return Long.valueOf(loop.timerWaitMillis());
            }
        });
        assertEquals("block until something happens", -1L, none.longValue());
    }

    @Test
    public void theLoopWakesForTheNearestTimerAndNoSooner() throws Exception {
        loop.schedule(500, new Counter());
        loop.schedule(200, new Counter());
        Long wait = onLoop(new Callable<Long>() {
            @Override
            public Long call() {
                return Long.valueOf(loop.timerWaitMillis());
            }
        });
        assertEquals(200L, wait.longValue());
    }

    @Test
    public void aCancelledTimerGivesTheLoopNoReasonToWake() throws Exception {
        final TimerHandle handle = loop.schedule(200, new Counter());
        Long wait = onLoop(new Callable<Long>() {
            @Override
            public Long call() {
                handle.cancel();
                return Long.valueOf(loop.timerWaitMillis());
            }
        });
        assertEquals(-1L, wait.longValue());
    }

    @Test
    public void aTimerScheduledFromAnotherThreadWakesALoopBlockedWithNothingToDo() throws Exception {
        // the test thread is not the loop thread
        final Counter c = new Counter();
        loop.schedule(10, c);
        loop.now.addAndGet(10 * MS);
        loop.wakeup();
        assertTrue("ran without any further prompting", c.first.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void timersScheduledOnAFinishedLoopAreInertButSafe() throws Exception {
        Thread thread = loop.getThread();
        loop.shutdown();
        thread.join(5000);
        assertFalse(thread.isAlive());
        Counter c = new Counter();
        TimerHandle handle = loop.schedule(10, c);
        assertTrue("never to run", handle.isCancelled());
        assertEquals(0, c.runs.get());
    }

    @Test
    public void onTheRealClockALoopWithNothingElseToDoWakesForItsTimerAndNotBefore() throws Exception {
        SelectorLoop real = new SelectorLoop(0);
        real.start();
        try {
            final Counter c = new Counter();
            final long start = System.nanoTime();
            final AtomicLong ranAt = new AtomicLong();
            real.schedule(150, new Runnable() {
                @Override
                public void run() {
                    ranAt.set(System.nanoTime());
                    c.run();
                }
            });
            assertTrue("it ran with no prompting", c.first.await(10, TimeUnit.SECONDS));
            long elapsedMs = (ranAt.get() - start) / MS;
            assertTrue("ran after " + elapsedMs + " ms, before its 150", elapsedMs >= 150);
            assertSame(real.getThread(), c.thread.get());
        } finally {
            real.shutdown();
        }
    }
}
