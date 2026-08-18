/*
 * H3ControlStream.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Handles one peer-initiated HTTP/3 unidirectional stream (RFC 9114
 * section 6.2).
 *
 * <p>An instance of this class is returned for every new peer-initiated
 * unidirectional stream by {@link HTTP3ServerHandler}'s/
 * {@link HTTP3ClientHandler}'s {@code unidirectionalStreamAcceptHandler}
 * -- the stream's type (a leading varint, RFC 9114 section 6.2) isn't
 * known until its first byte arrives, so a single generic handler
 * fields every peer-initiated uni stream and dispatches once the type
 * is read. For {@link #STREAM_TYPE_CONTROL}, an {@link H3Parser} is
 * wired up, expecting SETTINGS as the first frame (RFC 9114 section
 * 7.2.4) and GOAWAY thereafter (section 5.2); for any other type --
 * including the QPACK encoder/decoder streams (RFC 9204 section 4.2) a
 * peer may still open despite this implementation always advertising
 * {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} 0 -- all further bytes are
 * discarded, per RFC 9114 section 9's tolerance requirement for unknown
 * unidirectional stream types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class H3ControlStream implements ProtocolHandler, H3FrameHandler {

    private static final Logger LOGGER = Logger.getLogger(H3ControlStream.class.getName());

    /** RFC 9114 section 6.2.1. */
    private static final long STREAM_TYPE_CONTROL = 0x00;

    /** RFC 9114 section 8.1: a frame was received that was not permitted in the current state. */
    private static final long H3_FRAME_UNEXPECTED = 0x105;

    /**
     * Notified of the meaningful events on the peer's control stream.
     */
    interface Listener {

        /**
         * Called when the peer's SETTINGS frame is received (RFC 9114
         * section 7.2.4).
         *
         * @param settings the setting identifier/value pairs, in wire order
         */
        void settingsReceived(long[] settings);

        /**
         * Called when the peer's GOAWAY frame is received (RFC 9114
         * section 5.2).
         *
         * @param streamOrPushId the ID beyond which the peer will not
         *                       process further requests/pushes
         */
        void goawayReceived(long streamOrPushId);
    }

    private final QuicConnection quicConnection;
    private final Listener listener;

    private int typeBytesNeeded = -1;
    private final ByteArrayOutputStream typeBuffer = new ByteArrayOutputStream(8);
    private boolean typeKnown;
    private boolean isControlStream;
    private H3Parser parser;

    H3ControlStream(QuicConnection quicConnection, Listener listener) {
        this.quicConnection = quicConnection;
        this.listener = listener;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint endpoint) {
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void receive(ByteBuffer data) {
        if (!typeKnown) {
            if (!readStreamType(data)) {
                return;
            }
            typeKnown = true;
            if (isControlStream) {
                parser = new H3Parser(this);
            }
        }
        if (isControlStream) {
            parser.receive(data);
        } else {
            // RFC 9114 section 9: tolerate and discard unrecognised
            // unidirectional stream types.
            data.position(data.limit());
        }
    }

    /**
     * Reads the stream type varint (RFC 9114 section 6.2), buffering
     * across calls if it arrives split.
     *
     * @return true once the type is known
     */
    private boolean readStreamType(ByteBuffer buf) {
        if (typeBytesNeeded < 0) {
            if (!buf.hasRemaining()) {
                return false;
            }
            typeBytesNeeded = VarInt.peekEncodedLength(buf, buf.position());
        }
        int stillNeeded = typeBytesNeeded - typeBuffer.size();
        int available = Math.min(stillNeeded, buf.remaining());
        byte[] bytes = new byte[available];
        buf.get(bytes);
        typeBuffer.write(bytes, 0, bytes.length);
        if (typeBuffer.size() < typeBytesNeeded) {
            return false;
        }
        long streamType = VarInt.decode(ByteBuffer.wrap(typeBuffer.toByteArray()));
        isControlStream = (streamType == STREAM_TYPE_CONTROL);
        return true;
    }

    @Override
    public void disconnected() {
    }

    @Override
    public void error(Exception cause) {
        LOGGER.warning("HTTP/3 control stream error: " + cause);
    }

    // ── H3FrameHandler ──

    @Override
    public void dataFrameReceived(ByteBuffer data, boolean endOfFrame) {
        frameError("DATA frame is not valid on the control stream");
    }

    @Override
    public void headersFrameReceived(ByteBuffer encodedFieldSection) {
        frameError("HEADERS frame is not valid on the control stream");
    }

    @Override
    public void cancelPushFrameReceived(long pushId) {
    }

    @Override
    public void settingsFrameReceived(long[] settings) {
        listener.settingsReceived(settings);
    }

    @Override
    public void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection) {
        frameError("PUSH_PROMISE frame is not valid on the control stream");
    }

    @Override
    public void goawayFrameReceived(long streamOrPushId) {
        listener.goawayReceived(streamOrPushId);
    }

    @Override
    public void maxPushIdFrameReceived(long maxPushId) {
    }

    @Override
    public void frameError(String message) {
        LOGGER.warning("HTTP/3 control stream error: " + message);
        // RFC 9114 section 8.1: a fatal framing violation on the control
        // stream is a connection error, not just a stream error -- unlike
        // H3Stream#frameError, which can cancel just the one offending
        // request stream, a malformed frame here can desynchronize the
        // whole connection's control-stream state (e.g. a SETTINGS frame
        // sent somewhere other than first), so the entire connection is
        // closed rather than merely discarding this stream.
        quicConnection.closeWithApplicationError(H3_FRAME_UNEXPECTED, message);
    }
}
