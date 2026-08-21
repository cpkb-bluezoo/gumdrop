/*
 * Rfc9218NonIncrementalSlots.java
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

package org.bluezoo.gumdrop.http;

/**
 * Per-urgency exclusive slot for non-incremental responses (RFC 9218
 * sections 4.2 and 10). Incremental streams always proceed; non-incremental
 * streams of the same urgency are served one at a time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Rfc9218NonIncrementalSlots {

    private static final long EMPTY = -1L;

    private final long[] active = new long[] {
            EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY
    };

    /**
     * Attempts to claim the send slot for {@code streamId}. Incremental
     * streams always succeed. A non-incremental stream succeeds if the
     * slot is free or already held by this stream.
     *
     * @param streamId the request stream
     * @param params the stream's current priority
     * @return true if this stream may send DATA now
     */
    public boolean claim(long streamId, PriorityParams params) {
        if (params.isIncremental()) {
            return true;
        }
        int urgency = params.getUrgency();
        long holder = active[urgency];
        if (holder == EMPTY) {
            active[urgency] = streamId;
            return true;
        }
        return holder == streamId;
    }

    /**
     * Releases the slot if this stream holds it.
     *
     * @param streamId the request stream
     * @param params the stream's current priority
     */
    public void release(long streamId, PriorityParams params) {
        if (params.isIncremental()) {
            return;
        }
        int urgency = params.getUrgency();
        if (active[urgency] == streamId) {
            active[urgency] = EMPTY;
        }
    }
}
