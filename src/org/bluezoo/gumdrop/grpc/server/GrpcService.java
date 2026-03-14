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

import java.nio.ByteBuffer;

/**
 * Interface for handling gRPC RPC calls.
 *
 * <p>Implementations dispatch to the appropriate method based on the path
 * (/package.Service/Method) and process the request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GrpcService {

    /**
     * Handles a unary gRPC call.
     *
     * @param path the gRPC path (/package.Service/Method)
     * @param request the request message bytes (without gRPC framing)
     * @param response the sender for the response
     */
    void unaryCall(String path, ByteBuffer request, GrpcResponseSender response);
}
