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
import java.util.Arrays;

import org.bluezoo.gumdrop.grpc.GrpcCompression;
import org.bluezoo.gumdrop.grpc.GrpcEventHandler;
import org.bluezoo.gumdrop.grpc.GrpcFrameParser;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.GrpcTimeout;
import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.ContentEncoding.ContentEncodingException;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.ContentEncoding.Decoder;
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
    private Coding[] encodings = GrpcCompression.DEFAULT_ENCODINGS;

    public GrpcClient(ProtoFile protoFile) {
        this.protoFile = protoFile;
    }

    /**
     * Returns the compression algorithms this client decodes in responses and
     * offers in {@code grpc-accept-encoding}, besides {@code identity}.
     *
     * @return the algorithms
     */
    public Coding[] getEncodings() {
        return encodings.clone();
    }

    /**
     * Sets the compression algorithms this client decodes in responses and
     * offers in {@code grpc-accept-encoding}, besides {@code identity}, which
     * is always allowed. The default is {@code gzip} and {@code deflate}, the
     * algorithms of the gRPC specification. Add {@link Coding#BR} (Brotli) to
     * allow it too: it is an extension that only works with a server that has
     * it as well.
     *
     * @param encodings the algorithms
     * @return this client
     */
    public GrpcClient encodings(Coding... encodings) {
        this.encodings = encodings.clone();
        return this;
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
        }, responseTypeName, null);
    }

    private GrpcClientCall call(HttpClient httpClient, String path,
                                ByteBuffer requestMessage,
                                GrpcResponseHandler handler,
                                String responseTypeName,
                                GrpcCallOptions options) {
        ByteBuffer framed = GrpcFraming.frame(requestMessage);
        CallHandle call = open(httpClient, path, handler, responseTypeName, options);
        call.request.bodyContent(framed);
        call.request.endMessage();
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
        return call(httpClient, path, requestMessage, handler, responseTypeName, null);
    }

    /**
     * As {@link #call(HttpClient, String, ByteBuffer, GrpcResponseHandler)},
     * with request metadata and a deadline.
     *
     * @param httpClient a connected HTTP client
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param requestMessage the serialised request message
     * @param handler receives the response messages, the metadata and the end
     * @param options the metadata and deadline of the call, or {@code null}
     * @return the call
     */
    public GrpcClientCall call(HttpClient httpClient, String path,
                               ByteBuffer requestMessage,
                               GrpcResponseHandler handler,
                               GrpcCallOptions options) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
        return call(httpClient, path, requestMessage, handler, responseTypeName, options);
    }

    /**
     * As {@link #call(HttpClient, String, GrpcResponseHandler)}, with request
     * metadata and a deadline.
     *
     * @param httpClient a connected HTTP client
     * @param path the gRPC path ({@code /package.Service/Method})
     * @param handler receives the response messages, the metadata and the end
     * @param options the metadata and deadline of the call, or {@code null}
     * @return the call
     */
    public GrpcClientCall call(HttpClient httpClient, String path,
                               GrpcResponseHandler handler,
                               GrpcCallOptions options) {
        RpcDescriptor rpc = protoFile.getRpcByPath(path);
        String responseTypeName = rpc != null ? rpc.getOutputTypeName() : null;
        CallHandle call = open(httpClient, path, handler, responseTypeName, options);
        // send the header section now: the first message must not wait for
        // a second one, or for the half-close, to go out
        call.request.endHeaders();
        return call;
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
        return call(httpClient, path, handler, (GrpcCallOptions) null);
    }

    /**
     * Opens the request of a call: the headers, the metadata and the
     * deadline. Nothing of the body has gone out.
     */
    private CallHandle open(HttpClient httpClient, String path,
                            GrpcResponseHandler handler, String responseTypeName,
                            GrpcCallOptions options) {
        CallHandle call = new CallHandle();
        final StreamingResponseHandler responses =
                new StreamingResponseHandler(handler, protoFile, responseTypeName, call,
                        encodings);
        HttpRequest request = httpClient.post(path, responses);
        request.header("Content-Type", CONTENT_TYPE_GRPC);
        request.header("Te", "trailers");
        request.header("grpc-accept-encoding", GrpcCompression.acceptEncoding(encodings));
        call.request = request;
        if (options == null) {
            return call;
        }
        GrpcMetadata metadata = options.getMetadata();
        for (int i = 0; i < metadata.size(); i++) {
            request.header(metadata.keyAt(i), metadata.wireValueAt(i));
        }
        long timeout = options.remainingNanos();
        if (timeout >= 0) {
            request.header("grpc-timeout", GrpcTimeout.format(timeout));
            long millis = timeout / 1000000L;
            if (timeout % 1000000L != 0) {
                millis++;
            }
            // enforced here as well as by the server; a transport with no
            // timers leaves it to the server
            call.deadlineTimer = request.scheduleTimer(millis, new Runnable() {
                @Override
                public void run() {
                    responses.deadlineExceeded();
                }
            });
        }
        return call;
    }

    private static final class CallHandle implements GrpcClientCall {

        HttpRequest request;
        boolean requestEnded;
        /** The response has ended or failed: nothing more is sent. */
        boolean finished;
        TimerHandle deadlineTimer;
        private boolean cancelled;
        /** Framed request data the transport has not taken yet. */
        private ByteBuffer pending;

        /** The call is over: nothing more is sent and its deadline no longer matters. */
        void finish() {
            finished = true;
            if (deadlineTimer != null) {
                deadlineTimer.cancel();
                deadlineTimer = null;
            }
        }

        @Override
        public void cancel() {
            if (cancelled || finished) {
                return;
            }
            cancelled = true;
            finish();
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
        private final Coding[] encodings;
        private String responseEncoding;
        private Decoder decoder;
        private boolean failed;
        private boolean completed;
        private boolean bodyStarted;
        private boolean headersEnded;
        private boolean initialDelivered;
        private final GrpcMetadata headerMetadata = new GrpcMetadata();
        private final GrpcMetadata trailerMetadata = new GrpcMetadata();
        /** Bytes of the current message the protobuf parser has not consumed yet. */
        private ByteBuffer pending;
        private GrpcFrameParser frameParser;
        private ProtobufParser protobufParser;
        private ProtoModelAdapter protoAdapter;
        private ProtoMessageHandler messageHandler;
        private String grpcStatus;
        private String grpcMessage;

        StreamingResponseHandler(GrpcResponseHandler handler, ProtoFile protoFile,
                String defaultResponseTypeName, CallHandle call, Coding[] encodings) {
            this.encodings = encodings;
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
            } else if ("grpc-encoding".equalsIgnoreCase(name)) {
                responseEncoding = text(valueBuffer);
            } else {
                String key = name.toLowerCase(java.util.Locale.ROOT);
                if (!GrpcMetadata.isReserved(key)) {
                    // a field after the header section ended, or once the
                    // body has begun, is a trailer
                    GrpcMetadata target = headersEnded || bodyStarted
                            ? trailerMetadata : headerMetadata;
                    target.addReceived(key, text(valueBuffer));
                }
            }
        }

        @Override
        public void endHeaders() {
            headersEnded = true;
            // a status in the header block means a response with no
            // messages, whose metadata is the trailing metadata
            if (grpcStatus == null) {
                deliverInitialMetadata();
            }
        }

        private void deliverInitialMetadata() {
            if (!initialDelivered && !failed) {
                initialDelivered = true;
                handler.onInitialMetadata(headerMetadata);
            }
        }

        /** Tells the handler that the call has been out of time. */
        void deadlineExceeded() {
            if (call.finished) {
                return;
            }
            fail(new GrpcException(GrpcStatus.DEADLINE_EXCEEDED, "Deadline exceeded"));
            complete(GrpcStatus.DEADLINE_EXCEEDED, "Deadline exceeded");
            call.request.cancel();
        }

        private String text(ByteBuffer b) {
            byte[] octets = new byte[b.remaining()];
            b.duplicate().get(octets);
            return new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (!bodyStarted) {
                deliverInitialMetadata();
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
            if (!failed) {
                handler.onTrailingMetadata(initialDelivered ? trailerMetadata : headerMetadata);
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
            call.finish();
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
            call.finish();
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
                if (compressionFlag != 0 && !startDecoder()) {
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

            /**
             * Sets up the decoder for a compressed message, which the call's
             * {@code grpc-encoding} must have named and this client offered.
             *
             * @return false if the call has failed instead
             */
            private boolean startDecoder() {
                if (GrpcCompression.isIdentity(responseEncoding)) {
                    abandon(new GrpcException(GrpcStatus.INTERNAL,
                            "Compressed message without grpc-encoding"));
                    return false;
                }
                Coding coding = GrpcCompression.coding(responseEncoding);
                if (coding == null || !Arrays.asList(encodings).contains(coding)) {
                    abandon(new GrpcException(GrpcStatus.UNIMPLEMENTED,
                            "Unsupported grpc-encoding: " + responseEncoding));
                    return false;
                }
                decoder = GrpcCompression.newDecoder(coding, frameParser.getMaxMessageSize());
                return true;
            }

            /** Fails the call and cancels the stream, which can no longer be used. */
            private void abandon(GrpcException e) {
                if (decoder != null) {
                    decoder.close();
                    decoder = null;
                }
                fail(e);
                call.request.cancel();
            }

            @Override
            public void messageData(ByteBuffer data) {
                if (failed) {
                    return;
                }
                try {
                    if (decoder != null) {
                        decoder.write(data, false);
                        drainDecoder();
                    } else {
                        parse(data);
                    }
                } catch (ContentEncodingException e) {
                    abandon(GrpcCompression.failure(e));
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
                    if (decoder != null) {
                        decoder.write(ByteBuffer.allocate(0), true);
                        drainDecoder();
                        decoder.close();
                        decoder = null;
                    }
                    protobufParser.close();
                    protoAdapter.endRootMessage();
                } catch (ContentEncodingException e) {
                    abandon(GrpcCompression.failure(e));
                } catch (ProtoParseException | ProtobufParseException e) {
                    fail(e);
                }
            }

            /** Feeds what the decoder has produced so far to the protobuf parser. */
            private void drainDecoder() throws ProtobufParseException {
                ByteBuffer chunk;
                while ((chunk = decoder.readDecoded()) != null) {
                    parse(chunk);
                }
            }

            /**
             * Feeds bytes of the message to the protobuf parser. If it stops
             * part-way through a field, its unconsumed tail goes ahead of the
             * next bytes.
             */
            private void parse(ByteBuffer data) throws ProtobufParseException {
                ByteBuffer input = data;
                if (pending != null) {
                    input = ByteBuffer.allocate(pending.remaining() + data.remaining());
                    input.put(pending);
                    input.put(data);
                    input.flip();
                    pending = null;
                }
                protobufParser.receive(input);
                if (input.hasRemaining()) {
                    pending = ByteBuffer.allocate(input.remaining());
                    pending.put(input);
                    pending.flip();
                }
            }

            @Override
            public void parseError(String message) {
                fail(new GrpcException(GrpcStatus.INTERNAL, message));
            }
        }
    }
}
