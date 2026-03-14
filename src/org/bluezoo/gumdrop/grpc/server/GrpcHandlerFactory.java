/*
 * GrpcHandlerFactory.java
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

import java.util.Set;

import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.Headers;

/**
 * HTTPRequestHandlerFactory that routes gRPC requests to a GrpcHandler.
 *
 * <p>Checks that the path matches /package.Service/Method and content-type
 * is application/grpc, then returns a handler that parses gRPC framing
 * and dispatches to the GrpcService.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcHandlerFactory implements HTTPRequestHandlerFactory {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;
    private final GrpcService service;

    /**
     * Creates a factory with the given Proto model and service.
     *
     * @param protoFile the Proto model
     * @param service the gRPC service implementation
     */
    public GrpcHandlerFactory(ProtoFile protoFile, GrpcService service) {
        this.protoFile = protoFile;
        this.service = service;
    }

    @Override
    public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
        String path = headers.getValue(":path");
        String contentType = headers.getValue("content-type");

        if (path == null || !path.startsWith("/") || path.length() < 2) {
            return null;
        }
        if (!CONTENT_TYPE_GRPC.equals(contentType)) {
            return null;
        }

        int slash = path.indexOf('/', 1);
        if (slash < 0) {
            return null;
        }
        String method = path.substring(slash + 1);
        if (method.isEmpty()) {
            return null;
        }

        return new GrpcHandler(protoFile, service, path);
    }

    @Override
    public Set<String> getSupportedMethods() {
        return Set.of("POST");
    }
}
