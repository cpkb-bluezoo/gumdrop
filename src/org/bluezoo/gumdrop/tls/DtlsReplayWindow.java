/*
 * DtlsReplayWindow.java
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

package org.bluezoo.gumdrop.tls;

/**
 * RFC 6347 section 4.1.2.5 sliding replay window for one epoch. The window
 * only advances after AEAD verification succeeds ({@link #recordAccepted}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DtlsReplayWindow {

    private static final int WINDOW_BITS = 64;

    private long highestSeq = -1L;
    private long bitmask;

    /**
     * Cheap pre-decrypt filter -- a forged record must never burn a slot.
     *
     * @param combinedSeq {@code epoch << 48 | sequence_number}
     * @return true if the record may be worth decrypting
     */
    public boolean mayAccept(long combinedSeq) {
        if (highestSeq < 0) {
            return true;
        }
        if (combinedSeq > highestSeq) {
            return true;
        }
        long delta = highestSeq - combinedSeq;
        if (delta >= WINDOW_BITS) {
            return false;
        }
        return (bitmask & (1L << delta)) == 0L;
    }

    /**
     * Marks a verified record as accepted and advances the window.
     *
     * @param combinedSeq {@code epoch << 48 | sequence_number}
     */
    /**
     * Returns the highest combined sequence number accepted so far, or
     * {@code -1} if none.
     *
     * @return the highest accepted combined sequence number
     */
    public long highestAccepted() {
        return highestSeq;
    }

    public void recordAccepted(long combinedSeq) {
        if (highestSeq < 0) {
            highestSeq = combinedSeq;
            bitmask = 1L;
            return;
        }
        if (combinedSeq > highestSeq) {
            long shift = combinedSeq - highestSeq;
            if (shift >= WINDOW_BITS) {
                bitmask = 0L;
            } else {
                bitmask <<= (int) shift;
            }
            highestSeq = combinedSeq;
            bitmask |= 1L;
            return;
        }
        long delta = highestSeq - combinedSeq;
        if (delta < WINDOW_BITS) {
            bitmask |= (1L << delta);
        }
    }

}
