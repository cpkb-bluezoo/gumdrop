/*
 * H3Parser.java
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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Stateful, incremental push-parser for HTTP/3 frames (RFC 9114
 * section 7), one instance per QUIC stream. The HTTP/3 analogue of
 * {@code org.bluezoo.gumdrop.http.h2.H2Parser}, but unlike that parser
 * (and unlike {@code org.bluezoo.gumdrop.quic.frame.QuicFrameParser}),
 * this one must be genuinely incremental: QUIC frames are always fully
 * contained within one already-decrypted packet, but HTTP/3 framing
 * rides on a QUIC stream's reassembled byte sequence, "unlike QUIC
 * frames, HTTP/3 frames can span multiple packets" (RFC 9114 section
 * 7.1) -- including splitting a frame's own Type or Length varint
 * across reads. {@link #receive} may therefore be called any number of
 * times with arbitrary byte chunks, and must tolerate a chunk ending
 * mid-varint or mid-payload.
 *
 * <p>DATA frame payload is delivered to
 * {@link H3FrameHandler#dataFrameReceived} incrementally, in whatever
 * chunks {@link #receive} is called with, since request/response bodies
 * are not bounded in size the way a frame like HEADERS or SETTINGS
 * effectively is; every other frame type is buffered internally until
 * complete before its callback fires.
 *
 * <p>Frame types this parser does not recognise are delivered via
 * {@link H3FrameHandler#unknownFrameReceived} rather than as an error,
 * per RFC 9114 section 7.2.8's general extensibility rule. The control
 * stream uses that callback to enforce SETTINGS-first (section 7.2.4);
 * request streams ignore it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3FrameHandler
 */
public class H3Parser {

    private enum State {
        /** Waiting for the frame Type varint's first byte. */
        TYPE,
        /** Have the Type's length; waiting for the rest of its bytes. */
        TYPE_CONTINUATION,
        /** Waiting for the frame Length varint's first byte. */
        LENGTH,
        /** Have the Length's length; waiting for the rest of its bytes. */
        LENGTH_CONTINUATION,
        /** Have a complete header; consuming (and, for DATA, delivering) the payload. */
        PAYLOAD
    }

    private final H3FrameHandler handler;

    private State state = State.TYPE;
    private int headerBytesNeeded;
    private final ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream(8);

    private long frameType;
    private long payloadRemaining;
    private ByteArrayOutputStream payloadBuffer;

    /**
     * Creates a new HTTP/3 frame parser.
     *
     * @param handler the handler to receive parsed frames
     */
    public H3Parser(H3FrameHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    /**
     * Parses as many complete frames (and, for a DATA frame in
     * progress, as much payload) as {@code buf} contains.
     *
     * @param buf the buffer containing newly received stream bytes (in
     *            read mode); fully consumed on return, unless an error
     *            is reported
     */
    public void receive(ByteBuffer buf) {
        // Deliberately not "while (buf.hasRemaining())": a state
        // transition can become processable without consuming any more
        // bytes from buf (e.g. a varint's last byte was the last byte
        // buf had, or a zero-length payload dispatches immediately once
        // its Length varint is known) -- gating every iteration on
        // buf.hasRemaining() would strand that transition until a
        // future receive() call, which may never come. Each case is
        // responsible for returning once it genuinely cannot make
        // progress with what is available.
        while (true) {
            switch (state) {
                case TYPE:
                    if (!buf.hasRemaining() || !beginVarint(buf, State.TYPE_CONTINUATION)) {
                        return;
                    }
                    break;
                case TYPE_CONTINUATION:
                    if (!continueVarint(buf)) {
                        return;
                    }
                    frameType = decodeHeaderVarint();
                    headerBuffer.reset();
                    state = State.LENGTH;
                    break;
                case LENGTH:
                    if (!buf.hasRemaining() || !beginVarint(buf, State.LENGTH_CONTINUATION)) {
                        return;
                    }
                    break;
                case LENGTH_CONTINUATION:
                    if (!continueVarint(buf)) {
                        return;
                    }
                    payloadRemaining = decodeHeaderVarint();
                    headerBuffer.reset();
                    state = State.PAYLOAD;
                    payloadBuffer = null;
                    if (payloadRemaining == 0) {
                        dispatchPayload(EMPTY_BUFFER);
                        state = State.TYPE;
                    }
                    break;
                case PAYLOAD:
                    if (!consumePayload(buf)) {
                        return;
                    }
                    break;
                default:
                    throw new IllegalStateException("Unreachable state: " + state);
            }
        }
    }

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * Starts reading a varint: peeks its total length from the first
     * byte, then either consumes it immediately (if fully available)
     * or buffers what is available and transitions to the continuation
     * state.
     *
     * @return true if the caller should keep processing {@code buf};
     *         false if it should return and wait for more data
     */
    private boolean beginVarint(ByteBuffer buf, State continuationState) {
        int totalLength = VarInt.peekEncodedLength(buf, buf.position());
        headerBytesNeeded = totalLength;
        if (buf.remaining() >= totalLength) {
            byte[] bytes = new byte[totalLength];
            buf.get(bytes);
            headerBuffer.write(bytes, 0, bytes.length);
            state = continuationState;
            // Already complete: let the caller re-enter the switch at
            // continuationState, which will find nothing left to read
            // from buf for this varint and fall through immediately.
            return continueVarint(buf);
        }
        int available = buf.remaining();
        byte[] bytes = new byte[available];
        buf.get(bytes);
        headerBuffer.write(bytes, 0, bytes.length);
        state = continuationState;
        return false;
    }

    /**
     * Continues reading a varint whose first bytes were already
     * buffered by {@link #beginVarint}.
     *
     * @return true once {@code headerBuffer} holds the complete varint;
     *         false if more bytes are still needed
     */
    private boolean continueVarint(ByteBuffer buf) {
        int stillNeeded = headerBytesNeeded - headerBuffer.size();
        if (stillNeeded <= 0) {
            return true;
        }
        int available = Math.min(stillNeeded, buf.remaining());
        byte[] bytes = new byte[available];
        buf.get(bytes);
        headerBuffer.write(bytes, 0, bytes.length);
        return headerBuffer.size() >= headerBytesNeeded;
    }

    private long decodeHeaderVarint() {
        return VarInt.decode(ByteBuffer.wrap(headerBuffer.toByteArray()));
    }

    /**
     * Consumes as much of the current frame's payload as {@code buf}
     * has available. For a DATA frame, delivers each chunk immediately;
     * for every other frame type, buffers until the payload is complete.
     *
     * @return true if the caller should keep processing {@code buf};
     *         false if it should return and wait for more data
     */
    private boolean consumePayload(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            // Nothing new to report; avoid firing a spurious
            // zero-byte/false-endOfFrame callback for a DATA frame.
            return false;
        }
        int available = (int) Math.min(payloadRemaining, buf.remaining());
        int savedLimit = buf.limit();
        buf.limit(buf.position() + available);
        ByteBuffer chunk = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + available);
        payloadRemaining -= available;

        if (frameType == H3FrameHandler.TYPE_DATA) {
            handler.dataFrameReceived(chunk, payloadRemaining == 0);
        } else {
            if (payloadBuffer == null) {
                payloadBuffer = new ByteArrayOutputStream(Math.max(16, available));
            }
            byte[] bytes = new byte[chunk.remaining()];
            chunk.get(bytes);
            payloadBuffer.write(bytes, 0, bytes.length);
        }

        if (payloadRemaining > 0) {
            return buf.hasRemaining();
        }

        if (frameType != H3FrameHandler.TYPE_DATA) {
            dispatchPayload(ByteBuffer.wrap(payloadBuffer.toByteArray()));
            payloadBuffer = null;
        }
        state = State.TYPE;
        return true;
    }

    private void dispatchPayload(ByteBuffer payload) {
        try {
            if (frameType == H3FrameHandler.TYPE_DATA) {
                handler.dataFrameReceived(payload, true);
            } else if (frameType == H3FrameHandler.TYPE_HEADERS) {
                handler.headersFrameReceived(payload);
            } else if (frameType == H3FrameHandler.TYPE_CANCEL_PUSH) {
                long pushId = VarInt.decode(payload);
                handler.cancelPushFrameReceived(pushId);
            } else if (frameType == H3FrameHandler.TYPE_SETTINGS) {
                dispatchSettings(payload);
            } else if (frameType == H3FrameHandler.TYPE_PUSH_PROMISE) {
                long pushId = VarInt.decode(payload);
                handler.pushPromiseFrameReceived(pushId, payload.slice());
            } else if (frameType == H3FrameHandler.TYPE_GOAWAY) {
                long streamOrPushId = VarInt.decode(payload);
                handler.goawayFrameReceived(streamOrPushId);
            } else if (frameType == H3FrameHandler.TYPE_MAX_PUSH_ID) {
                long maxPushId = VarInt.decode(payload);
                handler.maxPushIdFrameReceived(maxPushId);
            } else if (frameType == H3FrameHandler.TYPE_PRIORITY_UPDATE_REQUEST) {
                long streamId = VarInt.decode(payload);
                String fieldValue = decodePriorityFieldValue(payload);
                if (fieldValue == null) {
                    handler.frameError("PRIORITY_UPDATE field value is not valid UTF-8");
                } else {
                    handler.priorityUpdateRequestFrameReceived(streamId, fieldValue);
                }
            } else if (frameType == H3FrameHandler.TYPE_PRIORITY_UPDATE_PUSH) {
                long pushId = VarInt.decode(payload);
                String fieldValue = decodePriorityFieldValue(payload);
                if (fieldValue == null) {
                    handler.frameError("PRIORITY_UPDATE field value is not valid UTF-8");
                } else {
                    handler.priorityUpdatePushFrameReceived(pushId, fieldValue);
                }
            } else {
                // RFC 9114 section 7.2.8: reserved HTTP/2 leftovers are
                // H3_FRAME_UNEXPECTED; genuine GREASE/extension types are
                // ignored on request streams, and the control stream is
                // notified so it can enforce SETTINGS-first (section 7.2.4).
                handler.unknownFrameReceived(frameType);
            }
        } catch (BufferUnderflowException e) {
            handler.frameError("Malformed frame payload for type " + frameType
                    + ": fields do not fit within the declared frame length");
        }
    }

    // RFC 9114 section 7.2.4: a flat sequence of {Identifier (i), Value (i)} pairs
    private void dispatchSettings(ByteBuffer payload) {
        int pairCount = 0;
        ByteBuffer counting = payload.duplicate();
        while (counting.hasRemaining()) {
            VarInt.decode(counting);
            VarInt.decode(counting);
            pairCount++;
        }
        long[] settings = new long[pairCount * 2];
        int i = 0;
        while (payload.hasRemaining()) {
            settings[i++] = VarInt.decode(payload);
            settings[i++] = VarInt.decode(payload);
        }
        handler.settingsFrameReceived(settings);
    }

    private static String decodePriorityFieldValue(ByteBuffer payload) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
