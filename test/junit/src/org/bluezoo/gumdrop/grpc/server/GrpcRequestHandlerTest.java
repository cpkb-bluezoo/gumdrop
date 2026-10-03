/*
 * GrpcRequestHandlerTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.ContentType;

import java.nio.ByteBuffer;
import java.security.Principal;
import java.net.SocketAddress;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.testsupport.ResponseRecorder;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcRequestHandler} configuration (SEC-011).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcRequestHandlerTest {

    private static ProtoFile echoProto;
    private static final ProtoFile PROTO = ProtoFile.builder().build();
    private static final GrpcServer NOOP_SERVICE = new GrpcServer() {
        @Override
        public ProtoMessageHandler startUnaryCall(String path, GrpcResponseSender response) {
            return null;
        }
    };

    @BeforeClass
    public static void loadEchoProto() throws Exception {
        echoProto = ProtoFileParser.parse(
                "syntax = \"proto3\";\n"
                        + "package gumdroptest;\n"
                        + "message EchoRequest { string message = 1; }\n"
                        + "message EchoResponse { string message = 1; }\n"
                        + "service Echo { rpc SayEcho(EchoRequest) returns (EchoResponse); }\n");
    }

    @Test
    public void testDefaultMaxMessageSize() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        assertEquals(GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, handler.getMaxMessageSize());
    }

    @Test
    public void testSetMaxMessageSize() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        handler.maxMessageSize(8192);
        assertEquals(8192, handler.getMaxMessageSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxMessageSizeRejectsNegative() {
        GrpcRequestHandler handler = new GrpcRequestHandler(PROTO, NOOP_SERVICE);
        handler.maxMessageSize(-1);
    }

    @Test
    public void nonGrpcContentTypeIsNotFound() {
        GrpcRequestHandler handler = new GrpcRequestHandler(echoProto, NOOP_SERVICE);
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = handler.openStream(state);
        stream.target(ascii("/gumdroptest.Echo/SayEcho"));
        stream.contentType(new ContentType("application", "json", null));
        stream.endHeaders();
        assertEquals(HttpStatus.NOT_FOUND, state.responseStatus());
    }

    @Test
    public void invalidPathIsNotFound() {
        GrpcRequestHandler handler = new GrpcRequestHandler(echoProto, NOOP_SERVICE);
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = handler.openStream(state);
        grpcHeaders(stream, "/not-a-grpc-path");
        assertEquals(HttpStatus.NOT_FOUND, state.responseStatus());
    }

    @Test
    public void validGrpcPostDispatchesToGrpcHandler() {
        GrpcRequestHandler handler = new GrpcRequestHandler(echoProto, NOOP_SERVICE);
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = handler.openStream(state);
        grpcHeaders(stream, "/gumdroptest.Echo/SayEcho");
        assertEquals(HttpStatus.OK, state.responseStatus());
        assertEquals("12", state.recorder.getValue("grpc-status"));
    }

    private static ByteBuffer ascii(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** Delivers the header section of a gRPC call to {@code path}. */
    private static void grpcHeaders(HttpRequestHandler stream, String path) {
        stream.target(ascii(path));
        stream.contentType(new ContentType("application", "grpc", null));
        stream.endHeaders();
    }

    private static final class CapturingState implements HttpResponse {
        final ResponseRecorder recorder = new ResponseRecorder();

        HttpStatus responseStatus() {
            return recorder.isStarted() ? HttpStatus.fromCode(recorder.getStatus()) : null;
        }

        @Override public void status(int code) { recorder.status(code); }
        @Override public void header(String name, String value) { recorder.header(name, value); }
        @Override public void endHeaders() { recorder.endHeaders(); }
        @Override public void bodyContent(ByteBuffer data) { recorder.bodyContent(); }
        @Override public void endMessage() { recorder.endMessage(); }
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
