/*
 * ScheduledTimer.java
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

package org.bluezoo.gumdrop;

import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Timer service for scheduling callbacks on SelectorLoop threads.
 *
 * <p>This utility allows servers and clients to schedule delayed callbacks
 * that will be executed on their assigned SelectorLoop thread, ensuring
 * thread safety for I/O operations.
 *
 * <p>Common use cases include:
 * <ul>
 * <li>Periodic keep-alive or ping messages</li>
 * <li>Connection/request timeouts</li>
 * <li>Delayed cleanup or retry operations</li>
 * </ul>
 *
 * <p>The timer runs in its own thread but dispatches callbacks through
 * the SelectorLoop's pending queue, ensuring all callbacks execute on
 * the correct thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ScheduledTimer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ScheduledTimer.class.getName());

    private static final AtomicLong TIMER_ID_GENERATOR = new AtomicLong(0);

    /**
     * Number of accumulated cancellations that must build up before the timer
     * thread sweeps cancelled entries out of the middle of the queue. Cancelling
     * a timer is O(1) and leaves a tombstone behind; this bounds the number of
     * tombstones that can accumulate between sweeps.
     */
    private static final int PURGE_THRESHOLD = 64;

    private final PriorityQueue<TimerEntry> queue;
    private final Lock lock;
    private final Condition condition;

    /**
     * Approximate count of cancelled-but-still-queued entries since the last
     * sweep. Maintained lock-free so that {@link TimerHandle#cancel()} does not
     * contend on the timer lock; used only as a heuristic to trigger a sweep.
     */
    private final AtomicInteger deadCount = new AtomicInteger();

    private Thread thread;
    private volatile boolean active;
    private final String name;

    ScheduledTimer() {
        this("ScheduledTimer");
    }

    ScheduledTimer(String name) {
        this.name = name;
        this.queue = new PriorityQueue<TimerEntry>();
        this.lock = new ReentrantLock();
        this.condition = lock.newCondition();
    }

    /**
     * Starts this ScheduledTimer.
     * Creates a new thread if needed and starts processing.
     */
    public void start() {
        if (thread != null && thread.isAlive()) {
            return; // Already running
        }
        thread = new Thread(this, name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Returns whether this ScheduledTimer is currently running.
     *
     * @return true if the thread is alive
     */
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        active = true;

        while (active) {
            lock.lock();
            try {
                // Reclaim cancelled entries buried in the queue. This keeps the
                // queue bounded to roughly the number of live timers even when
                // callers cancel-and-reschedule on every request/connection.
                purgeCancelledIfNeeded();

                // Wait for next timer or new entry
                while (active && queue.isEmpty()) {
                    condition.await();
                }

                if (!active) {
                    break;
                }

                // Drop any cancelled entries at the head so we neither wait on
                // them nor hold their memory until their original fire time.
                TimerEntry next = queue.peek();
                while (next != null && next.cancelled) {
                    queue.poll();
                    next = queue.peek();
                }
                if (next == null) {
                    continue;
                }

                long now = System.currentTimeMillis();
                long delay = next.fireTime - now;

                if (delay <= 0) {
                    // Timer has fired - remove and dispatch
                    queue.poll();
                    dispatchTimer(next);
                } else {
                    // Wait until fire time (or new entry)
                    condition.awaitNanos(delay * 1_000_000);
                }
            } catch (InterruptedException e) {
                // Check if we should continue
                if (!active) {
                    break;
                }
            } finally {
                lock.unlock();
            }
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("ScheduledTimer shutdown");
        }
    }

    /**
     * Dispatches a timer callback to the handler's SelectorLoop.
     */
    private void dispatchTimer(TimerEntry entry) {
        if (entry.cancelled) {
            return;
        }

        SelectorLoop loop = entry.handler.getSelectorLoop();
        if (loop != null) {
            loop.dispatchTimer(entry);
        }
    }

    /**
     * Schedules a timer callback.
     *
     * @param handler the handler that will receive the callback
     * @param delayMs delay in milliseconds
     * @param callback the callback to execute
     * @return a TimerHandle that can be used to cancel the timer
     */
    TimerHandle schedule(ChannelHandler handler, long delayMs, Runnable callback) {
        long fireTime = System.currentTimeMillis() + delayMs;
        TimerEntry entry = new TimerEntry(
                this,
                TIMER_ID_GENERATOR.incrementAndGet(),
                fireTime,
                handler,
                callback
        );

        lock.lock();
        try {
            queue.offer(entry);
            condition.signal();
        } finally {
            lock.unlock();
        }

        return entry;
    }

    /**
     * Cancels a scheduled timer.
     *
     * @param handle the timer handle returned from schedule()
     */
    void cancel(TimerHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    /**
     * Called (lock-free) when an entry is cancelled. Wakes the timer thread to
     * sweep tombstones once enough have accumulated so that cancelled timers do
     * not linger in the queue until their original fire time.
     */
    private void onEntryCancelled() {
        if (deadCount.incrementAndGet() == PURGE_THRESHOLD) {
            lock.lock();
            try {
                condition.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Sweeps cancelled entries out of the queue when enough have accumulated.
     * Must be called with {@link #lock} held.
     */
    private void purgeCancelledIfNeeded() {
        if (deadCount.get() >= PURGE_THRESHOLD) {
            for (Iterator<TimerEntry> it = queue.iterator(); it.hasNext(); ) {
                if (it.next().isCancelled()) {
                    it.remove();
                }
            }
            deadCount.set(0);
        }
    }

    /**
     * Returns the number of entries currently held in the queue, including any
     * cancelled entries not yet swept. Intended for diagnostics and tests.
     *
     * @return the current queue depth
     */
    int pendingCount() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts down the timer thread.
     */
    void shutdown() {
        active = false;
        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for this ScheduledTimer's thread to terminate.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    /**
     * Timer entry in the priority queue.
     */
    static final class TimerEntry implements TimerHandle, Comparable<TimerEntry> {
        private final ScheduledTimer owner;
        final long id;
        final long fireTime;
        final ChannelHandler handler;
        final Runnable callback;
        volatile boolean cancelled;

        TimerEntry(ScheduledTimer owner, long id, long fireTime, ChannelHandler handler, Runnable callback) {
            this.owner = owner;
            this.id = id;
            this.fireTime = fireTime;
            this.handler = handler;
            this.callback = callback;
        }

        @Override
        public int compareTo(TimerEntry other) {
            int cmp = Long.compare(this.fireTime, other.fireTime);
            if (cmp == 0) {
                cmp = Long.compare(this.id, other.id);
            }
            return cmp;
        }

        @Override
        public void cancel() {
            // Guard against the benign double-cancel race over-counting; the
            // queue itself is swept by the timer thread, so cancel() stays O(1)
            // and lock-free.
            if (!cancelled) {
                cancelled = true;
                if (owner != null) {
                    owner.onEntryCancelled();
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

}
