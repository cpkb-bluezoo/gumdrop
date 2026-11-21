/*
 * ReplyStream.java
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
 * A handle on the reply being streamed to a handler, given to it when the
 * reply begins. A handler that returns
 * {@link org.bluezoo.gumdrop.redis.codec.ReplyFlow#PAUSE} stops all reading
 * on the connection until it calls {@link #resume()}; while it waits, the
 * connection costs no more memory however much the server has to send, and
 * the command's timeout and the connection's health check do not run against
 * it.
 *
 * <p>Both methods may be called from any thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ReplyStream {

    /**
     * A stream that does nothing, given with replies the session answers
     * itself.
     */
    ReplyStream NONE = new ReplyStream() {
        @Override
        public void resume() {
        }

        @Override
        public void cancel() {
        }
    };

    /**
     * Resumes a reply that was paused. Does nothing if it was not.
     */
    void resume();

    /**
     * Gives up the rest of the reply. It is discarded as it arrives, in
     * constant memory, the connection survives, and the handler's completion
     * callback gets a {@code CANCELLED} error. If the reply was paused it is
     * resumed in order to be discarded.
     */
    void cancel();

}
