/*
 * GrpcHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.grpc.GrpcEventHandler;
import org.bluezoo.gumdrop.grpc.GrpcFrameParser;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

/**
 * HTTPRequestHandler that processes gRPC requests using push parsers.
 *
 * <p>gRPC framing and protobuf decoding stream from the HTTP request body
 * into {@link ProtoMessageHandler} events without buffering the entire body.
 */
public class GrpcHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(GrpcHandler.class.getName());
    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final int GRPC_STATUS_UNIMPLEMENTED = 12;

    private final ProtoFile protoFile;
    private final GrpcService service;
    private final String path;
    private final long maxMessageSize;
    private final String requestTypeName;
    private final String responseTypeName;

    private HTTPResponseState state;
    private GrpcResponseSenderImpl responseSender;
    private ProtoMessageHandler requestHandler;
    private ProtoModelAdapter protoAdapter;
    private ProtobufParser protobufParser;
    private GrpcFrameParser frameParser;
    private boolean bodyStarted;
    private boolean bodyRejected;

    GrpcHandler(ProtoFile protoFile, GrpcService service, String path,
            long maxMessageSize, RpcDescriptor rpc) {
        this.protoFile = protoFile;
        this.service = service;
        this.path = path;
        this.maxMessageSize = maxMessageSize;
        this.requestTypeName = rpc != null ? rpc.getInputTypeName() : null;
        this.responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
    }

    @Override
    public void headers(HTTPResponseState state, Headers headers) {
        this.state = state;
    }

    @Override
    public void startRequestBody(HTTPResponseState state) {
        this.state = state;
        bodyStarted = true;

        if (requestTypeName == null) {
            reject(HTTPStatus.NOT_FOUND, "Unknown RPC");
            return;
        }

        responseSender = new GrpcResponseSenderImpl(state);
        requestHandler = service.startUnaryCall(path, responseSender);
        if (requestHandler == null) {
            responseSender.sendError(GRPC_STATUS_UNIMPLEMENTED, "Unimplemented");
            bodyRejected = true;
            return;
        }

        protoAdapter = new ProtoModelAdapter(protoFile, requestHandler);
        try {
            protoAdapter.startRootMessage(requestTypeName);
        } catch (ProtoParseException e) {
            LOGGER.log(Level.WARNING, "Failed to start request message", e);
            reject(HTTPStatus.BAD_REQUEST, "Invalid request type");
            return;
        }

        protobufParser = new ProtobufParser(protoAdapter);
        frameParser = new GrpcFrameParser(new FrameToProtobufBridge());
        frameParser.setMaxMessageSize(maxMessageSize);
    }

    @Override
    public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
        if (bodyRejected || !bodyStarted || frameParser == null
                || data == null || !data.hasRemaining()) {
            return;
        }
        frameParser.receive(data);
    }

    @Override
    public void endRequestBody(HTTPResponseState state) {
        if (bodyRejected || !bodyStarted) {
            if (!bodyStarted) {
                reject(HTTPStatus.BAD_REQUEST, "Missing request body");
            }
            return;
        }
        if (frameParser == null) {
            return;
        }
        if (frameParser.hasPartialFrame() || !frameParser.isMessageCompleted()) {
            reject(HTTPStatus.BAD_REQUEST, "Invalid gRPC frame");
        }
    }

    private final class FrameToProtobufBridge implements GrpcEventHandler {

        @Override
        public void startMessage(byte compressionFlag, int length) {
        }

        @Override
        public void messageData(ByteBuffer data) {
            try {
                protobufParser.receive(data);
            } catch (ProtobufParseException e) {
                LOGGER.log(Level.WARNING, "Protobuf parse error", e);
                reject(HTTPStatus.BAD_REQUEST, "Invalid request message");
            }
        }

        @Override
        public void endMessage() {
            try {
                protobufParser.close();
                protoAdapter.endRootMessage();
            } catch (ProtoParseException | ProtobufParseException e) {
                LOGGER.log(Level.WARNING, "Failed to complete request message", e);
                reject(HTTPStatus.BAD_REQUEST, "Invalid request message");
            }
        }

        @Override
        public void parseError(String message) {
            LOGGER.warning(message);
            reject(HTTPStatus.BAD_REQUEST, "Invalid gRPC frame");
        }
    }

    private void reject(HTTPStatus status, String message) {
        if (bodyRejected) {
            return;
        }
        bodyRejected = true;
        sendError(state, status, message);
    }

    private void sendError(HTTPResponseState state, HTTPStatus status, String message) {
        Headers response = new Headers();
        response.status(status);
        response.add("content-type", "text/plain");
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(message.getBytes()));
        state.endResponseBody();
        state.complete();
    }

    private final class GrpcResponseSenderImpl implements GrpcResponseSender {

        private final HTTPResponseState responseState;
        private boolean sent;

        GrpcResponseSenderImpl(HTTPResponseState responseState) {
            this.responseState = responseState;
        }

        @Override
        public GrpcResponseMessage openMessage(String messageTypeName) throws IOException {
            if (sent) {
                throw new IOException("Response already sent");
            }
            String typeName = messageTypeName != null ? messageTypeName : responseTypeName;
            if (typeName == null) {
                throw new IOException("Unknown response message type");
            }
            return new GrpcResponseMessageImpl(typeName);
        }

        @Override
        public void sendError(int status, String message) {
            if (sent) {
                return;
            }
            sent = true;

            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add("content-type", CONTENT_TYPE_GRPC);
            response.add("grpc-status", String.valueOf(status));
            response.add("grpc-message", message != null ? message : "");
            responseState.headers(response);
            responseState.complete();
        }

        @Override
        public void sendError(Throwable cause) {
            if (sent) {
                return;
            }
            if (cause != null) {
                LOGGER.log(Level.SEVERE, "gRPC internal error", cause);
            }
            sendError(13, "Internal error");
        }

        private void sendFramed(ByteBuffer message) {
            if (sent) {
                return;
            }
            sent = true;

            ByteBuffer framed = GrpcFraming.frame(message);
            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add("content-type", CONTENT_TYPE_GRPC);
            responseState.headers(response);
            responseState.startResponseBody();
            responseState.responseBodyContent(framed);
            responseState.endResponseBody();
            responseState.complete();
        }

        private final class GrpcResponseMessageImpl implements GrpcResponseMessage {

            private final String messageTypeName;
            private final ProtoModelSerializer serializer;
            private final ByteBufferChannel channel;
            private final ProtobufWriter writer;
            private boolean started;

            GrpcResponseMessageImpl(String messageTypeName) {
                this.messageTypeName = messageTypeName;
                this.serializer = new ProtoModelSerializer(protoFile);
                this.channel = new ByteBufferChannel();
                this.writer = new ProtobufWriter(channel);
            }

            @Override
            public ProtoModelSerializer getSerializer() {
                return serializer;
            }

            @Override
            public ProtobufWriter getWriter() throws IOException {
                ensureStarted();
                return writer;
            }

            @Override
            public void complete() throws IOException {
                ensureStarted();
                serializer.endMessage();
                sendFramed(channel.toByteBuffer());
            }

            private void ensureStarted() throws IOException {
                if (!started) {
                    serializer.startMessage(writer, messageTypeName);
                    started = true;
                }
            }
        }
    }
}
