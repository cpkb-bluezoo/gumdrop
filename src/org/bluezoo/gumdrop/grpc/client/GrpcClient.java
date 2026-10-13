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
import org.bluezoo.gumdrop.grpc.GrpcStatus;
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

    /**
     * Makes a call, delivering the response message to a
     * {@link ProtoMessageHandler}.
     *
     * <p>Errors are not reported on this form; use
     * {@link #call(HttpClient, String, ByteBuffer, GrpcResponseHandler)} to
     * receive them.
     *
     * @param httpClient a connected HTTP client
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param requestMessage the serialised request message
     * @param responseTypeName the fully qualified response message type
     * @param messageHandler receives the response message's events
     * @return the call
     */
    public GrpcClientCall call(HttpClient httpClient, String path,
                               ByteBuffer requestMessage,
                               String responseTypeName,
                               final ProtoMessageHandler messageHandler) {
        return call(httpClient, path, requestMessage, new GrpcResponseHandler() {
            @Override
            public ProtoMessageHandler startMessage(String typeName) {
                return messageHandler;
            }

            @Override
            public void onError(Exception e) {
            }
        }, responseTypeName);
    }

    private GrpcClientCall call(HttpClient httpClient, String path,
                                ByteBuffer requestMessage,
                                GrpcResponseHandler handler,
                                String responseTypeName) {
        ByteBuffer framed = GrpcFraming.frame(requestMessage);

        CallHandle call = new CallHandle();
        HttpRequest request = httpClient.post(path,
                new StreamingResponseHandler(handler, protoFile, responseTypeName, call));
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");
        call.request = request;

        request.bodyContent(framed);
        request.endMessage();
        call.requestEnded = true;
        return call;
    }

    /**
     * Makes a call with a pre-serialised request message, delivering the
     * response to a {@link GrpcResponseHandler}.
     *
     * @param httpClient a connected HTTP client
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param requestMessage the serialised request message
     * @param handler receives the response message's events, and any error
     * @return the call
     */
    public GrpcClientCall call(HttpClient httpClient, String path,
                               ByteBuffer requestMessage,
                               GrpcResponseHandler handler) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
        return call(httpClient, path, requestMessage, handler, responseTypeName);
    }

    /**
     * Starts a call with no request message, for an RPC that streams its
     * requests. Send them with {@link GrpcClientCall#send} and end them with
     * {@link GrpcClientCall#halfClose()}; the response messages are delivered
     * to the handler as they arrive.
     *
     * @param httpClient a connected HTTP client
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param handler receives the response messages, and the end of the call
     * @return the call
     */
    public GrpcClientCall call(HttpClient httpClient, String path,
                               GrpcResponseHandler handler) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;

        CallHandle call = new CallHandle();
        HttpRequest request = httpClient.post(path,
                new StreamingResponseHandler(handler, protoFile, responseTypeName, call));
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");
        // send the header section now: the first message must not wait for
        // a second one, or for the half-close, to go out
        request.endHeaders();
        call.request = request;
        return call;
    }

    private static final class CallHandle implements GrpcClientCall {

        HttpRequest request;
        boolean requestEnded;
        /** The response has ended or failed: nothing more is sent. */
        boolean finished;
        private boolean cancelled;
        /** Framed request data the transport has not taken yet. */
        private ByteBuffer pending;

        @Override
        public void cancel() {
            if (cancelled || finished) {
                return;
            }
            cancelled = true;
            request.cancel();
        }

        @Override
        public void send(ByteBuffer message) {
            if (requestEnded) {
                throw new IllegalStateException("The request has ended");
            }
            if (cancelled || finished) {
                return;
            }
            ByteBuffer framed = GrpcFraming.frame(message);
            if (pending != null && pending.hasRemaining()) {
                ByteBuffer all = ByteBuffer.allocate(pending.remaining() + framed.remaining());
                all.put(pending).put(framed).flip();
                framed = all;
            }
            request.bodyContent(framed);
            pending = framed.hasRemaining() ? framed : null;
        }

        @Override
        public void halfClose() {
            if (requestEnded || cancelled || finished) {
                return;
            }
            if (pending != null) {
                request.bodyContent(pending);
                if (pending.hasRemaining()) {
                    throw new IllegalStateException(
                            "The transport has not taken all the request data");
                }
                pending = null;
            }
            requestEnded = true;
            request.endMessage();
        }
    }

    /**
     * The gRPC status for an HTTP status that came back without one, as the
     * gRPC HTTP/2 protocol specification maps them.
     */
    private static int statusForHttp(int httpStatus) {
        switch (httpStatus) {
            case 400:
                return GrpcStatus.INTERNAL;
            case 401:
                return GrpcStatus.UNAUTHENTICATED;
            case 403:
                return GrpcStatus.PERMISSION_DENIED;
            case 404:
                return GrpcStatus.UNIMPLEMENTED;
            case 429:
            case 502:
            case 503:
            case 504:
                return GrpcStatus.UNAVAILABLE;
            default:
                return GrpcStatus.UNKNOWN;
        }
    }

    private static final class StreamingResponseHandler implements HttpResponseHandler {

        private final GrpcResponseHandler handler;
        private final ProtoFile protoFile;
        private final String defaultResponseTypeName;
        private final CallHandle call;
        private boolean failed;
        private boolean completed;
        private boolean bodyStarted;
        /** Bytes of the current message the protobuf parser has not consumed yet. */
        private ByteBuffer pending;
        private GrpcFrameParser frameParser;
        private ProtobufParser protobufParser;
        private ProtoModelAdapter protoAdapter;
        private ProtoMessageHandler messageHandler;
        private String grpcStatus;
        private String grpcMessage;

        StreamingResponseHandler(GrpcResponseHandler handler, ProtoFile protoFile,
                String defaultResponseTypeName, CallHandle call) {
            this.handler = handler;
            this.protoFile = protoFile;
            this.defaultResponseTypeName = defaultResponseTypeName;
            this.call = call;
        }

        @Override
        public void status(int code) {
            HttpStatus status = HttpStatus.fromCode(code);
            if (!status.isSuccess()) {
                fail(new GrpcException(statusForHttp(code), "HTTP " + code + " " + status));
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

        @Override
        public void bodyContent(ByteBuffer data) {
            if (!bodyStarted) {
                bodyStarted = true;
                frameParser = new GrpcFrameParser(new FrameBridge());
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
                fail(new GrpcException(GrpcStatus.INTERNAL, "incomplete response frame"));
                return;
            }
            if (grpcStatus != null && !"0".equals(grpcStatus)) {
                String message = grpcMessage != null ? decodeGrpcMessage(grpcMessage) : null;
                int status = parseStatus(grpcStatus);
                fail(new GrpcException(status, message));
                complete(status, message);
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
            // is left unflagged rather than guessed at, since a streaming
            // response may legitimately have no messages and a well-behaved
            // server won't produce this combination for a unary RPC.
            if (frameParser != null && !frameParser.isMessageCompleted()) {
                fail(new GrpcException(GrpcStatus.INTERNAL, "incomplete response frame"));
                return;
            }
            if (grpcStatus != null) {
                complete(GrpcStatus.OK,
                        grpcMessage != null ? decodeGrpcMessage(grpcMessage) : null);
            }
        }

        private void complete(int status, String message) {
            call.finished = true;
            if (!completed) {
                completed = true;
                handler.onComplete(status, message);
            }
        }

        private int parseStatus(String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return GrpcStatus.UNKNOWN;
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

        private void fail(Exception e) {
            call.finished = true;
            if (!failed) {
                failed = true;
                handler.onError(e);
            }
        }

        /**
         * Receives the response messages from the frame parser. Each gets its
         * own adapter and protobuf parser, and its own
         * {@link GrpcResponseHandler#startMessage} call.
         */
        private final class FrameBridge implements GrpcEventHandler {

            @Override
            public void startMessage(byte compressionFlag, int length) {
                if (failed) {
                    return;
                }
                String typeName = defaultResponseTypeName;
                messageHandler = handler.startMessage(typeName);
                if (messageHandler == null) {
                    fail(new GrpcException(GrpcStatus.INTERNAL, "no response handler"));
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
                pending = null;
            }

            @Override
            public void messageData(ByteBuffer data) {
                if (failed) {
                    return;
                }
                ByteBuffer input = data;
                if (pending != null) {
                    // the parser stopped part-way through a field last time:
                    // its unconsumed tail goes ahead of the new bytes
                    input = ByteBuffer.allocate(pending.remaining() + data.remaining());
                    input.put(pending);
                    input.put(data);
                    input.flip();
                    pending = null;
                }
                try {
                    protobufParser.receive(input);
                    if (input.hasRemaining()) {
                        pending = ByteBuffer.allocate(input.remaining());
                        pending.put(input);
                        pending.flip();
                    }
                } catch (ProtobufParseException e) {
                    fail(e);
                }
            }

            @Override
            public void endMessage() {
                if (failed) {
                    return;
                }
                try {
                    protobufParser.close();
                    protoAdapter.endRootMessage();
                } catch (ProtoParseException | ProtobufParseException e) {
                    fail(e);
                }
            }

            @Override
            public void parseError(String message) {
                fail(new GrpcException(GrpcStatus.INTERNAL, message));
            }
        }
    }
}
