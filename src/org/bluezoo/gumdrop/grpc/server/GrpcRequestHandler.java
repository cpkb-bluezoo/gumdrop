/*
 * GrpcRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.grpc.server;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.NotFoundHttpRequestHandler;

/**
 * gRPC stream handler for {@link org.bluezoo.gumdrop.http.HttpServer}.
 *
 * <p>Accepts {@code POST} requests with {@code application/grpc} and dispatches
 * to {@link GrpcHandler}. Path matching is handled in the per-stream request
 * handler, not in the HTTP protocol layer.
 */
public class GrpcRequestHandler implements HttpStreamHandler {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;
    private final GrpcServer server;
    private long maxMessageSize = GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE;

    public GrpcRequestHandler(ProtoFile protoFile, GrpcServer server) {
        this.protoFile = protoFile;
        this.server = server;
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
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return new GrpcStreamHandler();
    }

    private final class GrpcStreamHandler extends DefaultHttpRequestHandler {

        private HttpRequestHandler delegate;

        @Override
        public void headers(HttpResponseState state, Headers headers) {
            if (delegate != null) {
                delegate.headers(state, headers);
                return;
            }
            delegate = createDelegate(state, headers);
            if (delegate == null) {
                NotFoundHttpRequestHandler.INSTANCE.headers(state, headers);
                return;
            }
            delegate.headers(state, headers);
        }

        @Override
        public void startRequestBody(HttpResponseState state) {
            forward(state).startRequestBody(state);
        }

        @Override
        public void requestBodyContent(HttpResponseState state,
                java.nio.ByteBuffer data) {
            forward(state).requestBodyContent(state, data);
        }

        @Override
        public void endRequestBody(HttpResponseState state) {
            forward(state).endRequestBody(state);
        }

        @Override
        public void requestComplete(HttpResponseState state) {
            if (delegate != null) {
                delegate.requestComplete(state);
            }
        }

        private HttpRequestHandler forward(HttpResponseState state) {
            if (delegate == null) {
                NotFoundHttpRequestHandler.INSTANCE.headers(state, new Headers());
                throw new IllegalStateException("no delegate before body event");
            }
            return delegate;
        }

        private HttpRequestHandler createDelegate(HttpResponseState state,
                Headers headers) {
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

            return new GrpcHandler(protoFile, server, path, maxMessageSize,
                    protoFile.getRpcByPath(path));
        }
    }

}
