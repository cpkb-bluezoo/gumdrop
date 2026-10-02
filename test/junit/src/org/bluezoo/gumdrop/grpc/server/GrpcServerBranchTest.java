/*
 * GrpcServerBranchTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Error, edge and sender-state branches of {@link GrpcHandler} and the
 * per-stream dispatcher in {@link GrpcRequestHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcServerBranchTest {

    private static final String PROTO =
            "syntax = \"proto3\";\n"
                    + "package gumdroptest;\n"
                    + "message EchoRequest { string message = 1; int32 repeat_count = 2; }\n"
                    + "message EchoResponse { string message = 1; }\n"
                    + "service Echo { rpc SayEcho(EchoRequest) returns (EchoResponse); }\n";

    private static final String RPC_PATH = "/gumdroptest.Echo/SayEcho";
    private static ProtoFile protoFile;
    private static RpcDescriptor rpc;

    @BeforeClass
    public static void parse() throws Exception {
        protoFile = ProtoFileParser.parse(PROTO);
        rpc = protoFile.getRpcByPath(RPC_PATH);
        assertNotNull(rpc);
    }

    /** Server that records the sender and optionally ends the message with a failure. */
    private static final class RecordingServer implements GrpcServer {
        GrpcResponseSender sender;
        boolean failOnEnd;

        @Override
        public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
            sender = response;
            return new ProtoDefaultHandler() {
                @Override
                public void endMessage() throws org.bluezoo.gumdrop.grpc.proto.ProtoParseException {
                    if (failOnEnd) {
                        throw new org.bluezoo.gumdrop.grpc.proto.ProtoParseException("refused");
                    }
                }
            };
        }
    }

    private static ByteBuffer frameOf(byte[] payload) {
        ByteBuffer raw = ByteBuffer.wrap(payload);
        return GrpcFraming.frame(raw);
    }

    private static ByteBuffer validRequest() throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(128);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", "hi");
        serializer.endMessage();
        return GrpcFraming.frame(channel.toByteBuffer());
    }

    private static GrpcHandler handlerFor(RecordingServer server, long max) {
        return new GrpcHandler(protoFile, server, RPC_PATH, max, rpc);
    }

    @Test
    public void garbledProtobufPayloadIsBadRequest() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = handlerFor(new RecordingServer(), GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, frameOf(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80}));
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, state.status());
        assertEquals(1, state.completeCount);
    }

    @Test
    public void truncatedProtobufFieldIsBadRequest() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = handlerFor(new RecordingServer(), GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, frameOf(new byte[] {0x0A, 0x05, 'a'}));
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, state.status());
    }

    @Test
    public void handlerFailureAtEndOfMessageIsBadRequest() throws Exception {
        CapturingState state = new CapturingState();
        RecordingServer server = new RecordingServer();
        server.failOnEnd = true;
        GrpcHandler handler = handlerFor(server, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, validRequest());
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, state.status());
        assertEquals(1, state.completeCount);
    }

    @Test
    public void oversizedFrameIsRejectedOnce() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = handlerFor(new RecordingServer(), 2);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, validRequest());
        assertEquals(HttpStatus.BAD_REQUEST, state.status());
        handler.requestBodyContent(state, validRequest());
        handler.endRequestBody(state);
        assertEquals(1, state.completeCount);
    }

    @Test
    public void emptyAndNullBodyChunksAreIgnored() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = handlerFor(new RecordingServer(), GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.requestBodyContent(state, validRequest());
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, null);
        handler.requestBodyContent(state, ByteBuffer.allocate(0));
        assertNull(state.headers);
    }

    @Test
    public void endOfBodyAfterRejectedUnimplementedCallDoesNothingMore() {
        CapturingState state = new CapturingState();
        GrpcServer none = new GrpcServer() {
            @Override
            public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
                return null;
            }
        };
        GrpcHandler handler = new GrpcHandler(protoFile, none, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, rpc);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, ByteBuffer.wrap(new byte[] {1}));
        handler.endRequestBody(state);
        assertEquals("12", state.headers.getValue("grpc-status"));
        assertEquals(1, state.completeCount);
    }

    @Test
    public void endOfBodyWithNoFramesLeavesNoResponse() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = handlerFor(new RecordingServer(), GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, state.status());
    }

    @Test
    public void startingBodyTwiceReplacesTheCall() {
        CapturingState state = new CapturingState();
        RecordingServer server = new RecordingServer();
        GrpcHandler handler = handlerFor(server, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.startRequestBody(state);
        GrpcResponseSender first = server.sender;
        handler.startRequestBody(state);
        assertNotNull(first);
        assertNotSame(first, server.sender);
    }

    @Test
    public void senderStateMachine() throws Exception {
        CapturingState state = new CapturingState();
        RecordingServer server = new RecordingServer();
        GrpcHandler handler = handlerFor(server, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.startRequestBody(state);
        GrpcResponseSender sender = server.sender;
        GrpcResponseMessage message = sender.openMessage("gumdroptest.EchoResponse");
        assertNotNull(message.getSerializer());
        ProtobufWriter writer = message.getWriter();
        ProtoModelSerializer ser = message.getSerializer();
        ser.startMessage(writer, "gumdroptest.EchoResponse");
        ser.field(writer, "message", "x");
        ser.endMessage();
        message.complete();
        assertEquals(HttpStatus.OK, state.status());
        assertEquals(1, state.completeCount);
        try {
            sender.openMessage(null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("Response already sent", expected.getMessage());
        }
        sender.sendError(5, "late");
        sender.sendError(new RuntimeException("late"));
        assertEquals(1, state.completeCount);
    }

    @Test
    public void senderErrorVariants() {
        CapturingState state = new CapturingState();
        RecordingServer server = new RecordingServer();
        GrpcHandler handler = handlerFor(server, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler.startRequestBody(state);
        server.sender.sendError(3, null);
        assertEquals("3", state.headers.getValue("grpc-status"));
        assertEquals("", state.headers.getValue("grpc-message"));

        CapturingState state2 = new CapturingState();
        RecordingServer server2 = new RecordingServer();
        GrpcHandler handler2 = handlerFor(server2, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler2.startRequestBody(state2);
        server2.sender.sendError(new IllegalStateException("boom"));
        assertEquals("13", state2.headers.getValue("grpc-status"));
        assertEquals("Internal error", state2.headers.getValue("grpc-message"));

        CapturingState state3 = new CapturingState();
        RecordingServer server3 = new RecordingServer();
        GrpcHandler handler3 = handlerFor(server3, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        handler3.startRequestBody(state3);
        server3.sender.sendError((Throwable) null);
        assertEquals("13", state3.headers.getValue("grpc-status"));
    }

    @Test
    public void streamHandlerDispatchAndEdgeCases() throws Exception {
        RecordingServer server = new RecordingServer();
        GrpcRequestHandler requestHandler = new GrpcRequestHandler(protoFile, server);
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = requestHandler.openStream(state);
        stream.requestComplete(state);
        try {
            stream.startRequestBody(state);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(HttpStatus.NOT_FOUND, state.status());
        }
        Headers headers = new Headers();
        headers.add(new Header(":path", RPC_PATH));
        headers.add(new Header("content-type", "application/grpc"));
        stream.headers(state, headers);
        stream.headers(state, headers);
        stream.startRequestBody(state);
        stream.requestBodyContent(state, validRequest());
        stream.endRequestBody(state);
        stream.requestComplete(state);
        assertNotNull(server.sender);
    }

    @Test
    public void streamHandlerRejectsMalformedPaths() {
        String[] paths = {null, "", "/", "x/y", "/nomethod", "/svc/"};
        for (int i = 0; i < paths.length; i++) {
            GrpcRequestHandler requestHandler = new GrpcRequestHandler(protoFile, new RecordingServer());
            CapturingState state = new CapturingState();
            HttpRequestHandler stream = requestHandler.openStream(state);
            Headers headers = new Headers();
            if (paths[i] != null) {
                headers.add(new Header(":path", paths[i]));
            }
            headers.add(new Header("content-type", "application/grpc"));
            stream.headers(state, headers);
            assertEquals("path " + paths[i], HttpStatus.NOT_FOUND, state.status());
        }
    }

    @Test
    public void unknownRpcWithValidShapeStillDispatchesToHandlerWhichRejects() {
        GrpcRequestHandler requestHandler = new GrpcRequestHandler(protoFile, new RecordingServer());
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = requestHandler.openStream(state);
        Headers headers = new Headers();
        headers.add(new Header(":path", "/gumdroptest.Echo/Missing"));
        headers.add(new Header("content-type", "application/grpc"));
        stream.headers(state, headers);
        stream.startRequestBody(state);
        assertEquals(HttpStatus.NOT_FOUND, state.status());
    }

    private static final class CapturingState implements HttpResponseState {
        Headers headers;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int completeCount;

        HttpStatus status() {
            if (headers == null) {
                return null;
            }
            String code = headers.getValue(":status");
            return code != null ? HttpStatus.fromCode(Integer.parseInt(code)) : null;
        }

        @Override
        public void headers(Headers headers) {
            this.headers = headers;
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            while (data.hasRemaining()) {
                body.write(data.get());
            }
        }

        @Override
        public void endResponseBody() {
        }

        @Override
        public void complete() {
            completeCount++;
        }

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_2_0; }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
        @Override public boolean sendDatagram(ByteBuffer data) { return false; }
        @Override public boolean sendCapsule(long type, ByteBuffer value) { return false; }
        @Override public boolean acceptConnectIp() { return false; }
    }
}
