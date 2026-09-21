/*
 * RecordingCompletionHandler.java
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

package org.bluezoo.gumdrop.testsupport;

import java.nio.channels.CompletionHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link CompletionHandler} that records its outcome and lets a test block on
 * completion with a {@link CountDownLatch}, so async tests need no sleeping
 * or polling.
 * @param <V> the result type
 * @param <A> the attachment type
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RecordingCompletionHandler<V, A> implements CompletionHandler<V, A> {

    private final CountDownLatch done = new CountDownLatch(1);
    private volatile V result;
    private volatile Throwable error;
    private volatile A attachment;
    private volatile Thread completedOn;

    @Override
    public void completed(V result, A attachment) {
        this.result = result;
        this.attachment = attachment;
        this.completedOn = Thread.currentThread();
        done.countDown();
    }

    @Override
    public void failed(Throwable error, A attachment) {
        this.error = error;
        this.attachment = attachment;
        this.completedOn = Thread.currentThread();
        done.countDown();
    }

    /**
     * Blocks until the operation completes or fails.
     * @return false if it did not finish within the timeout
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    public V getResult() {
        return result;
    }

    public Throwable getError() {
        return error;
    }

    public A getAttachment() {
        return attachment;
    }

    /** The thread on which the handler was invoked. */
    public Thread getCompletedOn() {
        return completedOn;
    }
}
