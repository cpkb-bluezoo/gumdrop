/*
 * MailboxIndexer.java
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

package org.bluezoo.gumdrop.mailbox.index;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A single dedicated background thread that rebuilds/refreshes mailbox
 * search indexes ({@link MessageIndex}), pulling jobs off a priority
 * queue instead of running the (potentially expensive, full-mailbox-scan)
 * rebuild inline on whichever thread happens to trigger it.
 *
 * <p>Deliberately a single thread, not a pool: mailbox index rebuilds are
 * disk/CPU-bound background work, and running them one at a time is what
 * makes the priority ordering below meaningful (a pool would let a
 * low-priority background job and a high-priority live job run
 * concurrently, defeating the point of prioritizing at all). It is also
 * kept separate from {@link org.bluezoo.gumdrop.StorageExecutor} - the
 * general-purpose bounded pool shared by every protocol's blocking file
 * I/O - so mailbox indexing work can never starve APPEND/FETCH/SEARCH, or
 * be starved by them.
 *
 * <p>Two kinds of job:
 * <ul>
 * <li><b>Live</b> ({@link #ensureFreshBlocking}): a client request (SELECT,
 * APPEND, SEARCH) is synchronously blocked waiting for this mailbox's index
 * to become current. Live jobs always run before any background job,
 * ordered among themselves by arrival - a live request must never return
 * stale/partial results, so it always wins over pre-warming work.</li>
 * <li><b>Background</b> ({@link #submitBackground}): opportunistic
 * pre-warming, from eager per-store enumeration at login or a filesystem
 * watch noticing an external change. Ordered by INBOX first, then most
 * -recently-modified mailbox first (more likely to be opened soon).
 * Deduplicated by key: a second background request for a mailbox that
 * already has one queued (not yet started) is a no-op, since the single
 * eventual execution refreshes the same on-disk index either request
 * would have produced.</li>
 * </ul>
 *
 * <p>Live jobs are never deduplicated against each other or against a
 * background job for the same key: a live job's closure is bound to the
 * specific {@code Mailbox} instance that is waiting for it (its own
 * in-memory {@code searchIndex} field needs to end up populated), so
 * reusing a different instance's closure would silently leave the waiting
 * instance's index unbuilt. A still-queued background job for the same key
 * is cancelled when a live job for it arrives instead, since the live
 * job's own execution makes it redundant.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxIndexer {

    private static final Logger LOGGER = Logger.getLogger(MailboxIndexer.class.getName());

    /**
     * The work a job performs: rebuild/refresh one mailbox's search index.
     * Exceptions are captured and rethrown (wrapped if necessary) to
     * whichever thread is blocked in {@link #ensureFreshBlocking}, or
     * logged and dropped for a background job with nothing waiting.
     */
    @FunctionalInterface
    public interface IndexWork {
        void run() throws Exception;
    }

    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>(64);
    private final Map<MailboxIndexKey, Job> pendingBackground = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    public MailboxIndexer() {
        worker = new Thread(this::runLoop, "gumdrop-mailbox-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Returns whether the calling thread is this indexer's own single
     * background worker thread.
     *
     * <p>A background job's work (eager per-store warming, or a
     * filesystem-watch catch-up) typically opens a {@code Mailbox}, whose
     * constructor calls back into {@link #ensureFreshBlocking} if <em>its
     * own</em> index also needs a rebuild - which is, in fact, the whole
     * point of warming it. Submitting and blocking on a second job from
     * inside the single worker thread that would have to run it would
     * deadlock. Callers must check this and, if true, perform the rebuild
     * directly instead of routing through this indexer again.
     *
     * @return true if called from this indexer's worker thread
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == worker;
    }

    /**
     * Ensures a mailbox's search index is current, blocking the calling
     * thread until the rebuild/refresh completes. For a client request
     * (SELECT/APPEND/SEARCH) that must not proceed against a stale or
     * partial index.
     *
     * @param key identifies the mailbox's persisted index
     * @param isInbox true if this is the INBOX of its store (only affects
     *      priority of other jobs queued behind this one, since live jobs
     *      always run first regardless)
     * @param lastModified the mailbox's last-modified time, for priority
     *      purposes if this job is later superseded/requeued
     * @param work performs the actual rebuild/refresh
     * @throws IOException if the work fails
     * @throws InterruptedException if interrupted while waiting
     */
    public void ensureFreshBlocking(MailboxIndexKey key, boolean isInbox,
            long lastModified, IndexWork work)
            throws IOException, InterruptedException {
        // A still-queued (not yet started) background job for this key is
        // superseded by our own live execution - cancel it rather than
        // leave it to run redundantly after us.
        Job stale = pendingBackground.remove(key);
        if (stale != null) {
            stale.cancelled = true;
        }

        Job job = new Job(key, isInbox, lastModified, work, true,
                sequence.incrementAndGet());
        queue.offer(job);
        job.await();

        Throwable error = job.error;
        if (error != null) {
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            IOException wrapped = new IOException("Mailbox indexing failed for " + key);
            wrapped.initCause(error);
            throw wrapped;
        }
    }

    /**
     * Submits opportunistic pre-warming work for a mailbox: does not block
     * the caller, and is deduplicated against any not-yet-started
     * background job already queued for the same key.
     *
     * @param key identifies the mailbox's persisted index
     * @param isInbox true if this is the INBOX of its store
     * @param lastModified the mailbox's last-modified time, used to order
     *      this job against other background jobs
     * @param work performs the actual rebuild/refresh
     */
    public void submitBackground(MailboxIndexKey key, boolean isInbox,
            long lastModified, IndexWork work) {
        pendingBackground.compute(key, (k, existing) -> {
            if (existing != null && !existing.cancelled) {
                return existing;
            }
            Job job = new Job(k, isInbox, lastModified, work, false,
                    sequence.incrementAndGet());
            queue.offer(job);
            return job;
        });
    }

    /**
     * Returns the approximate number of jobs queued or in flight. Intended
     * for diagnostics and tests.
     */
    public int pendingCount() {
        return queue.size();
    }

    /**
     * Stops the background thread. In-flight work is allowed to finish;
     * queued-but-not-started jobs are abandoned (any live caller still
     * waiting on one receives an {@link InterruptedException} via its
     * blocked {@link #ensureFreshBlocking} call).
     */
    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void runLoop() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                continue; // re-check running
            }
            if (job.cancelled) {
                continue;
            }
            if (!job.live) {
                pendingBackground.remove(job.key, job);
            }
            try {
                job.work.run();
            } catch (Throwable t) {
                job.error = t;
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Mailbox indexing job failed for " + job.key, t);
                }
            } finally {
                job.done.countDown();
            }
        }
        // Release anyone still waiting on a queued-but-abandoned live job.
        Job leftover;
        while ((leftover = queue.poll()) != null) {
            leftover.error = new java.io.InterruptedIOException(
                    "Mailbox indexer shut down before this job ran");
            leftover.done.countDown();
        }
    }

    /**
     * One queued unit of work. Live jobs always sort before background
     * jobs; among background jobs, INBOX first, then most-recently
     * -modified first; ties broken by arrival order (FIFO) in all cases.
     */
    private static final class Job implements Comparable<Job> {
        final MailboxIndexKey key;
        final boolean isInbox;
        final long lastModified;
        final IndexWork work;
        final boolean live;
        final long seq;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Throwable error;
        volatile boolean cancelled;

        Job(MailboxIndexKey key, boolean isInbox, long lastModified,
                IndexWork work, boolean live, long seq) {
            this.key = key;
            this.isInbox = isInbox;
            this.lastModified = lastModified;
            this.work = work;
            this.live = live;
            this.seq = seq;
        }

        void await() throws InterruptedException {
            done.await();
        }

        @Override
        public int compareTo(Job other) {
            if (this.live != other.live) {
                return this.live ? -1 : 1;
            }
            if (!this.live) {
                if (this.isInbox != other.isInbox) {
                    return this.isInbox ? -1 : 1;
                }
                int cmp = Long.compare(other.lastModified, this.lastModified);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Long.compare(this.seq, other.seq);
        }
    }
}
