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

import org.bluezoo.gumdrop.grpc.GrpcEventHandler;
import org.bluezoo.gumdrop.grpc.GrpcFrameParser;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;

/**
 * gRPC client that uses the HTTP client for transport.
 *
 * <p>Response bodies are parsed with {@link GrpcFrameParser} and
 * {@link ProtobufParser} as data arrives, without buffering the full body.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClient {

    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;

    public GrpcClient(ProtoFile protoFile) {
        this.protoFile = protoFile;
    }

    public void unaryCall(HTTPClient httpClient, String path,
                          ByteBuffer requestMessage,
                          String responseTypeName,
                          ProtoMessageHandler messageHandler) {
        unaryCall(httpClient, path, requestMessage, new GrpcResponseHandler() {
            @Override
            public ProtoMessageHandler startMessage(String typeName) {
                return messageHandler;
            }

            @Override
            public void onError(Exception e) {
            }
        }, responseTypeName);
    }

    private void unaryCall(HTTPClient httpClient, String path,
                           ByteBuffer requestMessage,
                           GrpcResponseHandler handler,
                           String responseTypeName) {
        ByteBuffer framed = GrpcFraming.frame(requestMessage);

        HTTPRequest request = httpClient.post(path);
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");

        request.startRequestBody(
                new StreamingResponseHandler(handler, protoFile, responseTypeName));
        request.requestBodyContent(framed);
        request.endRequestBody();
    }

    /**
     * Performs a unary gRPC call with a pre-serialized request message.
     */
    public void unaryCall(HTTPClient httpClient, String path,
                          ByteBuffer requestMessage,
                          GrpcResponseHandler handler) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
        unaryCall(httpClient, path, requestMessage, handler, responseTypeName);
    }

    private static final class StreamingResponseHandler implements HTTPResponseHandler {

        private final GrpcResponseHandler handler;
        private final ProtoFile protoFile;
        private final String defaultResponseTypeName;
        private boolean failed;
        private GrpcFrameParser frameParser;
        private ProtobufParser protobufParser;
        private ProtoModelAdapter protoAdapter;
        private ProtoMessageHandler messageHandler;

        StreamingResponseHandler(GrpcResponseHandler handler, ProtoFile protoFile,
                String defaultResponseTypeName) {
            this.handler = handler;
            this.protoFile = protoFile;
            this.defaultResponseTypeName = defaultResponseTypeName;
        }

        @Override
        public void ok(org.bluezoo.gumdrop.http.client.HTTPResponse response) {
        }

        @Override
        public void error(org.bluezoo.gumdrop.http.client.HTTPResponse response) {
            fail(new GrpcException("gRPC error: " + response.getStatus()));
        }

        @Override
        public void failed(Exception cause) {
            fail(cause);
        }

        @Override
        public void header(String name, String value) {
        }

        @Override
        public void startResponseBody() {
            if (failed) {
                return;
            }
            String typeName = defaultResponseTypeName;
            messageHandler = handler.startMessage(typeName);
            if (messageHandler == null) {
                fail(new GrpcException("No response handler"));
                return;
            }
            protoAdapter = new ProtoModelAdapter(protoFile, messageHandler);
            try {
                if (typeName != null) {
                    protoAdapter.startRootMessage(typeName);
                }
            } catch (ProtoParseException e) {
                fail(e);
                return;
            }
            protobufParser = new ProtobufParser(protoAdapter);
            frameParser = new GrpcFrameParser(new FrameBridge());
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (!failed && frameParser != null && data != null && data.hasRemaining()) {
                frameParser.receive(data);
            }
        }

        @Override
        public void endResponseBody() {
            if (failed || frameParser == null) {
                return;
            }
            if (frameParser.hasPartialFrame() || !frameParser.isMessageCompleted()) {
                fail(new GrpcException("Incomplete gRPC response frame"));
            }
        }

        @Override
        public void close() {
        }

        @Override
        public void pushPromise(org.bluezoo.gumdrop.http.client.PushPromise promise) {
        }

        private void fail(Exception e) {
            if (!failed) {
                failed = true;
                handler.onError(e);
            }
        }

        private final class FrameBridge implements GrpcEventHandler {

            @Override
            public void startMessage(byte compressionFlag, int length) {
            }

            @Override
            public void messageData(ByteBuffer data) {
                try {
                    protobufParser.receive(data);
                } catch (ProtobufParseException e) {
                    fail(e);
                }
            }

            @Override
            public void endMessage() {
                try {
                    protobufParser.close();
                    protoAdapter.endRootMessage();
                } catch (ProtoParseException | ProtobufParseException e) {
                    fail(e);
                }
            }

            @Override
            public void parseError(String message) {
                fail(new GrpcException(message));
            }
        }
    }
}
