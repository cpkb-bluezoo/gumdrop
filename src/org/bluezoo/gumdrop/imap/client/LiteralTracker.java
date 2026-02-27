/*
 * LiteralTracker.java
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

package org.bluezoo.gumdrop.imap.client;

import java.nio.ByteBuffer;

/**
 * Tracks incoming literal byte data during IMAP FETCH responses.
 * Pure byte-counting: delivers content chunks via a callback and
 * signals completion when the expected number of bytes has been
 * consumed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class LiteralTracker {

    interface Callback {
        void literalContent(ByteBuffer data);
        void literalComplete();
    }

    private long remaining;
    private final Callback callback;

    LiteralTracker(long size, Callback callback) {
        this.remaining = size;
        this.callback = callback;
    }

    long getRemaining() {
        return remaining;
    }

    boolean isComplete() {
        return remaining <= 0;
    }

    /**
     * Processes incoming data against the literal byte count.
     * Delivers available bytes to the callback up to the remaining
     * count. Returns true if the literal is now complete.
     *
     * @param input the incoming data buffer
     * @return true if the literal has been fully consumed
     */
    boolean process(ByteBuffer input) {
        if (remaining <= 0) {
            return true;
        }

        int available = input.remaining();
        if (available <= 0) {
            return false;
        }

        if (available <= remaining) {
            remaining -= available;
            callback.literalContent(input.slice());
            input.position(input.limit());
        } else {
            int savedLimit = input.limit();
            input.limit(input.position() + (int) remaining);
            callback.literalContent(input.slice());
            input.position(input.limit());
            input.limit(savedLimit);
            remaining = 0;
        }

        if (remaining <= 0) {
            callback.literalComplete();
            return true;
        }
        return false;
    }
}
