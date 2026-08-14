/*
 * QuicFrameHandler.java
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

package org.bluezoo.gumdrop.quic.frame;

import java.nio.ByteBuffer;

/**
 * Callback interface for receiving parsed QUIC frames from a
 * {@link QuicFrameParser}, in the same style as
 * {@code org.bluezoo.gumdrop.http.h2.H2FrameHandler} for HTTP/2.
 *
 * <p>Only the frame types listed in the {@code TYPE_*} constants are
 * currently understood; see the package documentation for what is
 * still missing (connection ID management, PATH_CHALLENGE/RESPONSE).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicFrameParser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19">RFC 9000 section 19</a>
 */
public interface QuicFrameHandler {

    // ─────────────────────────────────────────────────────────────────────────
    // RFC 9000 section 19: Frame Type Constants
    // ─────────────────────────────────────────────────────────────────────────

    long TYPE_PADDING = 0x00;           // RFC 9000 section 19.1
    long TYPE_PING = 0x01;              // RFC 9000 section 19.2
    long TYPE_ACK = 0x02;               // RFC 9000 section 19.3
    long TYPE_ACK_ECN = 0x03;           // RFC 9000 section 19.3, with ECN counts
    long TYPE_CRYPTO = 0x06;            // RFC 9000 section 19.6
    long TYPE_STREAM_MIN = 0x08;        // RFC 9000 section 19.8, low 3 bits are OFF/LEN/FIN
    long TYPE_STREAM_MAX = 0x0f;
    long TYPE_MAX_DATA = 0x10;          // RFC 9000 section 19.9
    long TYPE_MAX_STREAM_DATA = 0x11;   // RFC 9000 section 19.10
    long TYPE_MAX_STREAMS_BIDI = 0x12;  // RFC 9000 section 19.11
    long TYPE_MAX_STREAMS_UNI = 0x13;   // RFC 9000 section 19.11
    long TYPE_DATA_BLOCKED = 0x14;      // RFC 9000 section 19.12
    long TYPE_STREAM_DATA_BLOCKED = 0x15; // RFC 9000 section 19.13
    long TYPE_STREAMS_BLOCKED_BIDI = 0x16; // RFC 9000 section 19.14
    long TYPE_STREAMS_BLOCKED_UNI = 0x17;  // RFC 9000 section 19.14
    long TYPE_CONNECTION_CLOSE = 0x1c;  // RFC 9000 section 19.19, transport error
    long TYPE_CONNECTION_CLOSE_APP = 0x1d; // RFC 9000 section 19.19, application error
    long TYPE_HANDSHAKE_DONE = 0x1e;    // RFC 9000 section 19.20

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called for a run of one or more consecutive PADDING frames
     * (RFC 9000 section 19.1), coalesced into a single callback.
     *
     * @param length the number of consecutive PADDING frame bytes
     */
    void paddingFrameReceived(int length);

    /**
     * Called when a PING frame is received (RFC 9000 section 19.2).
     */
    void pingFrameReceived();

    /**
     * Called when an ACK frame is received (RFC 9000 section 19.3).
     *
     * <p>Only the largest acknowledged packet number and the first
     * (highest) ACK range are reported; additional ACK ranges (gaps)
     * and ECN counts are parsed, for correctness of frame boundary
     * detection, but not yet surfaced here.
     *
     * @param largestAcknowledged the largest packet number being acknowledged
     * @param ackDelay the ACK Delay field, in the sender's declared units
     * @param firstAckRange the number of contiguous packets below
     *                      {@code largestAcknowledged} also being acknowledged
     */
    void ackFrameReceived(long largestAcknowledged, long ackDelay, long firstAckRange);

    /**
     * Called when a CRYPTO frame is received (RFC 9000 section 19.6).
     *
     * @param offset the byte offset of this data within the CRYPTO stream
     *               for the packet's encryption level
     * @param data the handshake data (a slice - consume or copy before returning)
     */
    void cryptoFrameReceived(long offset, ByteBuffer data);

    /**
     * Called when a STREAM frame is received (RFC 9000 section 19.8).
     *
     * @param streamId the stream identifier
     * @param offset the byte offset of this data within the stream
     * @param fin true if this frame marks the end of the stream
     * @param data the stream data (a slice - consume or copy before returning)
     */
    void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data);

    /**
     * Called when a MAX_DATA frame is received (RFC 9000 section 19.9).
     *
     * @param maximumData the new connection-level send limit
     */
    void maxDataFrameReceived(long maximumData);

    /**
     * Called when a MAX_STREAM_DATA frame is received (RFC 9000 section 19.10).
     *
     * @param streamId the affected stream
     * @param maximumStreamData the new stream-level send limit
     */
    void maxStreamDataFrameReceived(long streamId, long maximumStreamData);

    /**
     * Called when a MAX_STREAMS frame is received (RFC 9000 section 19.11).
     *
     * @param bidirectional true for {@link #TYPE_MAX_STREAMS_BIDI}, false for
     *                      {@link #TYPE_MAX_STREAMS_UNI}
     * @param maximumStreams the new cumulative stream limit
     */
    void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams);

    /**
     * Called when a DATA_BLOCKED frame is received (RFC 9000 section 19.12).
     *
     * @param maximumData the connection-level limit at which the peer was blocked
     */
    void dataBlockedFrameReceived(long maximumData);

    /**
     * Called when a STREAM_DATA_BLOCKED frame is received (RFC 9000 section 19.13).
     *
     * @param streamId the blocked stream
     * @param maximumStreamData the stream-level limit at which the peer was blocked
     */
    void streamDataBlockedFrameReceived(long streamId, long maximumStreamData);

    /**
     * Called when a STREAMS_BLOCKED frame is received (RFC 9000 section 19.14).
     *
     * @param bidirectional true for {@link #TYPE_STREAMS_BLOCKED_BIDI}, false
     *                      for {@link #TYPE_STREAMS_BLOCKED_UNI}
     * @param maximumStreams the stream limit at which the peer was blocked
     */
    void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams);

    /**
     * Called when a CONNECTION_CLOSE frame is received (RFC 9000
     * section 19.19), for either a transport or an application error.
     *
     * @param applicationError true if this is an application-level
     *                         close ({@link #TYPE_CONNECTION_CLOSE_APP}),
     *                         false for a transport-level close
     * @param errorCode the error code
     * @param frameType for a transport-level close, the frame type that
     *                  triggered the error (0 if not applicable)
     * @param reason the human-readable reason phrase, possibly empty
     */
    void connectionCloseFrameReceived(boolean applicationError, long errorCode,
            long frameType, String reason);

    /**
     * Called when a HANDSHAKE_DONE frame is received (RFC 9000
     * section 19.20). Server-only; a client that receives this
     * confirms handshake completion (RFC 9001 section 4.1.1).
     */
    void handshakeDoneFrameReceived();

    /**
     * Called when a frame cannot be parsed, or is a type not
     * implemented by this parser.
     *
     * @param message a human-readable description of the error
     */
    void frameError(String message);
}
