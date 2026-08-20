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
    long TYPE_RESET_STREAM = 0x04;      // RFC 9000 section 19.4
    long TYPE_STOP_SENDING = 0x05;      // RFC 9000 section 19.5
    long TYPE_CRYPTO = 0x06;            // RFC 9000 section 19.6
    long TYPE_NEW_TOKEN = 0x07;         // RFC 9000 section 19.7
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
    long TYPE_NEW_CONNECTION_ID = 0x18;    // RFC 9000 section 19.15
    long TYPE_RETIRE_CONNECTION_ID = 0x19; // RFC 9000 section 19.16
    long TYPE_PATH_CHALLENGE = 0x1a;    // RFC 9000 section 19.17
    long TYPE_PATH_RESPONSE = 0x1b;     // RFC 9000 section 19.18
    long TYPE_CONNECTION_CLOSE = 0x1c;  // RFC 9000 section 19.19, transport error
    long TYPE_CONNECTION_CLOSE_APP = 0x1d; // RFC 9000 section 19.19, application error
    long TYPE_HANDSHAKE_DONE = 0x1e;    // RFC 9000 section 19.20

    /** RFC 9000 section 19.15: the fixed length of the Stateless Reset Token field, in bytes. */
    int STATELESS_RESET_TOKEN_LENGTH = 16;

    /** RFC 9000 sections 19.17/19.18: the fixed length of the Data field, in bytes. */
    int PATH_DATA_LENGTH = 8;

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
     * Called when an ACK frame is received (RFC 9000 section 19.3),
     * with every acknowledged range resolved from the wire's Gap/ACK
     * Range Length chain (section 19.3.1). ECN counts are parsed, for
     * correctness of frame boundary detection, but not yet surfaced
     * here.
     *
     * @param largestAcknowledged the largest packet number being acknowledged
     * @param ackDelay the ACK Delay field, in the sender's declared units
     * @param ranges every acknowledged packet number range, as
     *               {@code {low, high}} pairs inclusive of both ends,
     *               in descending order -- {@code ranges[0]} is the
     *               highest range and its {@code high} equals
     *               {@code largestAcknowledged}
     */
    void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges);

    /**
     * Called when a RESET_STREAM frame is received (RFC 9000 section 19.4),
     * abruptly terminating the peer's sending part of a stream.
     *
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the reset
     * @param finalSize the total number of bytes the peer sent on the stream
     */
    void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize);

    /**
     * Called when a STOP_SENDING frame is received (RFC 9000 section 19.5),
     * requesting that this side abruptly terminate its sending part of a stream.
     *
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the request
     */
    void stopSendingFrameReceived(long streamId, long applicationErrorCode);

    /**
     * Called when a CRYPTO frame is received (RFC 9000 section 19.6).
     *
     * @param offset the byte offset of this data within the CRYPTO stream
     *               for the packet's encryption level
     * @param data the handshake data (a slice - consume or copy before returning)
     */
    void cryptoFrameReceived(long offset, ByteBuffer data);

    /**
     * Called when a NEW_TOKEN frame is received (RFC 9000 section 19.7).
     * Server-to-client only, carrying a token the client may present on a
     * future connection's Initial packet.
     *
     * @param token the opaque token bytes (a slice - consume or copy before returning)
     */
    void newTokenFrameReceived(ByteBuffer token);

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
     * Called when a NEW_CONNECTION_ID frame is received (RFC 9000
     * section 19.15), offering an additional connection ID this side may
     * use as the Destination Connection ID on future packets.
     *
     * @param sequenceNumber the new connection ID's sequence number
     * @param retirePriorTo connection IDs with a lower sequence number
     *                      than this must be retired
     * @param connectionId the new connection ID bytes (a slice - consume
     *                     or copy before returning)
     * @param statelessResetToken the associated stateless reset token,
     *                            always {@link #STATELESS_RESET_TOKEN_LENGTH}
     *                            bytes (a slice - consume or copy before returning)
     */
    void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
            ByteBuffer connectionId, ByteBuffer statelessResetToken);

    /**
     * Called when a RETIRE_CONNECTION_ID frame is received (RFC 9000
     * section 19.16): the peer will no longer use the connection ID with
     * this sequence number as a Destination Connection ID.
     *
     * @param sequenceNumber the sequence number being retired
     */
    void retireConnectionIdFrameReceived(long sequenceNumber);

    /**
     * Called when a PATH_CHALLENGE frame is received (RFC 9000 section
     * 19.17), requiring the same data to be echoed back in a
     * PATH_RESPONSE frame on the path the challenge arrived on.
     *
     * @param data the challenge data, always {@link #PATH_DATA_LENGTH}
     *             bytes (a slice - consume or copy before returning)
     */
    void pathChallengeFrameReceived(ByteBuffer data);

    /**
     * Called when a PATH_RESPONSE frame is received (RFC 9000 section
     * 19.18), echoing data from a previously sent PATH_CHALLENGE.
     *
     * @param data the response data, always {@link #PATH_DATA_LENGTH}
     *             bytes (a slice - consume or copy before returning)
     */
    void pathResponseFrameReceived(ByteBuffer data);

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
