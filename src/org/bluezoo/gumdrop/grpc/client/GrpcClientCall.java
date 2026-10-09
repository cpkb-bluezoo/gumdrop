/*
 * GrpcClientCall.java
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

package org.bluezoo.gumdrop.grpc.client;

/**
 * A gRPC call in progress, as returned by {@link GrpcClient#call}.
 *
 * <p>This is the client's whole handle on a call. Other operations on a
 * call, such as sending further request messages, a deadline and metadata,
 * will be methods of this interface.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GrpcClientCall {

    /**
     * Cancels the call. The request stream is reset, so the server is told
     * the client is no longer interested; nothing further is delivered to the
     * response handler. Has no effect on a call that has finished.
     */
    void cancel();
}
