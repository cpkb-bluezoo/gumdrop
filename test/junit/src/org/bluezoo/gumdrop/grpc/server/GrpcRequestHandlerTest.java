/*
 * GrpcRequestHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcRequestHandler} configuration (SEC-011).
 */
public class GrpcRequestHandlerTest {

    private static final ProtoFile PROTO = ProtoFile.builder().build();
    private static final GrpcServer NOOP_SERVICE = new GrpcServer() {
        @Override
        public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
            return null;
        }
    };

    @Test
    public void testDefaultMaxMessageSize() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        assertEquals(GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, handler.getMaxMessageSize());
    }

    @Test
    public void testSetMaxMessageSize() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        handler.maxMessageSize(8192);
        assertEquals(8192, handler.getMaxMessageSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxMessageSizeRejectsNegative() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        handler.maxMessageSize(-1);
    }
}
