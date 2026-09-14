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

    private final GrpcRequestHandler streamHandler;

    public GrpcHandlerFactory(ProtoFile protoFile, GrpcServer service) {
        this.streamHandler = new GrpcRequestHandler(protoFile, service);
    }

    public long getMaxMessageSize() {
        return streamHandler.getMaxMessageSize();
    }

    public void setMaxMessageSize(long maxMessageSize) {
        streamHandler.maxMessageSize(maxMessageSize);
    }

    @Override
    public HttpRequestHandler createHandler(HttpResponseState state, Headers headers) {
        return streamHandler.openStream(state);
    }

    @Override
    public Set<String> getSupportedMethods() {
        return Set.of("POST");
    }

}
