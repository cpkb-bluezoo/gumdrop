/*
 * GrpcService.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;

/**
 * Interface for handling gRPC RPC calls.
 *
 * <p>Implementations receive request protobuf events through a
 * {@link ProtoMessageHandler} and send responses via {@link GrpcResponseSender}.
 */
public interface GrpcService {

    /**
     * Called when a unary RPC request body begins.
     *
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param response sender for the response message
     * @return handler for request protobuf events, or {@code null} if unimplemented
     */
    ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response);
}
