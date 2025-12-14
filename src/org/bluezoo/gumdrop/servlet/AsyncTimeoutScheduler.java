/*
 * AsyncTimeoutScheduler.java
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

import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Timer scheduler for servlet async context timeouts.
 *
 * <p>This is a lightweight timer implementation that uses a priority queue
 * and a single background thread to dispatch timeout callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AsyncTimeoutScheduler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(AsyncTimeoutScheduler.class.getName());
    private static final AtomicLong TIMER_ID_GENERATOR = new AtomicLong(0);

    private final PriorityQueue<TimeoutEntry> queue;
    private final Lock lock;
    private final Condition condition;
    private Thread thread;
    private volatile boolean active;

    AsyncTimeoutScheduler() {
        this.queue = new PriorityQueue<TimeoutEntry>();
        this.lock = new ReentrantLock();
        this.condition = lock.newCondition();
    }

    /**
     * Starts this scheduler.
     */
    void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(this, "AsyncTimeoutScheduler");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        active = true;

        while (active) {
            lock.lock();
            try {
                while (active && queue.isEmpty()) {
                    condition.await();
                }

                if (!active) {
                    break;
                }

                TimeoutEntry next = queue.peek();
                if (next == null) {
                    continue;
                }

                long now = System.currentTimeMillis();
                long delay = next.fireTime - now;

                if (delay <= 0) {
                    queue.poll();
                    fireTimeout(next);
                } else {
                    condition.awaitNanos(delay * 1_000_000);
                }
            } catch (InterruptedException e) {
                if (!active) {
                    break;
                }
            } finally {
                lock.unlock();
            }
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("AsyncTimeoutScheduler shutdown");
        }
    }

    private void fireTimeout(TimeoutEntry entry) {
        if (entry.cancelled) {
            return;
        }

        try {
            entry.callback.onTimeout();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in async timeout callback", e);
        }
    }

    /**
     * Schedules a timeout callback.
     *
     * @param delayMs delay in milliseconds
     * @param callback the callback to execute on timeout
     * @return a handle that can be used to cancel the timeout
     */
    AsyncTimeoutHandle schedule(long delayMs, AsyncTimeoutCallback callback) {
        long fireTime = System.currentTimeMillis() + delayMs;
        TimeoutEntry entry = new TimeoutEntry(
                TIMER_ID_GENERATOR.incrementAndGet(),
                fireTime,
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
     * Shuts down this scheduler.
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
     * Timeout entry in the priority queue.
     */
    private static final class TimeoutEntry implements AsyncTimeoutHandle, Comparable<TimeoutEntry> {
        final long id;
        final long fireTime;
        final AsyncTimeoutCallback callback;
        volatile boolean cancelled;

        TimeoutEntry(long id, long fireTime, AsyncTimeoutCallback callback) {
            this.id = id;
            this.fireTime = fireTime;
            this.callback = callback;
        }

        @Override
        public int compareTo(TimeoutEntry other) {
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

