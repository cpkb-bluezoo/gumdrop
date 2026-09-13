/*
 * GrpcRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.grpc.server;

import java.util.Set;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestRouter;
import org.bluezoo.gumdrop.http.server.HttpResponseState;

/**
 * gRPC request router for {@link org.bluezoo.gumdrop.http.HttpServer}.
 *
 * <p>Routes {@code POST} requests with {@code application/grpc} to
 * {@link GrpcHandler}.
 */
public class GrpcRequestHandler implements HttpRequestRouter {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;
    private final GrpcServer service;
    private long maxMessageSize = GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE;

    public GrpcRequestHandler(ProtoFile protoFile, GrpcServer service) {
        this.protoFile = protoFile;
        this.service = service;
    }

    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    public GrpcRequestHandler maxMessageSize(long maxMessageSize) {
        if (maxMessageSize < 0) {
            throw new IllegalArgumentException(
                    "maxMessageSize must not be negative, got: " + maxMessageSize);
        }
        this.maxMessageSize = maxMessageSize;
        return this;
    }

    @Override
    public HttpRequestHandler route(HttpResponseState state, Headers headers) {
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

        return new GrpcHandler(protoFile, service, path, maxMessageSize,
                protoFile.getRpcByPath(path));
    }

    @Override
    public Set<String> getSupportedMethods() {
        return Set.of("POST");
    }

}
