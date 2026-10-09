/*
 * GrpcCall.java
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

import java.io.IOException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;

/**
 * One gRPC call being served: what a {@link GrpcServer} answers through.
 *
 * <p>For a unary RPC, send the response by opening its single message with
 * {@link #openMessage} and completing it; the call then ends with status 0.
 * Or end it with an error using {@link #sendError(int, String)}.
 *
 * <p>This is the call's whole server-side context. Information about the
 * call itself, such as its metadata, deadline and peer, is available from
 * methods on this interface, and other kinds of RPC will use the same
 * object.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GrpcCall {

    /**
     * Returns the RPC being served, as declared in the {@code .proto} file:
     * its name, message types and whether either side streams.
     *
     * @return the RPC
     */
    RpcDescriptor getRpc();

    /**
     * Opens an event-driven response message encoder.
     *
     * @param messageTypeName fully qualified protobuf response type name, or
     *        {@code null} for the RPC's declared response type
     * @return encoder; call {@link GrpcResponseMessage#complete()} when finished
     * @throws IOException if the call has already been answered
     */
    GrpcResponseMessage openMessage(String messageTypeName) throws IOException;

    /**
     * Ends the call with an error.
     *
     * @param status the gRPC status code, see
     *        {@link org.bluezoo.gumdrop.grpc.GrpcStatus}
     * @param message the error message
     */
    void sendError(int status, String message);

    /**
     * Ends the call with {@code INTERNAL} (13), logging the cause.
     *
     * @param cause the exception
     */
    void sendError(Throwable cause);
}
