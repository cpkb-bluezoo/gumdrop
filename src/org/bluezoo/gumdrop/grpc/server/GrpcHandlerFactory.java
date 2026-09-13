/*
 * GrpcHandlerFactory.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandlerFactory;
import org.bluezoo.gumdrop.http.server.HttpResponseState;

import java.util.Set;

/**
 * @deprecated use {@link GrpcRequestHandler}.
 */
@Deprecated
public class GrpcHandlerFactory implements HttpRequestHandlerFactory {

    private final GrpcRequestHandler router;

    public GrpcHandlerFactory(ProtoFile protoFile, GrpcServer service) {
        this.router = new GrpcRequestHandler(protoFile, service);
    }

    public long getMaxMessageSize() {
        return router.getMaxMessageSize();
    }

    public void setMaxMessageSize(long maxMessageSize) {
        router.maxMessageSize(maxMessageSize);
    }

    @Override
    public HttpRequestHandler createHandler(HttpResponseState state, Headers headers) {
        return router.route(state, headers);
    }

    @Override
    public Set<String> getSupportedMethods() {
        return router.getSupportedMethods();
    }

}
