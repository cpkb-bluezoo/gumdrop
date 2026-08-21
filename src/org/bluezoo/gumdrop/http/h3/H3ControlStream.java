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
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
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
 * is read:
 * <ul>
 * <li>{@link #STREAM_TYPE_CONTROL}: an {@link H3Parser} is wired up,
 * expecting SETTINGS as the first frame (RFC 9114 section 7.2.4) and
 * GOAWAY thereafter (section 5.2).</li>
 * <li>{@link #STREAM_TYPE_QPACK_ENCODER} (RFC 9204 section 4.2): the
 * peer's QPACK encoder instructions, fed into this connection's own
 * {@link Decoder} via {@link Decoder#feedEncoderStream}; a rejected
 * instruction (RFC 9204 section 3.2.3, e.g. a capacity exceeding our
 * declared maximum) closes the connection with {@code
 * QPACK_ENCODER_STREAM_ERROR}.</li>
 * <li>{@link #STREAM_TYPE_QPACK_DECODER}: the peer's QPACK decoder
 * instructions (Section Acknowledgment/Stream Cancellation/Insert Count
 * Increment), fed into this connection's own {@link Encoder} via {@link
 * Encoder#feedDecoderStream} -- these are all bare integers with no
 * distinct malformed-content case, so there is nothing to check
 * afterwards.</li>
 * <li>{@link #STREAM_TYPE_PUSH}: gumdrop never permits push, so a
 * client treats this as {@link H3ErrorCode#H3_ID_ERROR} (RFC 9114
 * section 4.6) and a server treats it as
 * {@link H3ErrorCode#H3_STREAM_CREATION_ERROR}.</li>
 * <li>Any other type: all further bytes are discarded, per RFC 9114
 * section 9's tolerance requirement for unknown unidirectional stream
 * types.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class H3ControlStream implements ProtocolHandler, H3FrameHandler {

    private static final Logger LOGGER = Logger.getLogger(H3ControlStream.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    /** RFC 9114 section 6.2.1. */
    private static final long STREAM_TYPE_CONTROL = 0x00;

    /** RFC 9114 section 6.2.2. */
    private static final long STREAM_TYPE_PUSH = 0x01;

    /** RFC 9204 section 4.2. */
    private static final long STREAM_TYPE_QPACK_ENCODER = 0x02;

    /** RFC 9204 section 4.2. */
    private static final long STREAM_TYPE_QPACK_DECODER = 0x03;

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

        /**
         * Called when a PRIORITY_UPDATE for a request stream arrives on
         * the control stream (RFC 9218 section 7.2).
         *
         * @param streamId the client-initiated bidirectional stream ID
         * @param fieldValue the Priority Field Value
         */
        void priorityUpdateReceived(long streamId, String fieldValue);
    }

    private enum StreamKind { CONTROL, PUSH, QPACK_ENCODER, QPACK_DECODER, UNKNOWN }

    private final QuicConnection quicConnection;
    private final Listener listener;
    private final Encoder qpackEncoder;
    private final Decoder qpackDecoder;
    // True when this handler sits on an HTTP/3 client connection, so
    // MAX_PUSH_ID from the peer is a server-sent frame (forbidden) and
    // a push stream is H3_ID_ERROR rather than H3_STREAM_CREATION_ERROR.
    private final boolean client;

    private int typeBytesNeeded = -1;
    private final ByteArrayOutputStream typeBuffer = new ByteArrayOutputStream(8);
    private StreamKind kind;
    private H3Parser parser;

    H3ControlStream(QuicConnection quicConnection, Listener listener, Encoder qpackEncoder, Decoder qpackDecoder,
            boolean client) {
        this.quicConnection = quicConnection;
        this.listener = listener;
        this.qpackEncoder = qpackEncoder;
        this.qpackDecoder = qpackDecoder;
        this.client = client;
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
        if (kind == null) {
            if (!readStreamType(data)) {
                return;
            }
            if (kind == StreamKind.CONTROL) {
                parser = new H3Parser(this);
            } else if (kind == StreamKind.PUSH) {
                // RFC 9114 section 4.6: a client that never sent
                // MAX_PUSH_ID (gumdrop never does) MUST treat a push
                // stream as H3_ID_ERROR. A server MUST treat a
                // client-opened push stream as H3_STREAM_CREATION_ERROR.
                long code = client ? H3ErrorCode.H3_ID_ERROR : H3ErrorCode.H3_STREAM_CREATION_ERROR;
                String message = client
                        ? "push stream received but no MAX_PUSH_ID was sent"
                        : "client opened a push stream";
                connectionError(code, message);
                return;
            }
        }
        switch (kind) {
            case CONTROL:
                parser.receive(data);
                break;
            case QPACK_ENCODER:
                qpackDecoder.feedEncoderStream(data);
                String error = qpackDecoder.takeLastInstructionError();
                if (error != null) {
                    String formatted = MessageFormat.format(
                            L10N.getString("warn.qpack_encoder_stream_error"), error);
                    LOGGER.warning(formatted);
                    quicConnection.closeWithApplicationError(H3ErrorCode.QPACK_ENCODER_STREAM_ERROR, error);
                }
                break;
            case QPACK_DECODER:
                qpackEncoder.feedDecoderStream(data);
                break;
            default:
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
        if (streamType == STREAM_TYPE_CONTROL) {
            kind = StreamKind.CONTROL;
        } else if (streamType == STREAM_TYPE_PUSH) {
            kind = StreamKind.PUSH;
        } else if (streamType == STREAM_TYPE_QPACK_ENCODER) {
            kind = StreamKind.QPACK_ENCODER;
        } else if (streamType == STREAM_TYPE_QPACK_DECODER) {
            kind = StreamKind.QPACK_DECODER;
        } else {
            kind = StreamKind.UNKNOWN;
        }
        return true;
    }

    @Override
    public void disconnected() {
    }

    @Override
    public void error(Exception cause) {
        String formatted = MessageFormat.format(
                L10N.getString("warn.control_stream_error"), cause);
        LOGGER.warning(formatted);
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
        // RFC 9114 section 7.2.3: gumdrop never permits push (no
        // MAX_PUSH_ID is sent, and the server never promises a push),
        // so any referenced push ID exceeds the allowed set.
        connectionError(H3ErrorCode.H3_ID_ERROR,
                "CANCEL_PUSH for a push ID that was never permitted");
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
        if (client) {
            // RFC 9114 section 7.2.7: a server MUST NOT send MAX_PUSH_ID.
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "MAX_PUSH_ID is not valid from a server");
            return;
        }
        // Server role: the client is raising the push budget. Gumdrop
        // never pushes, so the frame is legal and ignored.
    }

    @Override
    public void priorityUpdateRequestFrameReceived(long streamId, String fieldValue) {
        if (client) {
            // RFC 9218 section 7.2: a server MUST NOT send PRIORITY_UPDATE.
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "PRIORITY_UPDATE is not valid from a server");
            return;
        }
        // RFC 9114 section 7.2 / RFC 9218 section 7.2: client-initiated
        // bidirectional stream IDs are 0 mod 4.
        if (streamId % 4 != 0) {
            connectionError(H3ErrorCode.H3_ID_ERROR,
                    "PRIORITY_UPDATE stream ID is not a client-initiated bidirectional stream");
            return;
        }
        listener.priorityUpdateReceived(streamId, fieldValue);
    }

    @Override
    public void priorityUpdatePushFrameReceived(long pushId, String fieldValue) {
        if (client) {
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "PRIORITY_UPDATE is not valid from a server");
            return;
        }
        // Gumdrop never pushes — any push PRIORITY_UPDATE is an ID error.
        connectionError(H3ErrorCode.H3_ID_ERROR,
                "PRIORITY_UPDATE for a push ID that was never permitted");
    }

    @Override
    public void frameError(String message) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED, message);
    }

    private void connectionError(long errorCode, String message) {
        String formatted = MessageFormat.format(
                L10N.getString("warn.control_stream_error"), message);
        LOGGER.warning(formatted);
        quicConnection.closeWithApplicationError(errorCode, message);
    }
}
