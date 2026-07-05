/*
 * GrpcHandlerFactoryTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcHandlerFactory} configuration (SEC-011).
 */
public class GrpcHandlerFactoryTest {

    private static final ProtoFile PROTO = ProtoFile.builder().build();
    private static final GrpcService NOOP_SERVICE = (path, response) -> null;

    @Test
    public void testDefaultMaxMessageSize() {
        GrpcHandlerFactory factory = new GrpcHandlerFactory(PROTO, NOOP_SERVICE);
        assertEquals(GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, factory.getMaxMessageSize());
    }

    @Test
    public void testSetMaxMessageSize() {
        GrpcHandlerFactory factory = new GrpcHandlerFactory(PROTO, NOOP_SERVICE);
        factory.setMaxMessageSize(8192);
        assertEquals(8192, factory.getMaxMessageSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxMessageSizeRejectsNegative() {
        GrpcHandlerFactory factory = new GrpcHandlerFactory(PROTO, NOOP_SERVICE);
        factory.setMaxMessageSize(-1);
    }
}
