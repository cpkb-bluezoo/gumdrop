/*
 * H2FrameHandler.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.h2;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Callback interface for receiving parsed HTTP/2 frames.
 *
 * <p>Implementations receive frame data directly from an {@link H2Parser}
 * without intermediate Frame object allocation. Both server-side (HTTPConnection)
 * and client-side (HTTPClientConnection) connections implement this interface.
 *
 * <p>Each frame type has a dedicated callback method with the parsed fields
 * as parameters. ByteBuffer parameters are slices of the parser's input buffer
 * and should be consumed or copied before returning.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H2Parser
 */
public interface H2FrameHandler {

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Type Constants
    // ─────────────────────────────────────────────────────────────────────────

    int TYPE_DATA = 0x0;
    int TYPE_HEADERS = 0x1;
    int TYPE_PRIORITY = 0x2;
    int TYPE_RST_STREAM = 0x3;
    int TYPE_SETTINGS = 0x4;
    int TYPE_PUSH_PROMISE = 0x5;
    int TYPE_PING = 0x6;
    int TYPE_GOAWAY = 0x7;
    int TYPE_WINDOW_UPDATE = 0x8;
    int TYPE_CONTINUATION = 0x9;

    // ─────────────────────────────────────────────────────────────────────────
    // Flag Constants
    // ─────────────────────────────────────────────────────────────────────────

    int FLAG_ACK = 0x1;
    int FLAG_END_STREAM = 0x1;
    int FLAG_END_HEADERS = 0x4;
    int FLAG_PADDED = 0x8;
    int FLAG_PRIORITY = 0x20;

    // ─────────────────────────────────────────────────────────────────────────
    // Error Code Constants
    // ─────────────────────────────────────────────────────────────────────────

    int ERROR_NO_ERROR = 0x0;
    int ERROR_PROTOCOL_ERROR = 0x1;
    int ERROR_INTERNAL_ERROR = 0x2;
    int ERROR_FLOW_CONTROL_ERROR = 0x3;
    int ERROR_SETTINGS_TIMEOUT = 0x4;
    int ERROR_STREAM_CLOSED = 0x5;
    int ERROR_FRAME_SIZE_ERROR = 0x6;
    int ERROR_REFUSED_STREAM = 0x7;
    int ERROR_CANCEL = 0x8;
    int ERROR_COMPRESSION_ERROR = 0x9;
    int ERROR_CONNECT_ERROR = 0xa;
    int ERROR_ENHANCE_YOUR_CALM = 0xb;
    int ERROR_INADEQUATE_SECURITY = 0xc;
    int ERROR_HTTP_1_1_REQUIRED = 0xd;

    // ─────────────────────────────────────────────────────────────────────────
    // SETTINGS Parameter Constants
    // ─────────────────────────────────────────────────────────────────────────

    int SETTINGS_HEADER_TABLE_SIZE = 0x1;
    int SETTINGS_ENABLE_PUSH = 0x2;
    int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
    int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
    int SETTINGS_MAX_FRAME_SIZE = 0x5;
    int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Callbacks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when a DATA frame is received.
     *
     * @param streamId the stream identifier
     * @param endStream true if this is the last frame for the stream
     * @param data the data payload (a slice - consume or copy before returning)
     */
    void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data);

    /**
     * Called when a HEADERS frame is received.
     *
     * @param streamId the stream identifier
     * @param endStream true if this is the last frame for the stream
     * @param endHeaders true if this completes the header block
     * @param streamDependency the stream dependency (0 if no priority)
     * @param exclusive true for exclusive stream dependency
     * @param weight the priority weight (1-256, only valid if streamDependency > 0)
     * @param headerBlockFragment the HPACK-encoded header block (a slice)
     */
    void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
            int streamDependency, boolean exclusive, int weight,
            ByteBuffer headerBlockFragment);

    /**
     * Called when a PRIORITY frame is received.
     *
     * @param streamId the stream identifier
     * @param streamDependency the stream this one depends on
     * @param exclusive true for exclusive dependency
     * @param weight the priority weight (1-256)
     */
    void priorityFrameReceived(int streamId, int streamDependency,
            boolean exclusive, int weight);

    /**
     * Called when a RST_STREAM frame is received.
     *
     * @param streamId the stream identifier
     * @param errorCode the error code indicating why the stream is being terminated
     */
    void rstStreamFrameReceived(int streamId, int errorCode);

    /**
     * Called when a SETTINGS frame is received.
     *
     * @param ack true if this is an acknowledgement of our SETTINGS
     * @param settings map of setting identifier to value (empty if ack is true)
     */
    void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings);

    /**
     * Called when a PUSH_PROMISE frame is received.
     *
     * @param streamId the stream identifier of the associated request
     * @param promisedStreamId the stream identifier for the pushed response
     * @param endHeaders true if this completes the header block
     * @param headerBlockFragment the HPACK-encoded header block (a slice)
     */
    void pushPromiseFrameReceived(int streamId, int promisedStreamId,
            boolean endHeaders, ByteBuffer headerBlockFragment);

    /**
     * Called when a PING frame is received.
     *
     * @param ack true if this is a PING response
     * @param opaqueData the 8 bytes of opaque data
     */
    void pingFrameReceived(boolean ack, long opaqueData);

    /**
     * Called when a GOAWAY frame is received.
     *
     * @param lastStreamId the highest stream ID the sender will accept
     * @param errorCode the error code indicating why the connection is closing
     * @param debugData optional debug information (a slice, may be empty)
     */
    void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData);

    /**
     * Called when a WINDOW_UPDATE frame is received.
     *
     * @param streamId the stream identifier (0 for connection-level)
     * @param windowSizeIncrement the number of bytes to add to the window
     */
    void windowUpdateFrameReceived(int streamId, int windowSizeIncrement);

    /**
     * Called when a CONTINUATION frame is received.
     *
     * @param streamId the stream identifier
     * @param endHeaders true if this completes the header block
     * @param headerBlockFragment the HPACK-encoded header block (a slice)
     */
    void continuationFrameReceived(int streamId, boolean endHeaders,
            ByteBuffer headerBlockFragment);

    /**
     * Called when a protocol error is detected during frame parsing.
     *
     * @param errorCode the HTTP/2 error code
     * @param streamId the stream ID where the error occurred (0 for connection-level)
     * @param message a human-readable description of the error
     */
    void frameError(int errorCode, int streamId, String message);

    // ─────────────────────────────────────────────────────────────────────────
    // Utility Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable name for a frame type.
     *
     * @param type the frame type constant
     * @return the type name (e.g., "DATA", "HEADERS", etc.)
     */
    static String typeToString(int type) {
        switch (type) {
            case TYPE_DATA:
                return "DATA";
            case TYPE_HEADERS:
                return "HEADERS";
            case TYPE_PRIORITY:
                return "PRIORITY";
            case TYPE_RST_STREAM:
                return "RST_STREAM";
            case TYPE_SETTINGS:
                return "SETTINGS";
            case TYPE_PUSH_PROMISE:
                return "PUSH_PROMISE";
            case TYPE_PING:
                return "PING";
            case TYPE_GOAWAY:
                return "GOAWAY";
            case TYPE_WINDOW_UPDATE:
                return "WINDOW_UPDATE";
            case TYPE_CONTINUATION:
                return "CONTINUATION";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }

    /**
     * Returns a human-readable name for an error code.
     *
     * @param errorCode the error code constant
     * @return the error name (e.g., "NO_ERROR", "PROTOCOL_ERROR", etc.)
     */
    static String errorToString(int errorCode) {
        switch (errorCode) {
            case ERROR_NO_ERROR:
                return "NO_ERROR";
            case ERROR_PROTOCOL_ERROR:
                return "PROTOCOL_ERROR";
            case ERROR_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case ERROR_FLOW_CONTROL_ERROR:
                return "FLOW_CONTROL_ERROR";
            case ERROR_SETTINGS_TIMEOUT:
                return "SETTINGS_TIMEOUT";
            case ERROR_STREAM_CLOSED:
                return "STREAM_CLOSED";
            case ERROR_FRAME_SIZE_ERROR:
                return "FRAME_SIZE_ERROR";
            case ERROR_REFUSED_STREAM:
                return "REFUSED_STREAM";
            case ERROR_CANCEL:
                return "CANCEL";
            case ERROR_COMPRESSION_ERROR:
                return "COMPRESSION_ERROR";
            case ERROR_CONNECT_ERROR:
                return "CONNECT_ERROR";
            case ERROR_ENHANCE_YOUR_CALM:
                return "ENHANCE_YOUR_CALM";
            case ERROR_INADEQUATE_SECURITY:
                return "INADEQUATE_SECURITY";
            case ERROR_HTTP_1_1_REQUIRED:
                return "HTTP_1_1_REQUIRED";
            default:
                return "UNKNOWN(" + errorCode + ")";
        }
    }
}
