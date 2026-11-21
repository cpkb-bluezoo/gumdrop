/*
 * ReplyFlow.java
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

package org.bluezoo.gumdrop.redis.codec;

/**
 * What a {@link RespEventHandler} tells the {@link RespDecoder} to do after
 * an event.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ReplyFlow {

    /** Go on. */
    CONTINUE,

    /**
     * Stop after this event. {@link RespDecoder#receive} returns true with
     * the bytes not yet consumed left in the caller's buffer, and the caller
     * stops reading until it is ready for more.
     */
    PAUSE,

    /**
     * The consumer wants no more of this reply. The rest of it is discarded
     * as it arrives, in constant memory, and the handler hears nothing more
     * of it except {@link RespEventHandler#endReply(boolean)} with
     * {@code skipped} true.
     */
    SKIP

}
