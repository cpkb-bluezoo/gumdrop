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
import java.net.SocketAddress;
import java.security.Principal;
import java.time.Instant;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
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

    /**
     * Ends the call successfully, with status {@code OK} (0). A call to an RPC
     * whose response is a single message ends by itself once that message is
     * complete; a call that streams its response stays open until this or
     * {@link #close(int, String)} is called.
     */
    void close();

    /**
     * Ends the call with a status, in the trailers if response messages have
     * been sent already. Does nothing if the call has ended.
     *
     * @param status the gRPC status code
     * @param message the status message, or {@code null}
     */
    void close(int status, String message);

    /**
     * Whether the response can take more messages without queueing them.
     * A handler that sends many messages checks this, and when it is
     * {@code false} waits for {@link #onReady(Runnable)}.
     *
     * @return true if the transport has drained what was sent
     */
    boolean isReady();

    /**
     * Registers a one-shot callback for when the transport can take more
     * response data. Replaces any callback pending; {@code null} clears it.
     *
     * @param callback the callback, run on the connection's thread
     */
    void onReady(Runnable callback);

    /**
     * Stops request messages being delivered until {@link #resumeRequests()},
     * so that a client sending faster than the handler can work is slowed.
     */
    void pauseRequests();

    /** Resumes delivery of request messages after {@link #pauseRequests()}. */
    void resumeRequests();

    /**
     * Returns the metadata the client sent with the request. Entries the
     * protocol itself uses ({@code grpc-*}, {@code content-type}, {@code te})
     * are not included.
     *
     * @return the request metadata, empty if there is none
     */
    GrpcMetadata getRequestMetadata();

    /**
     * Returns the initial metadata of the response, which is sent with the
     * response headers, that is with the first message or with the status of
     * a call that sends none. Add to it before then: later changes are not
     * sent.
     *
     * @return the initial response metadata, to be added to
     */
    GrpcMetadata getResponseMetadata();

    /**
     * Returns the trailing metadata of the response, which is sent with the
     * status when the call ends. Add to it before then: later changes are not
     * sent.
     *
     * @return the trailing response metadata, to be added to
     */
    GrpcMetadata getTrailingMetadata();

    /**
     * Returns when the client wants the call to be over, from its
     * {@code grpc-timeout}. When the deadline passes the framework ends the
     * call with {@code DEADLINE_EXCEEDED} (4) and tells a
     * {@link GrpcCallHandler} that it is cancelled.
     *
     * @return the deadline, or {@code null} if the client set none
     */
    Instant getDeadline();

    /**
     * Returns the address of the client.
     *
     * @return the remote address
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the TLS details of the connection.
     *
     * @return the security information, or {@code null} if it is not secure
     */
    SecurityInfo getSecurityInfo();

    /**
     * Returns the authenticated principal of the client.
     *
     * @return the principal, or {@code null} if the client is not authenticated
     */
    Principal getPrincipal();
}
