/*
 * MailboxIndexerTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MailboxIndexer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxIndexerTest {

    private MailboxIndexer indexer;

    @Before
    public void setUp() {
        indexer = new MailboxIndexer();
    }

    @After
    public void tearDown() {
        indexer.shutdown();
    }

    private static MailboxIndexKey key(String path) {
        return new MailboxIndexKey(Paths.get(path));
    }

    @Test
    public void ensureFreshBlocking_runsWorkAndReturns() throws Exception {
        final AtomicInteger ran = new AtomicInteger();
        indexer.ensureFreshBlocking(key("/tmp/a"), true, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { ran.incrementAndGet(); }
        });
        assertEquals(1, ran.get());
    }

    @Test
    public void ensureFreshBlocking_propagatesIOException() {
        final IOException expected = new IOException("boom");
        try {
            indexer.ensureFreshBlocking(key("/tmp/a"), true, 0L, new MailboxIndexer.IndexWork() {
                @Override public void run() throws Exception {
                    throw expected;
                }
            });
            fail("Expected IOException");
        } catch (IOException e) {
            assertSame(expected, e);
        } catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
    }

    @Test
    public void ensureFreshBlocking_rethrowsRuntimeExceptionUnwrapped() {
        try {
            indexer.ensureFreshBlocking(key("/tmp/a"), true, 0L, new MailboxIndexer.IndexWork() {
                @Override public void run() {
                    throw new IllegalStateException("bad state");
                }
            });
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("bad state", e.getMessage());
        } catch (IOException | InterruptedException e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void submitBackground_runsAsynchronously() throws Exception {
        final CountDownLatch ran = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/a"), true, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { ran.countDown(); }
        });
        assertTrue("background job should run", ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void submitBackground_deduplicatesQueuedJobsForSameKey() throws Exception {
        // Block the worker so both submissions are still queued (not yet
        // started) when the second one arrives - only then does dedup apply.
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                releaseBlocker.await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        final AtomicInteger count = new AtomicInteger();
        MailboxIndexKey k = key("/tmp/dup");
        MailboxIndexer.IndexWork incrementCount = new MailboxIndexer.IndexWork() {
            @Override public void run() { count.incrementAndGet(); }
        };
        indexer.submitBackground(k, false, 0L, incrementCount);
        indexer.submitBackground(k, false, 0L, incrementCount);
        indexer.submitBackground(k, false, 0L, incrementCount);

        releaseBlocker.countDown();

        // Give the worker time to drain both the blocker and the (single,
        // deduplicated) job for k.
        long deadline = System.currentTimeMillis() + 5000;
        while (indexer.pendingCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        // Allow the last job to finish running after being dequeued.
        Thread.sleep(200);

        assertEquals("only one of the three duplicate submissions should have run",
                1, count.get());
    }

    @Test
    public void liveJob_cancelsQueuedBackgroundJobForSameKey() throws Exception {
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker2"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                releaseBlocker.await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        MailboxIndexKey k = key("/tmp/live-cancel");
        final AtomicInteger backgroundRuns = new AtomicInteger();
        indexer.submitBackground(k, false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { backgroundRuns.incrementAndGet(); }
        });

        final AtomicInteger liveRuns = new AtomicInteger();
        // Release the blocker concurrently with the live call so the live
        // job is queued while the background job for k is still pending.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                releaseBlocker.countDown();
            }
        }).start();

        indexer.ensureFreshBlocking(k, false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { liveRuns.incrementAndGet(); }
        });

        Thread.sleep(200);
        assertEquals(1, liveRuns.get());
        assertEquals("cancelled background job must not also run",
                0, backgroundRuns.get());
    }

    @Test
    public void livePriority_runsBeforeQueuedBackgroundJobs() throws Exception {
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker3"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                releaseBlocker.await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        final List<String> order = new CopyOnWriteArrayList<>();
        indexer.submitBackground(key("/tmp/bg1"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("bg1"); }
        });
        indexer.submitBackground(key("/tmp/bg2"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("bg2"); }
        });

        // Enqueue the live job on its own thread *before* releasing the
        // blocker, so it is genuinely queued behind bg1/bg2 (not started
        // after them) when the worker becomes free to pick a job.
        final CountDownLatch liveDone = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    indexer.ensureFreshBlocking(key("/tmp/live"), false, 0L, new MailboxIndexer.IndexWork() {
                        @Override public void run() { order.add("live"); }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    liveDone.countDown();
                }
            }
        }).start();
        Thread.sleep(100);

        releaseBlocker.countDown();
        assertTrue(liveDone.await(5, TimeUnit.SECONDS));

        long deadline = System.currentTimeMillis() + 5000;
        while (order.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(3, order.size());
        assertEquals("live job must run before any background job queued behind it",
                "live", order.get(0));
    }

    @Test
    public void backgroundPriority_inboxBeforeNonInbox() throws Exception {
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker4"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                releaseBlocker.await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        final List<String> order = new CopyOnWriteArrayList<>();
        indexer.submitBackground(key("/tmp/other"), false, 100L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("other"); }
        });
        indexer.submitBackground(key("/tmp/inbox"), true, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("inbox"); }
        });

        releaseBlocker.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        while (order.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, order.size());
        assertEquals("INBOX should be prioritized ahead of a non-INBOX mailbox",
                "inbox", order.get(0));
    }

    @Test
    public void backgroundPriority_mostRecentlyModifiedFirst() throws Exception {
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker5"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                releaseBlocker.await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        final List<String> order = new CopyOnWriteArrayList<>();
        indexer.submitBackground(key("/tmp/old"), false, 1000L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("old"); }
        });
        indexer.submitBackground(key("/tmp/new"), false, 9000L, new MailboxIndexer.IndexWork() {
            @Override public void run() { order.add("new"); }
        });

        releaseBlocker.countDown();

        long deadline = System.currentTimeMillis() + 5000;
        while (order.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, order.size());
        assertEquals("most recently modified mailbox should run first",
                "new", order.get(0));
    }

    @Test
    public void isCurrentThread_trueOnlyOnWorkerThread() throws Exception {
        assertFalse(indexer.isCurrentThread());

        final AtomicInteger sawTrue = new AtomicInteger();
        indexer.ensureFreshBlocking(key("/tmp/thread-check"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() {
                if (indexer.isCurrentThread()) {
                    sawTrue.incrementAndGet();
                }
            }
        });
        assertEquals(1, sawTrue.get());
    }

    @Test
    public void shutdown_releasesWaitingLiveCallerWithException() throws Exception {
        // Block the worker forever (until shutdown), then queue a live job
        // behind it and shut down - the live caller must be released
        // rather than hang.
        final CountDownLatch blockerRunning = new CountDownLatch(1);
        indexer.submitBackground(key("/tmp/blocker6"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override public void run() throws Exception {
                blockerRunning.countDown();
                // Block until interrupted by shutdown().
                new CountDownLatch(1).await();
            }
        });
        assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

        final CountDownLatch liveDone = new CountDownLatch(1);
        final boolean[] threw = new boolean[1];
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    indexer.ensureFreshBlocking(key("/tmp/never-runs"), false, 0L, new MailboxIndexer.IndexWork() {
                        @Override public void run() { }
                    });
                } catch (IOException | InterruptedException e) {
                    threw[0] = true;
                } finally {
                    liveDone.countDown();
                }
            }
        }).start();

        // Give the live job a moment to be queued behind the stuck blocker.
        Thread.sleep(100);
        indexer.shutdown();

        assertTrue("live caller must be released after shutdown",
                liveDone.await(5, TimeUnit.SECONDS));
        assertTrue("live caller should observe a failure, not silently succeed", threw[0]);
    }

    @Test
    public void shutdown_waitsForInFlightJobToActuallyFinish() throws Exception {
        // Regression test for issue #349: shutdown() used to interrupt the
        // worker and return immediately, without waiting for a job already
        // running to actually stop. That is fine for work that responds to
        // interrupt() promptly (as the blocker in
        // shutdown_releasesWaitingLiveCallerWithException above does, via
        // CountDownLatch.await()), but real IndexWork can be a blocking
        // file write mid-syscall, which does not - so a caller of
        // shutdown() (Gumdrop.shutdown(), in production) had no guarantee
        // that in-flight indexing had actually quiesced by the time it
        // returned. Simulates that by having the job swallow the interrupt
        // and keep "running" until explicitly released, so this test can
        // assert shutdown() is still blocked while it does.
        final CountDownLatch jobRunning = new CountDownLatch(1);
        final CountDownLatch releaseJob = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean jobFinished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        indexer.submitBackground(key("/tmp/slow"), false, 0L, new MailboxIndexer.IndexWork() {
            @Override
            public void run() {
                jobRunning.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseJob.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Deliberately keep waiting, as uninterruptible
                        // blocking I/O (e.g. a file write already inside
                        // the OS call) would.
                    }
                }
                jobFinished.set(true);
            }
        });
        assertTrue(jobRunning.await(5, TimeUnit.SECONDS));

        final CountDownLatch shutdownReturned = new CountDownLatch(1);
        Thread shutdownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                indexer.shutdown();
                shutdownReturned.countDown();
            }
        });
        shutdownThread.start();

        assertFalse("shutdown() must wait for the in-flight job, not return "
                        + "while it is still running",
                shutdownReturned.await(300, TimeUnit.MILLISECONDS));
        assertFalse("the job must not be reported finished while shutdown() "
                        + "is still blocked on it",
                jobFinished.get());

        releaseJob.countDown();

        assertTrue("shutdown() must return once the in-flight job actually finishes",
                shutdownReturned.await(5, TimeUnit.SECONDS));
        assertTrue("the job must have completed before shutdown() returned",
                jobFinished.get());
    }
}
