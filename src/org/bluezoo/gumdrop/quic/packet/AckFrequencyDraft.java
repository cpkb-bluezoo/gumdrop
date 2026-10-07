/*
 * AckFrequencyDraft.java
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

package org.bluezoo.gumdrop.quic.packet;

/**
 * The codepoints and limits of draft-ietf-quic-ack-frequency-14, kept in
 * one place so that a later revision of the draft, or an RFC, can replace
 * them without touching the code that uses them.
 *
 * @see <a href="https://www.ietf.org/archive/id/draft-ietf-quic-ack-frequency-14.html">draft-ietf-quic-ack-frequency-14</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AckFrequencyDraft {

    private AckFrequencyDraft() {
    }

    /** Section 3: the min_ack_delay transport parameter, in microseconds. */
    public static final long TRANSPORT_PARAMETER_MIN_ACK_DELAY = 0xff04de1bL;

    /** Section 4: the ACK_FREQUENCY frame type. */
    public static final long FRAME_TYPE_ACK_FREQUENCY = 0xafL;

    /** Section 5: the IMMEDIATE_ACK frame type. */
    public static final long FRAME_TYPE_IMMEDIATE_ACK = 0x1fL;

    /**
     * Section 4: a Requested Max Ack Delay of 2^14 milliseconds or more,
     * in microseconds, is a connection error.
     */
    public static final long REQUESTED_MAX_ACK_DELAY_LIMIT_MICROS = 16384L * 1000L;

    /** Section 3: the value, in microseconds, this endpoint advertises as its min_ack_delay. */
    public static final long LOCAL_MIN_ACK_DELAY_MICROS = 1000L;

    /** Section 4: the Ack-Eliciting Threshold in force before any ACK_FREQUENCY frame (RFC 9000 section 13.2.2). */
    public static final long DEFAULT_ACK_ELICITING_THRESHOLD = 1;

    /** Section 4: the Reordering Threshold in force before any ACK_FREQUENCY frame (RFC 9000 section 13.2.1). */
    public static final long DEFAULT_REORDERING_THRESHOLD = 1;
}
