/*
 * GrpcResponseSender.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import java.io.IOException;

/**
 * Sender for gRPC response messages.
 */
public interface GrpcResponseSender {

    /**
     * Opens an event-driven response message encoder.
     *
     * @param messageTypeName fully qualified protobuf response type name
     * @return encoder; call {@link GrpcResponseMessage#complete()} when finished
     */
    GrpcResponseMessage openMessage(String messageTypeName) throws IOException;

    /**
     * Sends an error response.
     *
     * @param status the gRPC status code
     * @param message the error message
     */
    void sendError(int status, String message);

    /**
     * Sends an error response.
     *
     * @param cause the exception
     */
    void sendError(Throwable cause);
}
