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

import org.bluezoo.gumdrop.grpc.GrpcCompression;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
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

    /** The default size below which a response message is not compressed. */
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;

    private final ProtoFile protoFile;
    private final GrpcServer server;
    private long maxMessageSize = GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE;
    private Coding[] encodings = GrpcCompression.DEFAULT_ENCODINGS;
    private boolean compressResponses = true;
    private int compressionThreshold = DEFAULT_COMPRESSION_THRESHOLD;

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

    /**
     * Returns the compression algorithms this handler decodes in requests and
     * offers for responses, besides {@code identity}.
     *
     * @return the algorithms
     */
    public Coding[] getEncodings() {
        return encodings.clone();
    }

    /**
     * Sets the compression algorithms this handler decodes in requests and
     * offers in {@code grpc-accept-encoding}, besides {@code identity}, which
     * is always allowed. The default is {@code gzip} and {@code deflate}, the
     * algorithms of the gRPC specification. Add {@link Coding#BR} (Brotli) to
     * allow it too: it is an extension that only works with peers that have it
     * as well.
     *
     * @param encodings the algorithms
     * @return this handler
     */
    public GrpcRequestHandler encodings(Coding... encodings) {
        this.encodings = encodings.clone();
        return this;
    }

    /**
     * Returns whether responses are compressed when the client allows it.
     *
     * @return true if they are
     */
    public boolean isCompressResponses() {
        return compressResponses;
    }

    /**
     * Sets whether response messages are compressed when the client lists an
     * algorithm this handler offers in its {@code grpc-accept-encoding}. On by
     * default.
     *
     * @param compressResponses true to compress
     * @return this handler
     */
    public GrpcRequestHandler compressResponses(boolean compressResponses) {
        this.compressResponses = compressResponses;
        return this;
    }

    /**
     * Returns the size below which a response message is not compressed.
     *
     * @return the size in bytes
     */
    public int getCompressionThreshold() {
        return compressionThreshold;
    }

    /**
     * Sets the size, in bytes of the serialised message, below which a
     * response message is sent as it is, as compressing a small message gains
     * little. The default is 256.
     *
     * @param compressionThreshold the size in bytes, not negative
     * @return this handler
     * @throws IllegalArgumentException if it is negative
     */
    public GrpcRequestHandler compressionThreshold(int compressionThreshold) {
        if (compressionThreshold < 0) {
            throw new IllegalArgumentException(
                    "compressionThreshold must not be negative, got: " + compressionThreshold);
        }
        this.compressionThreshold = compressionThreshold;
        return this;
    }

    @Override
    public HttpRequestHandler openStream(HttpResponse response) {
        return new GrpcStreamHandler(response);
    }

    /**
     * Collects the request target and content type, and once the header
     * section ends hands the request to a {@link GrpcHandler} for the method
     * (or answers 404 if it is not a gRPC call this server knows).
     */
    private final class GrpcStreamHandler extends DefaultHttpRequestHandler {

        private final HttpResponse response;
        private String path;
        private ContentType contentType;
        private final GrpcMetadata metadata = new GrpcMetadata();
        private String timeout;
        private String encoding;
        private String acceptEncoding;
        private HttpRequestHandler delegate;

        GrpcStreamHandler(HttpResponse response) {
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
        public void header(String name, ByteBuffer value) {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            String text = StandardCharsets.ISO_8859_1.decode(value.duplicate()).toString();
            if (key.equals("grpc-timeout")) {
                timeout = text;
            } else if (key.equals("grpc-encoding")) {
                encoding = text;
            } else if (key.equals("grpc-accept-encoding")) {
                acceptEncoding = text;
            } else if (!GrpcMetadata.isReserved(key)) {
                metadata.addReceived(key, text);
            }
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

        @Override
        public boolean failedAfterEndMessage() {
            return true;
        }

        @Override
        public void failed(Exception cause) {
            if (delegate != null) {
                delegate.failed(cause);
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

            GrpcHandler handler = new GrpcHandler(protoFile, server, response, path,
                    maxMessageSize, protoFile.getRpcByPath(path));
            handler.requestHeaders(metadata, timeout, encoding, acceptEncoding);
            handler.compression(encodings, compressResponses, compressionThreshold);
            return handler;
        }
    }

}
