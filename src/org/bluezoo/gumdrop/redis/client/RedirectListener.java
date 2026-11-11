/*
 * RedirectListener.java
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

package org.bluezoo.gumdrop.redis.client;

/**
 * Offered every error reply before it reaches the command's result handler,
 * so that a cluster session can act on {@code MOVED} and {@code ASK}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface RedirectListener {

    /**
     * Called with an error reply and the command that provoked it.
     *
     * @param command the command, as sent
     * @param error the error message, such as {@code MOVED 3999 127.0.0.1:6381}
     * @return true if the listener has taken over the command, in which case
     *         the result handler is not told; false to deliver the error
     *         to it as usual
     */
    boolean handleError(SentCommand command, String error);

    /**
     * Called when the connection has gone, with the commands that had been
     * sent on it and not yet answered. Their result handlers are not told
     * by the connection; the listener is expected to tell them.
     *
     * @param commands the unanswered commands, oldest first
     */
    default void connectionLost(java.util.List<SentCommand> commands) {
    }
}
