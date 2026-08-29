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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small bounded pool of background threads that rebuild/refreshes mailbox
 * search indexes ({@link MessageIndex}), pulling jobs off a priority queue
 * instead of running the (potentially expensive, full-mailbox-scan) rebuild
 * inline on whichever thread happens to trigger it.
 *
 * <p>Deliberately a modest pool, not one thread per mailbox: index rebuilds
 * are disk-bound background work, and the shared {@link PriorityBlockingQueue}
 * still makes priority ordering meaningful -- the next free worker always
 * takes the highest-priority pending job (live before background, INBOX before
 * other mailboxes, and so on). Unrelated mailboxes can rebuild in parallel
 * without serialising cold-start work across the whole server. The pool is
 * also kept separate from {@link org.bluezoo.gumdrop.StorageExecutor} - the
 * general-purpose bounded pool shared by every protocol's blocking file I/O -
 * so mailbox indexing work can never starve APPEND/FETCH/SEARCH, or be
 * starved by them.
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
public final class MailboxIndexer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(MailboxIndexer.class.getName());

    /**
     * Default pool size when {@code gumdrop.mailboxIndexThreads} is not set.
     * Rebuilds are disk-bound; a small cap keeps priority meaningful while
     * still letting unrelated mailboxes progress in parallel on cold start.
     */
    private static final int DEFAULT_POOL_SIZE =
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors()));

    /**
     * The work a job performs: rebuild/refresh one mailbox's search index.
     * Exceptions are captured and rethrown (wrapped if necessary) to
     * whichever thread is blocked in {@link #ensureFreshBlocking}, or
     * logged and dropped for a background job with nothing waiting.
     */
    public interface IndexWork {
        void run() throws Exception;
    }

    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>(64);
    private final Map<MailboxIndexKey, Job> pendingBackground = new ConcurrentHashMap<>();
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread[] workers;
    private volatile boolean running = true;

    /**
     * Test-only: if non-null, invoked on the calling thread immediately
     * after {@link #ensureFreshBlocking} enqueues a live job.
     */
    static volatile Runnable afterLiveJobQueued;

    public MailboxIndexer() {
        this(poolSizeFromProperty());
    }

    /**
     * @param poolSize number of worker threads (must be positive); exposed
     *        for unit tests that need deterministic single-worker behaviour
     */
    MailboxIndexer(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive");
        }
        workers = new Thread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            Thread t = new Thread(this, workerThreadName(i, poolSize));
            t.setDaemon(true);
            workers[i] = t;
            t.start();
        }
    }

    private static int poolSizeFromProperty() {
        return Integer.getInteger("gumdrop.mailboxIndexThreads",
                DEFAULT_POOL_SIZE);
    }

    private static String workerThreadName(int index, int poolSize) {
        if (poolSize == 1) {
            return "gumdrop-mailbox-indexer";
        }
        return "gumdrop-mailbox-indexer-" + index;
    }

    /**
     * Returns whether the calling thread is one of this indexer's pool
     * worker threads.
     *
     * <p>A background job's work (eager per-store warming, or a
     * filesystem-watch catch-up) typically opens a {@code Mailbox}, whose
     * constructor calls back into {@link #ensureFreshBlocking} if <em>its
     * own</em> index also needs a rebuild - which is, in fact, the whole
     * point of warming it. Submitting and blocking on a second job from
     * inside a pool worker that would have to run it would deadlock. Callers
     * must check this and, if true, perform the rebuild directly instead of
     * routing through this indexer again.
     *
     * @return true if called from one of this indexer's worker threads
     */
    public boolean isCurrentThread() {
        return workerThreads.contains(Thread.currentThread());
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
        Runnable hook = afterLiveJobQueued;
        if (hook != null) {
            hook.run();
        }
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
        while (true) {
            Job existing = pendingBackground.get(key);
            if (existing != null && !existing.cancelled) {
                return;
            }
            Job job = new Job(key, isInbox, lastModified, work, false,
                    sequence.incrementAndGet());
            boolean replaced = (existing == null)
                    ? (pendingBackground.putIfAbsent(key, job) == null)
                    : pendingBackground.replace(key, existing, job);
            if (replaced) {
                queue.offer(job);
                return;
            }
            // Lost a race with another submitBackground/ensureFreshBlocking
            // call for this key; retry against the current state.
        }
    }

    /**
     * Returns the approximate number of jobs queued or in flight. Intended
     * for diagnostics and tests.
     */
    public int pendingCount() {
        return queue.size();
    }

    /**
     * How long {@link #shutdown()} waits for each worker thread to
     * actually terminate before giving up, mirroring {@link
     * org.bluezoo.gumdrop.StorageExecutor}'s own shutdown-await constant.
     * {@link Thread#interrupt()} does not guarantee a job stops
     * immediately -- {@code IndexWork} can be a blocking file write
     * already inside the OS call, which does not respond to interrupt at
     * all -- so this bounds how long a caller waits for the in-flight
     * job to genuinely finish rather than just observing that no new one
     * will start (issue #349).
     */
    private static final long SHUTDOWN_AWAIT_MS = 5000L;

    /**
     * Stops the pool, waiting (up to {@link #SHUTDOWN_AWAIT_MS} per worker)
     * for each thread to actually terminate before returning. In-flight work
     * is allowed to finish; queued-but-not-started jobs are abandoned (any
     * live caller still waiting on one receives an {@link
     * InterruptedException} via its blocked {@link #ensureFreshBlocking}
     * call).
     *
     * <p>Without this wait, a caller of {@code Gumdrop.shutdown()} -- which
     * calls this via {@code MailboxLifecycle.onServerStop()} -- could
     * proceed (e.g. delete a mailbox's directory tree, as a test's
     * teardown does) while an indexer worker was still mid-write on that
     * same mailbox's search index file, racing it (issue #349).
     */
    public void shutdown() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(SHUTDOWN_AWAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAbandonedJobs();
    }

    private void releaseAbandonedJobs() {
        Job leftover;
        while ((leftover = queue.poll()) != null) {
            leftover.error = new java.io.InterruptedIOException(
                    "Mailbox indexer shut down before this job ran");
            leftover.done.countDown();
        }
    }

    @Override
    public void run() {
        workerThreads.add(Thread.currentThread());
        try {
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
        } finally {
            workerThreads.remove(Thread.currentThread());
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
