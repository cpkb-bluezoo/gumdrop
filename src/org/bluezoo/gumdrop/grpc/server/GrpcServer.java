/*
 * GrpcServer.java
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
 * <p>Implementations receive the request message as events through a
 * {@link ProtoMessageHandler} and answer through the {@link GrpcCall}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GrpcServer {

    /**
     * Called when a call to an RPC of the {@code .proto} file begins.
     *
     * <p>Only unary RPCs reach this method for now: the framework answers a
     * call to an RPC that streams with {@code UNIMPLEMENTED} (12) itself. Other
     * kinds of RPC will be delivered here too once they are supported, and the
     * RPC's kind is available from {@link GrpcCall#getRpc()}; dispatch on the
     * path as you do now and you will only ever see the RPCs you implement.
     *
     * <p>The request message is delivered to the returned handler as events,
     * {@code endMessage()} marking the end of the whole message. Answer through
     * the call.
     *
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param call the call, to answer through
     * @return handler for the request message events, or {@code null} for an
     *         RPC you do not implement, which the caller is told is unimplemented
     */
    ProtoMessageHandler startCall(String path, GrpcCall call);
}
