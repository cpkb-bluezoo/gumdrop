/*
 * GrpcFrameParser.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * Push-parser for gRPC length-prefixed message frames.
 *
 * <p>Follows the same event-driven pattern as {@code MQTTFrameParser} and
 * {@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser}: frame
 * payloads are forwarded as slices from the input buffer without buffering
 * the entire HTTP body.
 */
public class GrpcFrameParser {

    private static final int HEADER_SIZE = 5;
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.grpc.L10N");

    private enum State { HEADER, PAYLOAD }

    private final GrpcEventHandler handler;
    private long maxMessageSize = GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE;
    private State state = State.HEADER;
    private final byte[] headerBuf = new byte[HEADER_SIZE];
    private int headerLen;
    private int payloadRemaining;
    private boolean messageCompleted;

    public GrpcFrameParser(GrpcEventHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    /**
     * Sets the maximum permitted frame payload size ({@code 0} = unlimited).
     */
    public void setMaxMessageSize(long maxMessageSize) {
        if (maxMessageSize < 0) {
            throw new IllegalArgumentException(
                    "maxMessageSize must not be negative, got: " + maxMessageSize);
        }
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * Returns true if at least one complete message frame was delivered.
     */
    public boolean isMessageCompleted() {
        return messageCompleted;
    }

    /**
     * Returns true if a frame is partially received.
     */
    public boolean hasPartialFrame() {
        return headerLen > 0 || state == State.PAYLOAD;
    }

    /**
     * Parses as many complete frames as possible from the buffer.
     *
     * @param buf input data (read mode)
     */
    public void receive(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            if (state == State.HEADER) {
                if (!processHeader(buf)) {
                    return;
                }
            } else {
                processPayload(buf);
                if (state == State.HEADER && !buf.hasRemaining()) {
                    return;
                }
            }
        }
    }

    private boolean processHeader(ByteBuffer buf) {
        while (headerLen < HEADER_SIZE && buf.hasRemaining()) {
            headerBuf[headerLen++] = buf.get();
        }
        if (headerLen < HEADER_SIZE) {
            return false;
        }

        ByteBuffer header = ByteBuffer.wrap(headerBuf);
        byte compressionFlag = header.get();
        if (compressionFlag != 0) {
            reset();
            handler.parseError(L10N.getString("err.compressed_frames_unsupported"));
            return false;
        }

        long length = ((long) (header.get() & 0xFF) << 24)
                | ((long) (header.get() & 0xFF) << 16)
                | ((long) (header.get() & 0xFF) << 8)
                | (header.get() & 0xFF);
        if (length > Integer.MAX_VALUE) {
            reset();
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.frame_too_large"), length));
            return false;
        }
        int payloadLength = (int) length;
        if (maxMessageSize > 0 && payloadLength > maxMessageSize) {
            reset();
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.frame_exceeds_max"),
                    payloadLength, maxMessageSize));
            return false;
        }

        headerLen = 0;
        payloadRemaining = payloadLength;
        state = State.PAYLOAD;
        handler.startMessage(compressionFlag, payloadLength);
        return true;
    }

    private void processPayload(ByteBuffer buf) {
        int toDeliver = Math.min(buf.remaining(), payloadRemaining);
        if (toDeliver > 0) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + toDeliver);
            handler.messageData(buf.slice());
            buf.limit(savedLimit);
            buf.position(buf.position() + toDeliver);
            payloadRemaining -= toDeliver;
        }

        if (payloadRemaining <= 0) {
            handler.endMessage();
            messageCompleted = true;
            state = State.HEADER;
        }
    }

    private void reset() {
        state = State.HEADER;
        headerLen = 0;
        payloadRemaining = 0;
    }
}
