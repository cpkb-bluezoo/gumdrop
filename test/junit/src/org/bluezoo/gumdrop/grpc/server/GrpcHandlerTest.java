/*
 * GrpcHandlerTest.java
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.net.SocketAddress;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.RpcDescriptor;
import org.bluezoo.gumdrop.testsupport.ResponseRecorder;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unary gRPC request handling on {@link GrpcHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcHandlerTest {

    private static final String ECHO_PROTO =
            "syntax = \"proto3\";\n"
                    + "package gumdroptest;\n"
                    + "message EchoRequest {\n"
                    + "  string message = 1;\n"
                    + "  int32 repeat_count = 2;\n"
                    + "}\n"
                    + "message EchoResponse {\n"
                    + "  string message = 1;\n"
                    + "  int32 length = 2;\n"
                    + "}\n"
                    + "service Echo {\n"
                    + "  rpc SayEcho(EchoRequest) returns (EchoResponse);\n"
                    + "  rpc Watch(EchoRequest) returns (stream EchoResponse);\n"
                    + "  rpc Collect(stream EchoRequest) returns (EchoResponse);\n"
                    + "  rpc Chat(stream EchoRequest) returns (stream EchoResponse);\n"
                    + "}\n";

    private static final String RPC_PATH = "/gumdroptest.Echo/SayEcho";
    private static ProtoFile protoFile;
    private static RpcDescriptor sayEchoRpc;

    @BeforeClass
    public static void parseProto() throws Exception {
        protoFile = ProtoFileParser.parse(ECHO_PROTO);
        sayEchoRpc = protoFile.getRpcByPath(RPC_PATH);
        assertNotNull(sayEchoRpc);
    }

    @Test
    public void unknownRpcReturnsNotFound() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, NOOP_SERVER, state, "/unknown.Service/Method",
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, null);
        handler.endHeaders();
        assertEquals(HttpStatus.NOT_FOUND, statusOf(state.headers));
    }

    @Test
    public void unimplementedServiceReturnsGrpcStatus12() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, NOOP_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        assertEquals(HttpStatus.OK, statusOf(state.headers));
        assertEquals("12", state.headers.getValue("grpc-status"));
        assertEquals("Unimplemented", state.headers.getValue("grpc-message"));
    }

    @Test
    public void missingRequestBodyReturnsBadRequest() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endMessage();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(state.headers));
    }

    @Test
    public void successfulUnaryCallReturnsFramedResponse() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        handler.bodyContent(encodeEchoRequest("hello", 1));
        handler.endMessage();

        assertEquals(HttpStatus.OK, statusOf(state.headers));
        assertEquals("application/grpc", state.headers.getValue("content-type"));
        assertTrue(state.body.size() > GrpcFraming.HEADER_SIZE);
        ByteBuffer framed = ByteBuffer.wrap(state.body.toByteArray());
        int len = GrpcFraming.readHeader(framed, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        assertEquals(len, framed.remaining());
    }

    @Test
    public void successfulUnaryCallEndsWithGrpcStatusZeroTrailer() throws Exception {
        // gRPC clients other than Gumdrop's treat a response with no
        // grpc-status trailer as a failed call
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        handler.bodyContent(encodeEchoRequest("hello", 1));
        handler.endMessage();

        assertEquals("[grpc-status: 0]", state.trailers.toString());
        assertEquals(1, state.completeCount);
    }

    @Test
    public void grpcMessageIsPercentEncoded() throws Exception {
        // gRPC HTTP/2 protocol: grpc-message is printable ASCII with
        // everything else, and "%", escaped as UTF-8 %XX
        CapturingState state = new CapturingState();
        final GrpcCall[] sender = new GrpcCall[1];
        GrpcServer server = new GrpcServer() {
            public ProtoMessageHandler startCall(String path, GrpcCall response) {
                sender[0] = response;
                return new ProtoDefaultHandler();
            }
        };
        GrpcHandler handler = new GrpcHandler(protoFile, server, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        sender[0].sendError(3, "bad value caf\u00e9 100%");
        assertEquals("bad value caf%C3%A9 100%25", state.headers.getValue("grpc-message"));
    }

    @Test
    public void streamingRpcsAreUnimplementedAndNeverReachTheServer() throws Exception {
        String[] paths = {"/gumdroptest.Echo/Watch", "/gumdroptest.Echo/Collect",
            "/gumdroptest.Echo/Chat"};
        for (int i = 0; i < paths.length; i++) {
            CapturingState state = new CapturingState();
            final int[] calls = new int[1];
            GrpcServer server = new GrpcServer() {
                public ProtoMessageHandler startCall(String path, GrpcCall call) {
                    calls[0]++;
                    return new ProtoDefaultHandler();
                }
            };
            GrpcHandler handler = new GrpcHandler(protoFile, server, state, paths[i],
                    GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, protoFile.getRpcByPath(paths[i]));
            handler.endHeaders();
            assertEquals(paths[i], HttpStatus.OK, statusOf(state.headers));
            assertEquals(paths[i], "12", state.headers.getValue("grpc-status"));
            assertEquals(paths[i], 0, calls[0]);
            assertEquals(paths[i], 0, state.body.size());
        }
    }

    @Test
    public void callExposesTheRpcBeingServed() throws Exception {
        CapturingState state = new CapturingState();
        final GrpcCall[] seen = new GrpcCall[1];
        GrpcServer server = new GrpcServer() {
            public ProtoMessageHandler startCall(String path, GrpcCall call) {
                seen[0] = call;
                return new ProtoDefaultHandler();
            }
        };
        GrpcHandler handler = new GrpcHandler(protoFile, server, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        assertSame(sayEchoRpc, seen[0].getRpc());
        assertEquals("SayEcho", seen[0].getRpc().getName());
        assertEquals(false, seen[0].getRpc().isClientStreaming());
        assertEquals(false, seen[0].getRpc().isServerStreaming());
    }

    @Test
    public void errorsAreTrailersOnlyWithNoBody() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, NOOP_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        assertEquals("12", state.headers.getValue("grpc-status"));
        assertTrue(state.trailers.isEmpty());
        assertEquals(0, state.body.size());
    }

    @Test
    public void truncatedFrameReturnsBadRequest() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, state, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.endHeaders();
        ByteBuffer full = encodeEchoRequest("x", 0);
        ByteBuffer partial = ByteBuffer.wrap(full.array(), 0, full.remaining() - 2);
        handler.bodyContent(partial);
        handler.endMessage();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(state.headers));
    }

    private static HttpStatus statusOf(ResponseRecorder headers) {
        return headers.isStarted() ? HttpStatus.fromCode(headers.getStatus()) : null;
    }

    private static ByteBuffer encodeEchoRequest(String message, int repeatCount) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(128);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", message);
        serializer.field(writer, "repeat_count", repeatCount);
        serializer.endMessage();
        return GrpcFraming.frame(channel.toByteBuffer());
    }

    private static final GrpcServer NOOP_SERVER = new GrpcServer() {
        @Override
        public ProtoMessageHandler startCall(String path, GrpcCall response) {
            return null;
        }
    };

    private static final GrpcServer ECHO_SERVER = new GrpcServer() {
        @Override
        public ProtoMessageHandler startCall(String path, GrpcCall response) {
            return new ProtoDefaultHandler() {
                @Override
                public void endMessage() throws ProtoParseException {
                    try {
                        GrpcResponseMessage msg = response.openMessage(null);
                        ProtoModelSerializer ser = msg.getSerializer();
                        ProtobufWriter w = msg.getWriter();
                        ser.startMessage(w, "gumdroptest.EchoResponse");
                        ser.field(w, "message", "echo");
                        ser.field(w, "length", 4);
                        ser.endMessage();
                        msg.complete();
                    } catch (Exception e) {
                        throw new ProtoParseException(e.getMessage(), e);
                    }
                }
            };
        }
    };

    private static final class CapturingState implements HttpResponse {
        final ResponseRecorder headers = new ResponseRecorder();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final java.util.List<String> trailers = new java.util.ArrayList<String>();
        boolean bodySeen;
        int completeCount;

        @Override
        public void status(int code) {
            headers.status(code);
        }

        @Override
        public void header(String name, ByteBuffer rawValue) {
            String value = java.nio.charset.StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()).toString();
            headers.header(name, value);
            if (bodySeen) {
                trailers.add(name + ": " + value);
            }
        }

        @Override
        public void endHeaders() {
            headers.endHeaders();
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            bodySeen = true;
            headers.bodyContent();
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void endMessage() {
            headers.endMessage();
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
        @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) { }
        @Override public boolean endPushPromise() { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
        @Override public boolean sendDatagram(ByteBuffer data) { return false; }
        @Override public boolean sendCapsule(long type, ByteBuffer value) { return false; }
        @Override public boolean acceptConnectIp() { return false; }
    }
}
