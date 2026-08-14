/*
 * QuicFrameParser.java
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
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Push-parser for QUIC frames (RFC 9000 section 19), the transport-layer
 * analogue of {@code org.bluezoo.gumdrop.http.h2.H2Parser}.
 *
 * <p>Unlike HTTP/2, where each frame carries an explicit length prefix,
 * QUIC frames are simply concatenated within a packet's decrypted
 * payload -- the frame type determines the frame's field layout, and
 * therefore its length, so an unrecognised frame type makes the rest of
 * the buffer unparseable. When that happens, {@link #receive} reports it
 * via {@link QuicFrameHandler#frameError} and stops.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicFrameHandler
 */
public class QuicFrameParser {

    private final QuicFrameHandler handler;

    /**
     * Creates a new QUIC frame parser.
     *
     * @param handler the handler to receive parsed frames
     */
    public QuicFrameParser(QuicFrameHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    /**
     * Parses every frame in a packet's decrypted payload.
     *
     * @param buf the buffer containing the payload (in read mode);
     *            fully consumed on return, unless an error is reported
     */
    public void receive(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            int startPosition = buf.position();
            long type = VarInt.decode(buf);

            if (type == QuicFrameHandler.TYPE_PADDING) {
                int count = 1;
                while (buf.hasRemaining() && (buf.get(buf.position()) & 0xff) == 0) {
                    buf.get();
                    count++;
                }
                handler.paddingFrameReceived(count);
            } else if (type == QuicFrameHandler.TYPE_PING) {
                handler.pingFrameReceived();
            } else if (type == QuicFrameHandler.TYPE_ACK || type == QuicFrameHandler.TYPE_ACK_ECN) {
                if (!parseAckFrame(buf, type == QuicFrameHandler.TYPE_ACK_ECN)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_CRYPTO) {
                if (!parseCryptoFrame(buf)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_CONNECTION_CLOSE) {
                if (!parseConnectionCloseFrame(buf, false)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_CONNECTION_CLOSE_APP) {
                if (!parseConnectionCloseFrame(buf, true)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_HANDSHAKE_DONE) {
                handler.handshakeDoneFrameReceived();
            } else {
                buf.position(startPosition);
                handler.frameError("Unsupported or unknown frame type: " + type);
                return;
            }
        }
    }

    // RFC 9000 section 19.3
    private boolean parseAckFrame(ByteBuffer buf, boolean withEcnCounts) {
        if (buf.remaining() < 4) {
            handler.frameError("ACK frame underflow");
            return false;
        }
        long largestAcknowledged = VarInt.decode(buf);
        long ackDelay = VarInt.decode(buf);
        long ackRangeCount = VarInt.decode(buf);
        long firstAckRange = VarInt.decode(buf);

        for (long i = 0; i < ackRangeCount; i++) {
            if (!buf.hasRemaining()) {
                handler.frameError("ACK frame underflow in range " + i);
                return false;
            }
            VarInt.decode(buf); // Gap
            VarInt.decode(buf); // ACK Range Length
        }

        if (withEcnCounts) {
            if (!buf.hasRemaining()) {
                handler.frameError("ACK frame underflow in ECN counts");
                return false;
            }
            VarInt.decode(buf); // ECT0 Count
            VarInt.decode(buf); // ECT1 Count
            VarInt.decode(buf); // ECN-CE Count
        }

        handler.ackFrameReceived(largestAcknowledged, ackDelay, firstAckRange);
        return true;
    }

    // RFC 9000 section 19.6
    private boolean parseCryptoFrame(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            handler.frameError("CRYPTO frame underflow");
            return false;
        }
        long offset = VarInt.decode(buf);
        long length = VarInt.decode(buf);
        if (length < 0 || length > buf.remaining()) {
            handler.frameError("CRYPTO frame length exceeds payload");
            return false;
        }

        int dataLength = (int) length;
        int savedLimit = buf.limit();
        buf.limit(buf.position() + dataLength);
        ByteBuffer data = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + dataLength);

        handler.cryptoFrameReceived(offset, data);
        return true;
    }

    // RFC 9000 section 19.19
    private boolean parseConnectionCloseFrame(ByteBuffer buf, boolean applicationError) {
        if (!buf.hasRemaining()) {
            handler.frameError("CONNECTION_CLOSE frame underflow");
            return false;
        }
        long errorCode = VarInt.decode(buf);
        long frameType = 0;
        if (!applicationError) {
            frameType = VarInt.decode(buf);
        }
        long reasonLength = VarInt.decode(buf);
        if (reasonLength < 0 || reasonLength > buf.remaining()) {
            handler.frameError("CONNECTION_CLOSE reason phrase length exceeds payload");
            return false;
        }

        byte[] reasonBytes = new byte[(int) reasonLength];
        buf.get(reasonBytes);
        String reason = new String(reasonBytes, StandardCharsets.UTF_8);

        handler.connectionCloseFrameReceived(applicationError, errorCode, frameType, reason);
        return true;
    }
}
