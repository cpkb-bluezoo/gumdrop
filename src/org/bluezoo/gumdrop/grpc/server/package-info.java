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
 * gRPC server, riding gumdrop's HTTP/2 server rather than a dedicated
 * transport.
 *
 * <p>{@link org.bluezoo.gumdrop.grpc.server.GrpcService} is the
 * application service base, creating a {@link
 * org.bluezoo.gumdrop.grpc.server.GrpcHandler} (an {@link
 * org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler}) per call via a
 * {@link org.bluezoo.gumdrop.grpc.server.GrpcHandlerFactory}, so gRPC's
 * message framing and deframing ({@link org.bluezoo.gumdrop.grpc}) sits
 * directly on top of HTTP/2 request/response handling rather than a
 * separate connection type. {@link
 * org.bluezoo.gumdrop.grpc.server.GrpcResponseSender} sends a {@link
 * org.bluezoo.gumdrop.grpc.server.GrpcResponseMessage} back, applying
 * the gRPC wire framing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.grpc
 * @see org.bluezoo.gumdrop.http.HTTPService
 */
package org.bluezoo.gumdrop.grpc.server;
