/*
 * MailboxIndexerPerformanceTest.java
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
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from MailboxIndexerTest.
 */
public class MailboxIndexerPerformanceTest {

    private MailboxIndexer indexer;

    @Before
    public void setUp() {
        indexer = new MailboxIndexer(1);
    }

    @After
    public void tearDown() {
        MailboxIndexer.afterLiveJobQueued = null;
        indexer.shutdown();
    }

    private static MailboxIndexKey key(String path) {
        return new MailboxIndexKey(Paths.get(path));
    }

























    /**
     * Regression coverage for issue #319: a single worker serialised every
     * mailbox index rebuild server-wide; unrelated mailboxes should rebuild
     * concurrently up to the pool size.
     */
    @Test(timeout = 15000)
    public void unrelatedRebuildsRunConcurrentlyOnPool() throws Exception {
        MailboxIndexer poolIndexer = new MailboxIndexer(4);
        try {
            final int mailboxCount = 4;
            final int workMs = 150;
            final AtomicInteger inFlight = new AtomicInteger();
            final AtomicInteger maxInFlight = new AtomicInteger();
            final CountDownLatch allRunning = new CountDownLatch(mailboxCount);

            long startNs = System.nanoTime();
            Thread[] clients = new Thread[mailboxCount];
            for (int i = 0; i < mailboxCount; i++) {
                final MailboxIndexKey k = key("/tmp/parallel-" + i);
                clients[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            poolIndexer.ensureFreshBlocking(k, false, 0L,
                                    new MailboxIndexer.IndexWork() {
                                @Override
                                public void run() throws InterruptedException {
                                    int now = inFlight.incrementAndGet();
                                    while (true) {
                                        int prev = maxInFlight.get();
                                        if (now <= prev) {
                                            break;
                                        }
                                        if (maxInFlight.compareAndSet(prev, now)) {
                                            break;
                                        }
                                    }
                                    allRunning.countDown();
                                    Thread.sleep(workMs);
                                    inFlight.decrementAndGet();
                                }
                            });
                        } catch (IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                clients[i].start();
            }

            for (int i = 0; i < mailboxCount; i++) {
                clients[i].join(5000);
            }
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            assertTrue("unrelated mailbox rebuilds must overlap on the pool "
                    + "(peak in-flight was " + maxInFlight.get() + ")",
                    maxInFlight.get() >= 2);
            assertTrue("four parallel rebuilds took " + elapsedMs + "ms -- a "
                    + "single worker serialising them would need at least "
                    + (mailboxCount * workMs) + "ms",
                    elapsedMs < mailboxCount * workMs - 100);
        } finally {
            poolIndexer.shutdown();
        }
    }
}
