/*
 * H3FrameHandler.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;

/**
 * Callback interface for receiving parsed HTTP/3 frames from an
 * {@link H3Parser}, one instance per QUIC stream (or, for
 * {@link #TYPE_SETTINGS}, per control stream) -- the HTTP/3 analogue of
 * {@code org.bluezoo.gumdrop.http.h2.H2FrameHandler} for HTTP/2.
 *
 * <p>Unlike H2Parser, whose caller decodes HPACK itself from the raw
 * header block fragment it hands back, {@link #headersFrameReceived}
 * here likewise hands back the raw QPACK-encoded field section -- QPACK
 * decoding is not this package's concern, matching how
 * {@code org.bluezoo.gumdrop.http.hpack} and {@code http.h2} are kept
 * separate.
 *
 * <p>Server push is declined ({@code H3Stream#pushPromise} returns
 * false; no {@code MAX_PUSH_ID} is sent). Unpermitted
 * {@link #TYPE_PUSH_PROMISE}, {@link #TYPE_CANCEL_PUSH}, and
 * {@link #TYPE_MAX_PUSH_ID} frames are connection errors as RFC 9114
 * requires, not silently ignored.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3Parser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-7.2">RFC 9114 section 7.2</a>
 */
public interface H3FrameHandler {

    // ─────────────────────────────────────────────────────────────────────────
    // RFC 9114 section 7.2: Frame Type Constants
    // ─────────────────────────────────────────────────────────────────────────

    long TYPE_DATA = 0x00;         // RFC 9114 section 7.2.1
    long TYPE_HEADERS = 0x01;      // RFC 9114 section 7.2.2
    long TYPE_CANCEL_PUSH = 0x03;  // RFC 9114 section 7.2.3
    long TYPE_SETTINGS = 0x04;     // RFC 9114 section 7.2.4
    long TYPE_PUSH_PROMISE = 0x05; // RFC 9114 section 7.2.5
    long TYPE_GOAWAY = 0x07;       // RFC 9114 section 7.2.6
    long TYPE_MAX_PUSH_ID = 0x0d;  // RFC 9114 section 7.2.7

    // ─────────────────────────────────────────────────────────────────────────
    // RFC 9114 section 7.2.4.1: Defined SETTINGS Parameters
    // ─────────────────────────────────────────────────────────────────────────

    /** RFC 9114 section 7.2.4.1. */
    long SETTINGS_MAX_FIELD_SECTION_SIZE = 0x06;
    /** RFC 9204 section 5 (QPACK). */
    long SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0x01;
    /** RFC 9204 section 5 (QPACK). */
    long SETTINGS_QPACK_BLOCKED_STREAMS = 0x07;
    /** RFC 9220 section 2 (Extended CONNECT / WebSocket). */
    long SETTINGS_ENABLE_CONNECT_PROTOCOL = 0x08;

    /** RFC 9218 section 7.2: PRIORITY_UPDATE for a request stream. */
    long TYPE_PRIORITY_UPDATE_REQUEST = 0xF0700;
    /** RFC 9218 section 7.2: PRIORITY_UPDATE for a push stream. */
    long TYPE_PRIORITY_UPDATE_PUSH = 0xF0701;

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called with a chunk of a DATA frame's payload (RFC 9114 section
     * 7.2.1). Unlike the other frame callbacks, this may be called
     * multiple times for a single frame -- HTTP/3 request/response
     * bodies are not bounded in size the way HPACK header blocks are,
     * so DATA payload is delivered incrementally as it arrives rather
     * than buffered in full first.
     *
     * @param data a chunk of the payload (a slice - consume or copy before returning)
     * @param endOfFrame true if this chunk completes the DATA frame
     */
    void dataFrameReceived(ByteBuffer data, boolean endOfFrame);

    /**
     * Called when a complete HEADERS frame is received (RFC 9114
     * section 7.2.2).
     *
     * @param encodedFieldSection the QPACK-encoded field section (a
     *                            slice - consume or copy before returning)
     */
    void headersFrameReceived(ByteBuffer encodedFieldSection);

    /**
     * Called when a complete CANCEL_PUSH frame is received (RFC 9114
     * section 7.2.3). Control-stream only.
     *
     * @param pushId the push ID being cancelled
     */
    void cancelPushFrameReceived(long pushId);

    /**
     * Called when a complete SETTINGS frame is received (RFC 9114
     * section 7.2.4). Control-stream only, and only as the first frame.
     *
     * @param settings the setting identifier/value pairs, in wire order
     */
    void settingsFrameReceived(long[] settings);

    /**
     * Called when a complete PUSH_PROMISE frame is received (RFC 9114
     * section 7.2.5). Request-stream only.
     *
     * @param pushId the push ID being promised
     * @param encodedFieldSection the QPACK-encoded promised request
     *                            field section (a slice - consume or
     *                            copy before returning)
     */
    void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection);

    /**
     * Called when a complete GOAWAY frame is received (RFC 9114 section
     * 7.2.6). Control-stream only.
     *
     * @param streamOrPushId a client-initiated bidirectional stream ID
     *                       (server-to-client direction) or a push ID
     *                       (client-to-server direction)
     */
    void goawayFrameReceived(long streamOrPushId);

    /**
     * Called when a complete MAX_PUSH_ID frame is received (RFC 9114
     * section 7.2.7). Control-stream only, client-to-server only.
     *
     * @param maxPushId the new maximum push ID the server may use
     */
    void maxPushIdFrameReceived(long maxPushId);

    /**
     * Called when a complete PRIORITY_UPDATE frame for a request stream
     * is received (RFC 9218 section 7.2). Control-stream only.
     *
     * @param streamId the client-initiated bidirectional stream ID
     * @param fieldValue the Priority Field Value
     */
    void priorityUpdateRequestFrameReceived(long streamId, String fieldValue);

    /**
     * Called when a complete PRIORITY_UPDATE frame for a push stream is
     * received (RFC 9218 section 7.2). Control-stream only.
     *
     * @param pushId the push ID
     * @param fieldValue the Priority Field Value
     */
    void priorityUpdatePushFrameReceived(long pushId, String fieldValue);

    /**
     * Called when a frame cannot be parsed.
     *
     * @param message a human-readable description of the error
     */
    void frameError(String message);
}
