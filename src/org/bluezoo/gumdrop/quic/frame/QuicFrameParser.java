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
import java.util.ArrayList;
import java.util.List;

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
            } else if (type == QuicFrameHandler.TYPE_RESET_STREAM) {
                if (!parseResetStreamFrame(buf)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_STOP_SENDING) {
                long[] streamIdAndValue = parseStreamIdAndValue(buf, "STOP_SENDING");
                if (streamIdAndValue == null) {
                    return;
                }
                handler.stopSendingFrameReceived(streamIdAndValue[0], streamIdAndValue[1]);
            } else if (type == QuicFrameHandler.TYPE_CRYPTO) {
                if (!parseCryptoFrame(buf)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_NEW_TOKEN) {
                if (!parseNewTokenFrame(buf)) {
                    return;
                }
            } else if (type >= QuicFrameHandler.TYPE_STREAM_MIN && type <= QuicFrameHandler.TYPE_STREAM_MAX) {
                if (!parseStreamFrame(buf, type)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_MAX_DATA) {
                if (!buf.hasRemaining()) {
                    handler.frameError("MAX_DATA frame underflow");
                    return;
                }
                handler.maxDataFrameReceived(VarInt.decode(buf));
            } else if (type == QuicFrameHandler.TYPE_MAX_STREAM_DATA) {
                long[] streamIdAndValue = parseStreamIdAndValue(buf, "MAX_STREAM_DATA");
                if (streamIdAndValue == null) {
                    return;
                }
                handler.maxStreamDataFrameReceived(streamIdAndValue[0], streamIdAndValue[1]);
            } else if (type == QuicFrameHandler.TYPE_MAX_STREAMS_BIDI
                    || type == QuicFrameHandler.TYPE_MAX_STREAMS_UNI) {
                if (!buf.hasRemaining()) {
                    handler.frameError("MAX_STREAMS frame underflow");
                    return;
                }
                handler.maxStreamsFrameReceived(type == QuicFrameHandler.TYPE_MAX_STREAMS_BIDI, VarInt.decode(buf));
            } else if (type == QuicFrameHandler.TYPE_DATA_BLOCKED) {
                if (!buf.hasRemaining()) {
                    handler.frameError("DATA_BLOCKED frame underflow");
                    return;
                }
                handler.dataBlockedFrameReceived(VarInt.decode(buf));
            } else if (type == QuicFrameHandler.TYPE_STREAM_DATA_BLOCKED) {
                long[] streamIdAndValue = parseStreamIdAndValue(buf, "STREAM_DATA_BLOCKED");
                if (streamIdAndValue == null) {
                    return;
                }
                handler.streamDataBlockedFrameReceived(streamIdAndValue[0], streamIdAndValue[1]);
            } else if (type == QuicFrameHandler.TYPE_STREAMS_BLOCKED_BIDI
                    || type == QuicFrameHandler.TYPE_STREAMS_BLOCKED_UNI) {
                if (!buf.hasRemaining()) {
                    handler.frameError("STREAMS_BLOCKED frame underflow");
                    return;
                }
                handler.streamsBlockedFrameReceived(
                        type == QuicFrameHandler.TYPE_STREAMS_BLOCKED_BIDI, VarInt.decode(buf));
            } else if (type == QuicFrameHandler.TYPE_NEW_CONNECTION_ID) {
                if (!parseNewConnectionIdFrame(buf)) {
                    return;
                }
            } else if (type == QuicFrameHandler.TYPE_RETIRE_CONNECTION_ID) {
                if (!buf.hasRemaining()) {
                    handler.frameError("RETIRE_CONNECTION_ID frame underflow");
                    return;
                }
                handler.retireConnectionIdFrameReceived(VarInt.decode(buf));
            } else if (type == QuicFrameHandler.TYPE_PATH_CHALLENGE) {
                ByteBuffer data = parseFixedLengthData(buf, QuicFrameHandler.PATH_DATA_LENGTH, "PATH_CHALLENGE");
                if (data == null) {
                    return;
                }
                handler.pathChallengeFrameReceived(data);
            } else if (type == QuicFrameHandler.TYPE_PATH_RESPONSE) {
                ByteBuffer data = parseFixedLengthData(buf, QuicFrameHandler.PATH_DATA_LENGTH, "PATH_RESPONSE");
                if (data == null) {
                    return;
                }
                handler.pathResponseFrameReceived(data);
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
            } else if (type == QuicFrameHandler.TYPE_DATAGRAM
                    || type == QuicFrameHandler.TYPE_DATAGRAM_LEN) {
                if (!parseDatagramFrame(buf, startPosition, type == QuicFrameHandler.TYPE_DATAGRAM_LEN)) {
                    return;
                }
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

        long firstLow = largestAcknowledged - firstAckRange;
        if (firstLow < 0) {
            handler.frameError("ACK frame's first range computes a negative packet number");
            return false;
        }
        List<long[]> ranges = new ArrayList<long[]>();
        ranges.add(new long[] { firstLow, largestAcknowledged });

        // RFC 9000 section 19.3.1: each subsequent range is derived from
        // the previous range's low end (previousSmallest).
        long previousSmallest = firstLow;
        for (long i = 0; i < ackRangeCount; i++) {
            if (!buf.hasRemaining()) {
                handler.frameError("ACK frame underflow in range " + i);
                return false;
            }
            long gap = VarInt.decode(buf);
            if (!buf.hasRemaining()) {
                handler.frameError("ACK frame underflow reading range " + i + "'s length");
                return false;
            }
            long rangeLength = VarInt.decode(buf);

            long rangeHigh = previousSmallest - gap - 2;
            long rangeLow = rangeHigh - rangeLength;
            if (rangeHigh < 0 || rangeLow < 0) {
                handler.frameError("ACK frame range " + i + " computes a negative packet number");
                return false;
            }
            ranges.add(new long[] { rangeLow, rangeHigh });
            previousSmallest = rangeLow;
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

        handler.ackFrameReceived(largestAcknowledged, ackDelay, ranges.toArray(new long[ranges.size()][]));
        return true;
    }

    // RFC 9000 section 19.4
    private boolean parseResetStreamFrame(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            handler.frameError("RESET_STREAM frame underflow");
            return false;
        }
        long streamId = VarInt.decode(buf);
        if (!buf.hasRemaining()) {
            handler.frameError("RESET_STREAM frame underflow reading error code");
            return false;
        }
        long applicationErrorCode = VarInt.decode(buf);
        if (!buf.hasRemaining()) {
            handler.frameError("RESET_STREAM frame underflow reading final size");
            return false;
        }
        long finalSize = VarInt.decode(buf);
        handler.resetStreamFrameReceived(streamId, applicationErrorCode, finalSize);
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

    // RFC 9000 section 19.7
    private boolean parseNewTokenFrame(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            handler.frameError("NEW_TOKEN frame underflow");
            return false;
        }
        long length = VarInt.decode(buf);
        if (length <= 0 || length > buf.remaining()) {
            handler.frameError("NEW_TOKEN frame length exceeds payload");
            return false;
        }

        int tokenLength = (int) length;
        int savedLimit = buf.limit();
        buf.limit(buf.position() + tokenLength);
        ByteBuffer token = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + tokenLength);

        handler.newTokenFrameReceived(token);
        return true;
    }

    // RFC 9000 section 19.8
    private boolean parseStreamFrame(ByteBuffer buf, long type) {
        boolean hasOffset = (type & 0x04) != 0;
        boolean hasLength = (type & 0x02) != 0;
        boolean fin = (type & 0x01) != 0;

        if (!buf.hasRemaining()) {
            handler.frameError("STREAM frame underflow");
            return false;
        }
        long streamId = VarInt.decode(buf);
        long offset = 0;
        if (hasOffset) {
            if (!buf.hasRemaining()) {
                handler.frameError("STREAM frame underflow reading offset");
                return false;
            }
            offset = VarInt.decode(buf);
        }

        int dataLength;
        if (hasLength) {
            if (!buf.hasRemaining()) {
                handler.frameError("STREAM frame underflow reading length");
                return false;
            }
            long length = VarInt.decode(buf);
            if (length < 0 || length > buf.remaining()) {
                handler.frameError("STREAM frame length exceeds payload");
                return false;
            }
            dataLength = (int) length;
        } else {
            dataLength = buf.remaining();
        }

        int savedLimit = buf.limit();
        buf.limit(buf.position() + dataLength);
        ByteBuffer data = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + dataLength);

        handler.streamFrameReceived(streamId, offset, fin, data);
        return true;
    }

    // RFC 9000 sections 19.10, 19.13: both are {Stream ID (i), Value (i)}
    private long[] parseStreamIdAndValue(ByteBuffer buf, String frameName) {
        if (!buf.hasRemaining()) {
            handler.frameError(frameName + " frame underflow");
            return null;
        }
        long streamId = VarInt.decode(buf);
        if (!buf.hasRemaining()) {
            handler.frameError(frameName + " frame underflow reading value");
            return null;
        }
        long value = VarInt.decode(buf);
        return new long[] { streamId, value };
    }

    // RFC 9000 section 19.15
    private boolean parseNewConnectionIdFrame(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            handler.frameError("NEW_CONNECTION_ID frame underflow");
            return false;
        }
        long sequenceNumber = VarInt.decode(buf);
        if (!buf.hasRemaining()) {
            handler.frameError("NEW_CONNECTION_ID frame underflow reading Retire Prior To");
            return false;
        }
        long retirePriorTo = VarInt.decode(buf);
        if (!buf.hasRemaining()) {
            handler.frameError("NEW_CONNECTION_ID frame underflow reading Length");
            return false;
        }
        int connectionIdLength = buf.get() & 0xff;
        if (connectionIdLength < 1 || connectionIdLength > 20) {
            handler.frameError("NEW_CONNECTION_ID frame has an invalid connection ID length: " + connectionIdLength);
            return false;
        }
        int required = connectionIdLength + QuicFrameHandler.STATELESS_RESET_TOKEN_LENGTH;
        if (buf.remaining() < required) {
            handler.frameError("NEW_CONNECTION_ID frame underflow reading connection ID/reset token");
            return false;
        }

        int savedLimit = buf.limit();
        buf.limit(buf.position() + connectionIdLength);
        ByteBuffer connectionId = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + connectionIdLength);

        buf.limit(buf.position() + QuicFrameHandler.STATELESS_RESET_TOKEN_LENGTH);
        ByteBuffer statelessResetToken = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + QuicFrameHandler.STATELESS_RESET_TOKEN_LENGTH);

        handler.newConnectionIdFrameReceived(sequenceNumber, retirePriorTo, connectionId, statelessResetToken);
        return true;
    }

    // RFC 9000 sections 19.17, 19.18: both are a fixed-length opaque Data field
    private ByteBuffer parseFixedLengthData(ByteBuffer buf, int length, String frameName) {
        if (buf.remaining() < length) {
            handler.frameError(frameName + " frame underflow");
            return null;
        }
        int savedLimit = buf.limit();
        buf.limit(buf.position() + length);
        ByteBuffer data = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + length);
        return data;
    }

    // RFC 9221 section 4: type 0x30 has no Length (payload is the
    // remainder of the packet); type 0x31 prefixes the payload with a
    // Length varint so a DATAGRAM can share a packet with other frames.
    private boolean parseDatagramFrame(ByteBuffer buf, int typeStartPosition, boolean lengthPresent) {
        int payloadLength;
        if (lengthPresent) {
            if (!buf.hasRemaining()) {
                handler.frameError("DATAGRAM frame underflow");
                return false;
            }
            long declaredLength = VarInt.decode(buf);
            if (declaredLength < 0 || declaredLength > buf.remaining()) {
                handler.frameError("DATAGRAM frame underflow");
                return false;
            }
            payloadLength = (int) declaredLength;
        } else {
            payloadLength = buf.remaining();
        }
        int encodedLength = buf.position() - typeStartPosition + payloadLength;
        ByteBuffer data = parseFixedLengthData(buf, payloadLength, "DATAGRAM");
        if (data == null) {
            return false;
        }
        handler.datagramFrameReceived(data, encodedLength);
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
