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
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        GrpcHandler handler = new GrpcHandler(protoFile, NOOP_SERVER, "/unknown.Service/Method",
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, null);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        assertEquals(HttpStatus.NOT_FOUND, statusOf(state.headers));
    }

    @Test
    public void unimplementedServiceReturnsGrpcStatus12() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, NOOP_SERVER, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        assertEquals(HttpStatus.OK, statusOf(state.headers));
        assertEquals("12", state.headers.getValue("grpc-status"));
        assertEquals("Unimplemented", state.headers.getValue("grpc-message"));
    }

    @Test
    public void missingRequestBodyReturnsBadRequest() {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.headers(state, new Headers());
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(state.headers));
    }

    @Test
    public void successfulUnaryCallReturnsFramedResponse() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        handler.requestBodyContent(state, encodeEchoRequest("hello", 1));
        handler.endRequestBody(state);

        assertEquals(HttpStatus.OK, statusOf(state.headers));
        assertEquals("application/grpc", state.headers.getValue("content-type"));
        assertTrue(state.body.size() > GrpcFraming.HEADER_SIZE);
        ByteBuffer framed = ByteBuffer.wrap(state.body.toByteArray());
        int len = GrpcFraming.readHeader(framed, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        assertEquals(len, framed.remaining());
    }

    @Test
    public void truncatedFrameReturnsBadRequest() throws Exception {
        CapturingState state = new CapturingState();
        GrpcHandler handler = new GrpcHandler(protoFile, ECHO_SERVER, RPC_PATH,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, sayEchoRpc);
        handler.headers(state, new Headers());
        handler.startRequestBody(state);
        ByteBuffer full = encodeEchoRequest("x", 0);
        ByteBuffer partial = ByteBuffer.wrap(full.array(), 0, full.remaining() - 2);
        handler.requestBodyContent(state, partial);
        handler.endRequestBody(state);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(state.headers));
    }

    private static HttpStatus statusOf(Headers headers) {
        String code = headers.getValue(":status");
        return code != null ? HttpStatus.fromCode(Integer.parseInt(code)) : null;
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
        public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
            return null;
        }
    };

    private static final GrpcServer ECHO_SERVER = new GrpcServer() {
        @Override
        public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
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

    private static final class CapturingState implements HttpResponseState {
        Headers headers;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int completeCount;

        @Override
        public void headers(Headers headers) {
            this.headers = headers;
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.write(chunk, 0, chunk.length);
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
