/*
 * GrpcResponseHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.client;

import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;

/**
 * Handler for streaming gRPC response message events.
 */
public interface GrpcResponseHandler {

    /**
     * Called when a response message frame begins.
     *
     * @param messageTypeName fully qualified protobuf type name
     * @return handler for response protobuf events
     */
    ProtoMessageHandler startMessage(String messageTypeName);

    /**
     * Called when an error occurs.
     *
     * @param e the error
     */
    void onError(Exception e);
}
