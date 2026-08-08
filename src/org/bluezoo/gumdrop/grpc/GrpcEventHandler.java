/*
 * GrpcEventHandler.java
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

package org.bluezoo.gumdrop.grpc;

import java.nio.ByteBuffer;

/**
 * Handler for push-parsed gRPC message frames (RFC-style 5-byte prefix).
 *
 * @see GrpcFrameParser
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
