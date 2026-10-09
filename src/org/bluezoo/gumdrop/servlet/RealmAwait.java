/*
 * RealmAwait.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;

/**
 * Turns an asynchronous {@link org.bluezoo.gumdrop.auth.Realm} call into a
 * blocking one for the Servlet API.
 *
 * <p>The Servlet API's authentication and role methods
 * ({@code isUserInRole}, {@code login}, {@code authenticate}) have to return
 * their answer to the caller, so a servlet worker waits here until the realm
 * calls back. That is the only place in Gumdrop where a realm is waited for,
 * and it is safe because the waiting thread is a servlet worker, never a
 * selector loop: the realm does its work, and calls back, on a loop.
 *
 * @param <T> the type of the realm's answer
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RealmAwait<T> implements RealmCallback<T>,
        HttpAuthenticationProvider.AuthenticationCallback {

    /** How long a servlet worker waits for a realm before giving up. */
    static final long TIMEOUT_SECONDS = 120L;

    private final CountDownLatch done = new CountDownLatch(1);
    private T result;
    private Throwable failure;

    @Override
    public void completed(T answer) {
        result = answer;
        done.countDown();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void completed(HttpAuthenticationProvider.AuthenticationResult answer) {
        completed((T) answer);
    }

    @Override
    public void failed(Throwable cause) {
        failure = cause;
        done.countDown();
    }

    /**
     * Waits for the realm to answer.
     *
     * @return the answer
     * @throws IllegalStateException if the realm failed, did not answer in
     *         time, or the thread was interrupted
     */
    T await() {
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the realm did not answer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the realm", e);
        }
        if (failure != null) {
            throw new IllegalStateException("the realm could not answer", failure);
        }
        return result;
    }
}
