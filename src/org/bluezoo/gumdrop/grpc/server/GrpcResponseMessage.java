/*
 * GrpcResponseMessage.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import java.io.IOException;

import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

/**
 * Event-driven encoder for a single gRPC response message.
 *
 * <p>The application writes protobuf fields through {@link #getSerializer()}
 * and {@link #getWriter()}, then calls {@link #complete()} to frame and
 * send the message.
 */
public interface GrpcResponseMessage {

    ProtoModelSerializer getSerializer();

    ProtobufWriter getWriter() throws IOException;

    /**
     * Finishes the message, applies gRPC framing, and sends the HTTP response.
     */
    void complete() throws IOException;
}
