/*
 * CapturedCallback.java
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

package org.bluezoo.gumdrop.auth;

/**
 * Test callback that records the outcome of a {@link Realm} call which is
 * expected to complete inline (an in-memory realm), so a test can assert
 * on the result straight after the call returns.
 *
 * @param <T> the result type
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CapturedCallback<T> implements RealmCallback<T> {

    private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    private volatile int completions;
    private volatile T value;
    private volatile Throwable failure;

    @Override
    public void completed(T result) {
        completions++;
        value = result;
        latch.countDown();
    }

    @Override
    public void failed(Throwable cause) {
        completions++;
        failure = cause;
        latch.countDown();
    }

    /**
     * Waits for a callback that completes on another thread. The timeout only
     * turns a hang into a failure.
     */
    public boolean awaitDone(long timeoutMs) throws InterruptedException {
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Whether the callback has fired (exactly once is enforced by {@link #get}). */
    public boolean isDone() {
        return completions > 0;
    }

    /** The completed value (null if not completed or failed). */
    public T value() {
        return value;
    }

    /** The failure, or null. */
    public Throwable failure() {
        return failure;
    }

    /**
     * Returns the completed value, requiring that the callback fired exactly
     * once and did not fail.
     */
    public T get() {
        if (completions != 1) {
            throw new AssertionError("callback fired " + completions + " times");
        }
        if (failure != null) {
            throw new AssertionError("callback failed: " + failure, failure);
        }
        return value;
    }

    /** Runs the realm call that reports to a fresh callback and returns its value. */
    public interface Call<R> {
        void invoke(RealmCallback<R> callback);
    }

    /** Invokes {@code call} with a fresh callback and returns the single result. */
    public static <R> R await(Call<R> call) {
        CapturedCallback<R> cb = new CapturedCallback<R>();
        call.invoke(cb);
        return cb.get();
    }
}
