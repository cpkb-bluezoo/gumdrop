/*
 * GrpcHandler.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.grpc.GrpcEventHandler;
import org.bluezoo.gumdrop.grpc.GrpcFrameParser;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufParseException;
import org.bluezoo.protobuf.ProtobufParser;
import org.bluezoo.protobuf.ProtobufWriter;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.SelectorLoop;

/**
 * HttpRequestHandler that processes gRPC requests using push parsers.
 *
 * <p>gRPC framing and protobuf decoding stream from the HTTP request body
 * into {@link ProtoMessageHandler} events without buffering the entire body.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcHandler extends DefaultHttpRequestHandler {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.grpc.L10N");

    private static final Logger LOGGER = Logger.getLogger(GrpcHandler.class.getName());

    private EventLogger events() {
        SelectorLoop loop = response != null ? response.getSelectorLoop() : null;
        TelemetryConfig telemetry = loop != null ? loop.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(GrpcHandler.class, L10N);
    }
    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    

    private final ProtoFile protoFile;
    private final GrpcServer server;
    private final String path;
    private final long maxMessageSize;
    private final RpcDescriptor rpc;
    private final String requestTypeName;
    private final String responseTypeName;

    private final HttpResponse response;
    private GrpcCallImpl responseSender;
    private ProtoMessageHandler requestHandler;
    private ProtoModelAdapter protoAdapter;
    private ProtobufParser protobufParser;
    private GrpcFrameParser frameParser;
    private boolean bodyStarted;
    private boolean bodyRejected;

    GrpcHandler(ProtoFile protoFile, GrpcServer server, HttpResponse response,
            String path, long maxMessageSize, RpcDescriptor rpc) {
        this.response = response;
        this.protoFile = protoFile;
        this.server = server;
        this.path = path;
        this.maxMessageSize = maxMessageSize;
        this.rpc = rpc;
        this.requestTypeName = rpc != null ? rpc.getInputTypeName() : null;
        this.responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
    }

    /** Begins reading the request message; the header section has ended. */
    @Override
    public void endHeaders() {
        bodyStarted = true;

        if (requestTypeName == null) {
            reject(HttpStatus.NOT_FOUND, "Unknown RPC");
            return;
        }

        if (rpc.isClientStreaming() || rpc.isServerStreaming()) {
            // Only unary RPCs are served for now; the caller is told so, in a
            // trailers-only response, and the server never sees the call
            new GrpcCallImpl(response).sendError(GrpcStatus.UNIMPLEMENTED,
                    "Streaming RPCs are not supported");
            bodyRejected = true;
            return;
        }
        responseSender = new GrpcCallImpl(response);
        requestHandler = server.startCall(path, responseSender);
        if (requestHandler == null) {
            responseSender.sendError(GrpcStatus.UNIMPLEMENTED, "Unimplemented");
            bodyRejected = true;
            return;
        }

        protoAdapter = new ProtoModelAdapter(protoFile, requestHandler);
        try {
            protoAdapter.startRootMessage(requestTypeName);
        } catch (ProtoParseException e) {
            events().warn("log.grpc_start_request_failed").thrown(e).emit();
            reject(HttpStatus.BAD_REQUEST, "Invalid request type");
            return;
        }

        protobufParser = new ProtobufParser(protoAdapter);
        frameParser = new GrpcFrameParser(new FrameToProtobufBridge());
        frameParser.setMaxMessageSize(maxMessageSize);
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (bodyRejected || !bodyStarted || frameParser == null
                || data == null || !data.hasRemaining()) {
            return;
        }
        frameParser.receive(data);
    }

    @Override
    public void endMessage() {
        if (bodyRejected || !bodyStarted) {
            if (!bodyStarted) {
                reject(HttpStatus.BAD_REQUEST, "Missing request body");
            }
            return;
        }
        if (frameParser == null) {
            return;
        }
        if (frameParser.hasPartialFrame() || !frameParser.isMessageCompleted()) {
            reject(HttpStatus.BAD_REQUEST, "Invalid gRPC frame");
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
                events().warn("log.grpc_protobuf_parse_error").thrown(e).emit();
                reject(HttpStatus.BAD_REQUEST, "Invalid request message");
            }
        }

        @Override
        public void endMessage() {
            try {
                protobufParser.close();
                protoAdapter.endRootMessage();
            } catch (ProtoParseException | ProtobufParseException e) {
                events().warn("log.grpc_complete_request_failed").thrown(e).emit();
                reject(HttpStatus.BAD_REQUEST, "Invalid request message");
            }
        }

        @Override
        public void parseError(String message) {
            events().warn("log.grpc_parse_error").attr("message", message).emit();
            reject(HttpStatus.BAD_REQUEST, "Invalid gRPC frame");
        }
    }

    private void reject(HttpStatus status, String message) {
        if (bodyRejected) {
            return;
        }
        bodyRejected = true;
        sendError(response, status, message);
    }

    private void sendError(HttpResponse response, HttpStatus status, String message) {
        response.status(status.code);
        response.header("content-type", "text/plain");
        response.bodyContent(ByteBuffer.wrap(message.getBytes()));
        response.endMessage();
    }

    private final class GrpcCallImpl implements GrpcCall {

        private final HttpResponse responseState;
        private boolean sent;

        GrpcCallImpl(HttpResponse responseState) {
            this.responseState = responseState;
        }

        @Override
        public RpcDescriptor getRpc() {
            return rpc;
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

            responseState.status(HttpStatus.OK.code);
            responseState.header("content-type", CONTENT_TYPE_GRPC);
            responseState.header("grpc-status", String.valueOf(status));
            responseState.header("grpc-message", encodeGrpcMessage(message));
            responseState.endMessage();
        }

        /**
         * Percent-encodes a grpc-message value (gRPC HTTP/2 protocol spec
         * "Percent-Encoding"): printable ASCII other than '%' is sent as it
         * is, every other byte of the UTF-8 form as %XX.
         */
        private String encodeGrpcMessage(String message) {
            if (message == null) {
                return "";
            }
            StringBuilder out = new StringBuilder(message.length());
            byte[] octets = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < octets.length; i++) {
                int b = octets[i] & 0xff;
                if (b >= 0x20 && b <= 0x7e && b != '%') {
                    out.append((char) b);
                } else {
                    out.append('%');
                    out.append(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
                    out.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
                }
            }
            return out.toString();
        }

        @Override
        public void sendError(Throwable cause) {
            if (sent) {
                return;
            }
            if (cause != null) {
                events().error("log.grpc_internal_error").thrown(cause).emit();
            }
            sendError(GrpcStatus.INTERNAL, "Internal error");
        }

        private void sendFramedBody(ByteBuffer framed) {
            if (sent) {
                return;
            }
            sent = true;

            responseState.status(HttpStatus.OK.code);
            responseState.header("content-type", CONTENT_TYPE_GRPC);
            responseState.bodyContent(framed);
            // the call's outcome is carried in trailers; a gRPC client that
            // does not see grpc-status treats the call as failed
            responseState.header("grpc-status", "0");
            responseState.endMessage();
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
                this.channel = ByteBufferChannel.withLeadingReserve(
                        GrpcFraming.HEADER_SIZE, 1024);
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
                int payloadLength = channel.payloadLength();
                ByteBuffer framed = channel.finishWithLeadingReserve();
                GrpcFraming.writeHeader(framed, payloadLength);
                sendFramedBody(framed);
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
