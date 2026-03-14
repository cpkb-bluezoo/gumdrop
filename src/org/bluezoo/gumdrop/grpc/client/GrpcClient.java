/*
 * GrpcClient.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;

/**
 * gRPC client that uses the HTTP client for transport.
 *
 * <p>Composes {@link HTTPClient} and adds gRPC framing around request/response
 * bodies. Does not extend the HTTP client; uses it as-is.
 *
 * <h3>Usage</h3>
 * <pre>
 * HTTPClient httpClient = new HTTPClient("localhost", 50051);
 * httpClient.setSecure(true);
 * GrpcClient grpcClient = new GrpcClient(protoFile);
 *
 * httpClient.connect(new HTTPClientHandler() {
 *     public void onConnected(Endpoint endpoint) {
 *         ByteBuffer request = ...; // serialized request message
 *         grpcClient.unaryCall(httpClient, "/example.UserService/GetUser",
 *             request, new GrpcResponseHandler() {
 *                 public void onMessage(ByteBuffer message) { ... }
 *                 public void onError(Exception e) { ... }
 *             });
 *     }
 *     ...
 * });
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClient {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;

    /**
     * Creates a gRPC client with the given Proto model.
     *
     * @param protoFile the Proto model (from parsed .proto file)
     */
    public GrpcClient(ProtoFile protoFile) {
        this.protoFile = protoFile;
    }

    /**
     * Performs a unary gRPC call.
     *
     * @param httpClient the connected HTTP client
     * @param path the gRPC path (/package.Service/Method)
     * @param requestMessage the serialized request message
     * @param handler the response handler
     */
    public void unaryCall(HTTPClient httpClient, String path,
                          ByteBuffer requestMessage,
                          GrpcResponseHandler handler) {
        ByteBuffer framed = GrpcFraming.frame(requestMessage);

        HTTPRequest request = httpClient.post(path);
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");

        request.startRequestBody(new GrpcResponseAdapter(handler, protoFile));
        request.requestBodyContent(framed);
        request.endRequestBody();
    }

    /**
     * Performs a unary gRPC call with a high-level message handler.
     *
     * @param httpClient the connected HTTP client
     * @param path the gRPC path (/package.Service/Method)
     * @param requestMessage the serialized request message
     * @param responseTypeName the fully qualified response message type name
     * @param messageHandler the handler for response message events
     */
    public void unaryCall(HTTPClient httpClient, String path,
                          ByteBuffer requestMessage,
                          String responseTypeName,
                          ProtoMessageHandler messageHandler) {
        unaryCall(httpClient, path, requestMessage,
                new ProtoMessageHandlerAdapter(responseTypeName, messageHandler, protoFile));
    }

    private static class GrpcResponseAdapter implements HTTPResponseHandler {

        private final GrpcResponseHandler handler;
        private final ProtoFile protoFile;
        private final List<ByteBuffer> bodyChunks = new ArrayList<>();
        private boolean failed;

        GrpcResponseAdapter(GrpcResponseHandler handler, ProtoFile protoFile) {
            this.handler = handler;
            this.protoFile = protoFile;
        }

        @Override
        public void ok(org.bluezoo.gumdrop.http.client.HTTPResponse response) {
        }

        @Override
        public void error(org.bluezoo.gumdrop.http.client.HTTPResponse response) {
            failed = true;
            handler.onError(new GrpcException("gRPC error: " + response.getStatus()));
        }

        @Override
        public void failed(Exception cause) {
            failed = true;
            handler.onError(cause);
        }

        @Override
        public void header(String name, String value) {
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (!failed) {
                ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                copy.put(data);
                copy.flip();
                bodyChunks.add(copy);
            }
        }

        @Override
        public void endResponseBody() {
            if (failed) return;

            int total = 0;
            for (ByteBuffer b : bodyChunks) {
                total += b.remaining();
            }
            ByteBuffer combined = ByteBuffer.allocate(total);
            for (ByteBuffer b : bodyChunks) {
                combined.put(b);
            }
            combined.flip();

            try {
                int length = GrpcFraming.readHeader(combined);
                if (length >= 0 && combined.remaining() >= length) {
                    ByteBuffer message = combined.slice();
                    message.limit(length);
                    handler.onMessage(message);
                }
            } catch (Exception e) {
                handler.onError(e);
            }
        }

        @Override
        public void close() {
        }

        @Override
        public void pushPromise(org.bluezoo.gumdrop.http.client.PushPromise promise) {
        }
    }

    private static class ProtoMessageHandlerAdapter implements GrpcResponseHandler {

        private final String responseTypeName;
        private final ProtoMessageHandler messageHandler;
        private final ProtoFile protoFile;
        private final List<ByteBuffer> bodyChunks = new ArrayList<>();
        private boolean failed;

        ProtoMessageHandlerAdapter(String responseTypeName,
                                   ProtoMessageHandler messageHandler,
                                   ProtoFile protoFile) {
            this.responseTypeName = responseTypeName;
            this.messageHandler = messageHandler;
            this.protoFile = protoFile;
        }

        @Override
        public void onMessage(ByteBuffer message) {
            ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, messageHandler);
            try {
                adapter.startRootMessage(responseTypeName);
                ProtobufParser parser = new ProtobufParser(adapter);
                parser.receive(message);
                parser.close();
                adapter.endRootMessage();
            } catch (ProtoParseException e) {
                onError(e);
            } catch (org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException e) {
                onError(e);
            }
        }

        @Override
        public void onError(Exception e) {
            failed = true;
        }
    }
}
