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
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.protobuf.ProtobufParseException;
import org.bluezoo.protobuf.ProtobufParser;

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

    public void unaryCall(HttpClient httpClient, String path,
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

    private void unaryCall(HttpClient httpClient, String path,
                           ByteBuffer requestMessage,
                           GrpcResponseHandler handler,
                           String responseTypeName) {
        ByteBuffer framed = GrpcFraming.frame(requestMessage);

        HttpRequest request = httpClient.post(path,
                new StreamingResponseHandler(handler, protoFile, responseTypeName));
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");

        request.bodyContent(framed);
        request.endMessage();
    }

    /**
     * Performs a unary gRPC call with a pre-serialized request message.
     */
    public void unaryCall(HttpClient httpClient, String path,
                          ByteBuffer requestMessage,
                          GrpcResponseHandler handler) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
        unaryCall(httpClient, path, requestMessage, handler, responseTypeName);
    }

    private static final class StreamingResponseHandler implements HttpResponseHandler {

        private final GrpcResponseHandler handler;
        private final ProtoFile protoFile;
        private final String defaultResponseTypeName;
        private boolean failed;
        private boolean bodyStarted;
        private GrpcFrameParser frameParser;
        private ProtobufParser protobufParser;
        private ProtoModelAdapter protoAdapter;
        private ProtoMessageHandler messageHandler;
        private String grpcStatus;
        private String grpcMessage;

        StreamingResponseHandler(GrpcResponseHandler handler, ProtoFile protoFile,
                String defaultResponseTypeName) {
            this.handler = handler;
            this.protoFile = protoFile;
            this.defaultResponseTypeName = defaultResponseTypeName;
        }

        @Override
        public void status(int code) {
            HttpStatus status = HttpStatus.fromCode(code);
            if (!status.isSuccess()) {
                fail(new GrpcException("gRPC error: " + status));
            }
        }

        @Override
        public void failed(Exception cause) {
            fail(cause);
        }

        @Override
        public void header(String name, ByteBuffer valueBuffer) {
            // gRPC delivers the RPC-level outcome as trailers (grpc-status /
            // grpc-message, gRPC HTTP/2 protocol spec), not as the HTTP
            // status -- a 200 OK with zero body bytes plus a non-zero
            // grpc-status is a normal, successful-at-the-HTTP-layer error
            // response (e.g. an RPC that aborts before writing any
            // message). Without capturing these, such a response fell
            // through to the generic "Incomplete gRPC response frame"
            // failure, discarding the real status code and message.
            // header() is called for both leading and trailing fields, so
            // match by name rather than assuming position.
            if ("grpc-status".equalsIgnoreCase(name)) {
                grpcStatus = text(valueBuffer);
            } else if ("grpc-message".equalsIgnoreCase(name)) {
                grpcMessage = text(valueBuffer);
            }
        }

        private String text(ByteBuffer b) {
            byte[] octets = new byte[b.remaining()];
            b.duplicate().get(octets);
            return new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        /** Starts reading the response message; called with the first of the body. */
        private void startResponseBody() {
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
        public void bodyContent(ByteBuffer data) {
            if (!bodyStarted) {
                bodyStarted = true;
                startResponseBody();
            }
            if (!failed && frameParser != null && data != null && data.hasRemaining()) {
                frameParser.receive(data);
            }
        }

        @Override
        public void endMessage() {
            if (failed) {
                return;
            }
            // A genuinely truncated frame (bytes cut off mid-message) is
            // always wrong regardless of what the trailers turn out to
            // say, so that check is made first. An incomplete *message* is
            // not necessarily wrong on its own, though: a trailers-only
            // error response (no DATA frame at all, e.g. an RPC that
            // aborts immediately) legitimately never starts one -- so
            // that check waits for grpc-status to have arrived, rather
            // than being treated as a framing error.
            if (frameParser != null && frameParser.hasPartialFrame()) {
                fail(new GrpcException("Incomplete gRPC response frame"));
                return;
            }
            if (grpcStatus != null && !"0".equals(grpcStatus)) {
                String message = grpcMessage != null ? decodeGrpcMessage(grpcMessage) : null;
                fail(new GrpcException("gRPC error " + grpcStatus
                        + (message != null && !message.isEmpty() ? ": " + message : "")));
                return;
            }
            // frameParser is null for a "Trailers-Only" response (gRPC
            // HTTP/2 protocol spec): no DATA frame at all, :status and
            // grpc-status/grpc-message combined into the single response
            // HEADERS frame, since the first body content (which creates
            // it) is only reached when the response has body bytes. A successful grpc-status here with no
            // frameParser and no message ever delivered is itself a
            // protocol violation (a unary RPC must return exactly one
            // message on success), not a client-side framing bug -- but
            // is left unflagged rather than guessed at, since gumdrop's
            // gRPC client only handles unary calls today and a well-behaved
            // server won't produce this combination.
            if (frameParser != null && !frameParser.isMessageCompleted()) {
                fail(new GrpcException("Incomplete gRPC response frame"));
            }
        }

        /**
         * Decodes a grpc-message trailer value (gRPC HTTP/2 protocol spec
         * "Percent-Encoding"): bytes outside printable ASCII minus '%' are
         * escaped as %XX, and the decoded bytes are UTF-8.
         */
        private String decodeGrpcMessage(String encoded) {
            if (encoded.indexOf('%') < 0) {
                return encoded;
            }
            byte[] raw = new byte[encoded.length()];
            int len = 0;
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c == '%' && i + 2 < encoded.length()) {
                    int hi = Character.digit(encoded.charAt(i + 1), 16);
                    int lo = Character.digit(encoded.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        raw[len++] = (byte) ((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                raw[len++] = (byte) c;
            }
            return new String(raw, 0, len, java.nio.charset.StandardCharsets.UTF_8);
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
