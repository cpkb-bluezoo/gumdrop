/*
 * RealmCallback.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
 * Receives the outcome of an asynchronous {@link Realm} operation.
 *
 * <p>Exactly one of the two methods is called, exactly once. A realm bound to
 * a loop (see {@link Realm#forSelectorLoop}) calls it on that loop's thread;
 * a realm whose answer is already in memory may call it before the operation
 * returns, so the caller must record that it is waiting <em>before</em> it
 * starts the operation.
 *
 * @param <T> the type of the answer
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface RealmCallback<T> {

    /**
     * The realm answered. A negative answer (a wrong password, an unknown
     * user) is still an answer and is delivered here, not to
     * {@link #failed}.
     *
     * @param result the answer; null where the operation says so
     */
    void completed(T result);

    /**
     * The realm could not answer: a directory server was unreachable, a
     * request timed out, or the operation is unsupported. Callers must not
     * treat this as a successful authentication or authorisation: the usual
     * response is to refuse and, where the protocol has one, report a
     * temporary failure.
     *
     * @param cause what went wrong
     */
    void failed(Throwable cause);
}
