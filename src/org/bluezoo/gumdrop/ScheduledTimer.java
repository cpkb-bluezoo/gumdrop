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

import java.util.PriorityQueue;
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
final class ScheduledTimer extends Thread {

    private static final Logger LOGGER = Logger.getLogger(ScheduledTimer.class.getName());

    private static final AtomicLong TIMER_ID_GENERATOR = new AtomicLong(0);

    private final PriorityQueue<TimerEntry> queue;
    private final Lock lock;
    private final Condition condition;
    private volatile boolean active;

    ScheduledTimer() {
        super("ScheduledTimer");
        setDaemon(true);
        this.queue = new PriorityQueue<>();
        this.lock = new ReentrantLock();
        this.condition = lock.newCondition();
    }

    @Override
    public void run() {
        active = true;

        while (active) {
            lock.lock();
            try {
                // Wait for next timer or new entry
                while (active && queue.isEmpty()) {
                    condition.await();
                }

                if (!active) {
                    break;
                }

                // Get next timer
                TimerEntry next = queue.peek();
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
        if (handle instanceof TimerEntry) {
            ((TimerEntry) handle).cancelled = true;
            // Note: We don't remove from queue immediately for simplicity.
            // It will be skipped when it fires.
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
     * Timer entry in the priority queue.
     */
    static final class TimerEntry implements TimerHandle, Comparable<TimerEntry> {
        final long id;
        final long fireTime;
        final ChannelHandler handler;
        final Runnable callback;
        volatile boolean cancelled;

        TimerEntry(long id, long fireTime, ChannelHandler handler, Runnable callback) {
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
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

}

