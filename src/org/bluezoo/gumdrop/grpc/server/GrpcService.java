/*
 * GrpcService.java
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

import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;

/**
 * Interface for handling gRPC RPC calls.
 *
 * <p>Implementations receive request protobuf events through a
 * {@link ProtoMessageHandler} and send responses via {@link GrpcResponseSender}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
