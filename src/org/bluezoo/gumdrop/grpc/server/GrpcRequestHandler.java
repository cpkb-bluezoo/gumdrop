/*
 * GrpcRequestHandler.java
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

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.ContentType;
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
    public HttpRequestHandler openStream(HttpResponseState response) {
        return new GrpcStreamHandler(response);
    }

    /**
     * Collects the request target and content type, and once the header
     * section ends hands the request to a {@link GrpcHandler} for the method
     * (or answers 404 if it is not a gRPC call this server knows).
     */
    private final class GrpcStreamHandler extends DefaultHttpRequestHandler {

        private final HttpResponseState response;
        private String path;
        private ContentType contentType;
        private HttpRequestHandler delegate;

        GrpcStreamHandler(HttpResponseState response) {
            this.response = response;
        }

        @Override
        public void target(ByteBuffer target) {
            byte[] octets = new byte[target.remaining()];
            target.duplicate().get(octets);
            path = new String(octets, StandardCharsets.ISO_8859_1);
        }

        @Override
        public void contentType(ContentType contentType) {
            this.contentType = contentType;
        }

        @Override
        public void endHeaders() {
            delegate = createDelegate();
            if (delegate == null) {
                new NotFoundHttpRequestHandler(response).endHeaders();
                return;
            }
            delegate.endHeaders();
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (delegate != null) {
                delegate.bodyContent(data);
            }
        }

        @Override
        public void endMessage() {
            if (delegate != null) {
                delegate.endMessage();
            }
        }

        private HttpRequestHandler createDelegate() {
            if (path == null || !path.startsWith("/") || path.length() < 2) {
                return null;
            }
            if (contentType == null
                    || !CONTENT_TYPE_GRPC.equals(contentType.toHeaderValue())) {
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

            return new GrpcHandler(protoFile, server, response, path, maxMessageSize,
                    protoFile.getRpcByPath(path));
        }
    }

}
