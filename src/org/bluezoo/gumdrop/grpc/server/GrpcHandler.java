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
import java.net.SocketAddress;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.grpc.GrpcCompression;
import org.bluezoo.gumdrop.grpc.GrpcEventHandler;
import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.grpc.GrpcFrameParser;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.GrpcTimeout;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.ContentEncoding.ContentEncodingException;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.ContentEncoding.Decoder;
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
    /** Bytes of the current message the protobuf parser has not consumed yet. */
    private ByteBuffer pending;
    private GrpcMetadata requestMetadata = new GrpcMetadata();
    private final GrpcMetadata responseMetadata = new GrpcMetadata();
    private final GrpcMetadata trailingMetadata = new GrpcMetadata();
    private long timeoutNanos = -1L;
    private String requestEncoding;
    private Coding[] encodings = GrpcCompression.DEFAULT_ENCODINGS;
    private Coding requestCoding;
    private Decoder decoder;
    private Instant deadline;
    private TimerHandle deadlineTimer;
    private int requestMessages;
    private boolean cancelledSent;
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

    /**
     * Gives the handler the request headers it will need: the call metadata
     * the {@code grpc-timeout} value, the {@code grpc-encoding} value (or
     * {@code null} for either) and the algorithms the server decodes. Called before
     * {@link #endHeaders()}.
     */
    void requestHeaders(GrpcMetadata metadata, String timeout, String encoding,
            Coding[] encodings) {
        requestMetadata = metadata != null ? metadata : new GrpcMetadata();
        timeoutNanos = timeout != null ? GrpcTimeout.parse(timeout) : -1L;
        requestEncoding = encoding;
        this.encodings = encodings;
    }

    /** Starts the call; the header section has ended. */
    @Override
    public void endHeaders() {
        bodyStarted = true;

        if (requestTypeName == null) {
            reject(HttpStatus.NOT_FOUND, "Unknown RPC");
            return;
        }

        responseSender = new GrpcCallImpl(response);
        if ((rpc.isClientStreaming() || rpc.isServerStreaming())
                && !server.isStreamingSupported()) {
            // the caller is told so, in a trailers-only response, and the
            // server never sees the call
            responseSender.sendError(GrpcStatus.UNIMPLEMENTED,
                    "Streaming RPCs are not supported");
            bodyRejected = true;
            return;
        }
        if (!GrpcCompression.isIdentity(requestEncoding)) {
            Coding coding = GrpcCompression.coding(requestEncoding);
            if (coding == null || !Arrays.asList(encodings).contains(coding)) {
                // the call never reaches the service; the response says what
                // would have been understood
                responseSender.sendError(GrpcStatus.UNIMPLEMENTED,
                        "Unsupported grpc-encoding: " + requestEncoding);
                bodyRejected = true;
                return;
            }
            requestCoding = coding;
        }
        if (timeoutNanos >= 0) {
            deadline = Instant.now().plusNanos(timeoutNanos);
        }
        requestHandler = server.startCall(path, responseSender);
        if (requestHandler == null) {
            responseSender.sendError(GrpcStatus.UNIMPLEMENTED, "Unimplemented");
            bodyRejected = true;
            return;
        }

        frameParser = new GrpcFrameParser(new FrameToProtobufBridge());
        frameParser.setMaxMessageSize(maxMessageSize);
        startDeadline();
    }

    /** Arms the timer for the client's deadline, if it set one. */
    private void startDeadline() {
        if (timeoutNanos < 0) {
            return;
        }
        long millis = timeoutNanos / 1000000L;
        if (timeoutNanos % 1000000L != 0) {
            millis++;
        }
        deadlineTimer = response.scheduleTimer(millis, new Runnable() {
            @Override
            public void run() {
                abort(GrpcStatus.DEADLINE_EXCEEDED, "Deadline exceeded");
            }
        });
    }

    private void stopDeadline() {
        if (deadlineTimer != null) {
            deadlineTimer.cancel();
            deadlineTimer = null;
        }
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (bodyRejected || !bodyStarted || frameParser == null
                || data == null || !data.hasRemaining()) {
            return;
        }
        frameParser.receive(data);
    }

    /** The request has ended: the client sent its last message. */
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
        // a unary request is exactly one message; a client stream is any number
        if (frameParser.hasPartialFrame()
                || (!rpc.isClientStreaming() && !frameParser.isMessageCompleted())) {
            reject(HttpStatus.BAD_REQUEST, "Invalid gRPC frame");
            return;
        }
        if (requestHandler instanceof GrpcCallHandler) {
            ((GrpcCallHandler) requestHandler).endRequests();
        }
    }

    /** The stream was reset or the connection failed before the call ended. */
    @Override
    public void failed(Exception cause) {
        if (responseSender != null) {
            responseSender.abandon();
        }
    }

    /**
     * A streaming call outlives its request, so a reset after the request
     * ended must still reach the handler.
     */
    @Override
    public boolean failedAfterEndMessage() {
        return true;
    }

    /**
     * Receives the request messages from the frame parser. Each message gets
     * its own adapter and protobuf parser, so a stream of them is delivered to
     * the handler as one {@code startMessage}/{@code endMessage} pair each.
     */
    private final class FrameToProtobufBridge implements GrpcEventHandler {

        @Override
        public void startMessage(byte compressionFlag, int length) {
            if (bodyRejected) {
                return;
            }
            if (requestMessages > 0 && !rpc.isClientStreaming()) {
                abort(GrpcStatus.INTERNAL, "Too many request messages");
                return;
            }
            if (compressionFlag != 0) {
                if (requestCoding == null) {
                    abort(GrpcStatus.INTERNAL, "Compressed message without grpc-encoding");
                    return;
                }
                decoder = GrpcCompression.newDecoder(requestCoding, maxMessageSize);
            }
            requestMessages++;
            protoAdapter = new ProtoModelAdapter(protoFile, requestHandler);
            try {
                protoAdapter.startRootMessage(requestTypeName);
            } catch (ProtoParseException e) {
                events().warn("log.grpc_start_request_failed").thrown(e).emit();
                reject(HttpStatus.BAD_REQUEST, "Invalid request type");
                return;
            }
            protobufParser = new ProtobufParser(protoAdapter);
            pending = null;
        }

        @Override
        public void messageData(ByteBuffer data) {
            if (bodyRejected) {
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
                compressionFailed(e);
            } catch (ProtobufParseException e) {
                events().warn("log.grpc_protobuf_parse_error").thrown(e).emit();
                reject(HttpStatus.BAD_REQUEST, "Invalid request message");
            }
        }

        @Override
        public void endMessage() {
            if (bodyRejected) {
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
                compressionFailed(e);
            } catch (ProtoParseException | ProtobufParseException e) {
                events().warn("log.grpc_complete_request_failed").thrown(e).emit();
                reject(HttpStatus.BAD_REQUEST, "Invalid request message");
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

        private void compressionFailed(ContentEncodingException e) {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
            GrpcException failure = GrpcCompression.failure(e);
            events().warn("log.grpc_decompress_failed").thrown(e).emit();
            abort(failure.getStatus(), failure.getMessage());
        }

        @Override
        public void parseError(String message) {
            events().warn("log.grpc_parse_error").attr("message", message).emit();
            reject(HttpStatus.BAD_REQUEST, "Invalid gRPC frame");
        }
    }

    /**
     * Refuses the request as malformed. Before the response has started that
     * is an HTTP error; once messages have gone out it is the call's status.
     */
    private void reject(HttpStatus status, String message) {
        if (bodyRejected) {
            return;
        }
        if (responseSender != null && responseSender.isStarted()) {
            abort(GrpcStatus.INTERNAL, message);
            return;
        }
        bodyRejected = true;
        boolean open = responseSender != null && responseSender.abandon();
        if (!open || !responseSender.isStarted()) {
            sendError(response, status, message);
        }
    }

    /** Ends the call with a gRPC status, telling the handler it is over. */
    private void abort(int status, String message) {
        if (bodyRejected) {
            return;
        }
        bodyRejected = true;
        if (responseSender != null) {
            boolean open = !responseSender.isClosed();
            responseSender.close(status, message);
            if (open) {
                notifyCancelled();
            }
        }
    }

    private void notifyCancelled() {
        if (!cancelledSent && requestHandler instanceof GrpcCallHandler) {
            cancelledSent = true;
            ((GrpcCallHandler) requestHandler).cancelled();
        }
    }

    private void sendError(HttpResponse response, HttpStatus status, String message) {
        response.status(status.code);
        response.header("content-type", "text/plain");
        response.bodyContent(ByteBuffer.wrap(message.getBytes()));
        response.endMessage();
    }

    private final class GrpcCallImpl implements GrpcCall {

        private final HttpResponse responseState;
        private boolean started;
        private boolean closed;

        GrpcCallImpl(HttpResponse responseState) {
            this.responseState = responseState;
        }

        boolean isStarted() {
            return started;
        }

        boolean isClosed() {
            return closed;
        }

        /**
         * Marks the call as over without sending anything more, because the
         * stream is gone or has been answered otherwise, and tells the
         * handler.
         *
         * @return true if the call was open
         */
        boolean abandon() {
            if (closed) {
                return false;
            }
            closed = true;
            stopDeadline();
            notifyCancelled();
            return true;
        }

        @Override
        public RpcDescriptor getRpc() {
            return rpc;
        }

        @Override
        public GrpcResponseMessage openMessage(String messageTypeName) throws IOException {
            if (closed) {
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
            close(status, message);
        }

        @Override
        public void sendError(Throwable cause) {
            if (closed) {
                return;
            }
            if (cause != null) {
                events().error("log.grpc_internal_error").thrown(cause).emit();
            }
            close(GrpcStatus.INTERNAL, "Internal error");
        }

        @Override
        public void close() {
            close(GrpcStatus.OK, null);
        }

        @Override
        public void close(int status, String message) {
            if (closed) {
                return;
            }
            closed = true;
            stopDeadline();
            boolean trailersOnly = !started;
            if (trailersOnly) {
                responseState.status(HttpStatus.OK.code);
                responseState.header("content-type", CONTENT_TYPE_GRPC);
                responseState.header("grpc-accept-encoding",
                        GrpcCompression.acceptEncoding(encodings));
                sendMetadata(responseMetadata);
            }
            sendMetadata(trailingMetadata);
            // in the trailers when messages have been sent: the call's outcome
            // is carried there, and a gRPC client that does not see grpc-status
            // treats the call as failed
            responseState.header("grpc-status", String.valueOf(status));
            if ((message != null && !message.isEmpty())
                    || (trailersOnly && status != GrpcStatus.OK)) {
                responseState.header("grpc-message", encodeGrpcMessage(message));
            }
            responseState.endMessage();
        }

        @Override
        public GrpcMetadata getRequestMetadata() {
            return requestMetadata;
        }

        @Override
        public GrpcMetadata getResponseMetadata() {
            return responseMetadata;
        }

        @Override
        public GrpcMetadata getTrailingMetadata() {
            return trailingMetadata;
        }

        @Override
        public Instant getDeadline() {
            return deadline;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return responseState.getRemoteAddress();
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return responseState.getSecurityInfo();
        }

        @Override
        public Principal getPrincipal() {
            return responseState.getPrincipal();
        }

        private void sendMetadata(GrpcMetadata metadata) {
            for (int i = 0; i < metadata.size(); i++) {
                responseState.header(metadata.keyAt(i), metadata.wireValueAt(i));
            }
        }

        @Override
        public boolean isReady() {
            return responseState.pendingResponseBytes() == 0;
        }

        @Override
        public void onReady(Runnable callback) {
            responseState.onWritable(callback);
        }

        @Override
        public void pauseRequests() {
            responseState.pauseRequestBody();
        }

        @Override
        public void resumeRequests() {
            responseState.resumeRequestBody();
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

        private void sendFramedBody(ByteBuffer framed) {
            if (closed) {
                return;
            }
            if (!started) {
                started = true;
                responseState.status(HttpStatus.OK.code);
                responseState.header("content-type", CONTENT_TYPE_GRPC);
                responseState.header("grpc-accept-encoding",
                        GrpcCompression.acceptEncoding(encodings));
                sendMetadata(responseMetadata);
            }
            responseState.bodyContent(framed);
            if (!rpc.isServerStreaming()) {
                // a single response message ends the call
                close();
            }
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
