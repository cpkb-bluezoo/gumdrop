/*
 * GrpcEventHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc;

import java.nio.ByteBuffer;

/**
 * Handler for push-parsed gRPC message frames (RFC-style 5-byte prefix).
 *
 * @see GrpcFrameParser
 */
public interface GrpcEventHandler {

    /**
     * Called when a complete frame header has been parsed.
     *
     * @param compressionFlag 0 = uncompressed, 1 = compressed
     * @param length payload length in bytes
     */
    void startMessage(byte compressionFlag, int length);

    /**
     * Delivers a chunk of frame payload data (read-only slice).
     *
     * @param data payload slice
     */
    void messageData(ByteBuffer data);

    /** Called when the frame payload has been fully delivered. */
    void endMessage();

    /**
     * Called when a framing error is detected.
     *
     * @param message error description
     */
    void parseError(String message);
}
