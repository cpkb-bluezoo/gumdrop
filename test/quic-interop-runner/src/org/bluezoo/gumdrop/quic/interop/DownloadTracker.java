/*
 * DownloadTracker.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts a batch of downloads to completion from the selector loop so the
 * main thread can wait for them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DownloadTracker {

    private final CountDownLatch remaining;
    private final AtomicInteger failures = new AtomicInteger();

    DownloadTracker(int count) {
        this.remaining = new CountDownLatch(count);
    }

    void finished(boolean ok) {
        if (!ok) {
            failures.incrementAndGet();
        }
        remaining.countDown();
    }

    /**
     * @return true if every download finished within the timeout
     */
    boolean await(long timeoutMillis) throws InterruptedException {
        return remaining.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    int failures() {
        return failures.get();
    }

}
