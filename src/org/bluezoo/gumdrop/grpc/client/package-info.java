/*
 * package-info.java
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

/**
 * gRPC client, delegating to a gumdrop HTTP client.
 *
 * <p>{@link org.bluezoo.gumdrop.grpc.client.GrpcClient} takes a proto
 * schema ({@link org.bluezoo.gumdrop.grpc.proto.ProtoFile}) and makes
 * unary calls through the generic {@link
 * org.bluezoo.gumdrop.http.client.HTTPRequest}/{@link
 * org.bluezoo.gumdrop.http.client.HTTPResponseHandler} API, framing and
 * deframing request/response messages per the gRPC wire format ({@link
 * org.bluezoo.gumdrop.grpc}). {@link
 * org.bluezoo.gumdrop.grpc.client.GrpcResponseHandler} is the callback
 * interface for a call's outcome.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.grpc
 * @see org.bluezoo.gumdrop.http.client.HTTPClient
 */
package org.bluezoo.gumdrop.grpc.client;
