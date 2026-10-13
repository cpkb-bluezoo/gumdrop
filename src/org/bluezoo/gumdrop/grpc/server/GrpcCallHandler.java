/*
 * GrpcCallHandler.java
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
 * A {@link ProtoMessageHandler} for a call that wants to know how the
 * request stream ends, as a streaming RPC does.
 *
 * <p>Return one from {@link GrpcServer#startCall} and the framework tells it
 * about the half-close and cancellation. For a streaming call it receives
 * one {@code startMessage}/{@code endMessage} pair for each request message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GrpcCallHandler extends ProtoMessageHandler {

    /**
     * The client has sent all its request messages (half-close). Called once,
     * after the last message, and also for a call that sent none. The call
     * can still be answered.
     */
    default void endRequests() {
    }

    /**
     * The call is over without the handler having ended it: the client reset
     * the stream or the connection failed, or the request was malformed.
     * Stop sending; nothing sent now reaches the client.
     */
    default void cancelled() {
    }
}
