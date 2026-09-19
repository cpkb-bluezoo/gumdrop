/*
 * GrpcRequestHandlerTest.java
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

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcRequestHandler} configuration (SEC-011).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
