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
import java.security.Principal;
import java.net.SocketAddress;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
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
        HttpRequestHandler stream = handler.openStream(new CapturingState());
        Headers headers = new Headers();
        headers.add(new Header(":path", "/gumdroptest.Echo/SayEcho"));
        headers.add(new Header("content-type", "application/json"));
        CapturingState state = new CapturingState();
        stream.headers(state, headers);
        assertEquals(HttpStatus.NOT_FOUND, state.responseStatus);
    }

    @Test
    public void invalidPathIsNotFound() {
        GrpcRequestHandler handler = new GrpcRequestHandler(echoProto, NOOP_SERVICE);
        HttpRequestHandler stream = handler.openStream(new CapturingState());
        Headers headers = grpcHeaders("/not-a-grpc-path");
        CapturingState state = new CapturingState();
        stream.headers(state, headers);
        assertEquals(HttpStatus.NOT_FOUND, state.responseStatus);
    }

    @Test
    public void validGrpcPostDispatchesToGrpcHandler() {
        GrpcRequestHandler handler = new GrpcRequestHandler(echoProto, NOOP_SERVICE);
        CapturingState state = new CapturingState();
        HttpRequestHandler stream = handler.openStream(state);
        stream.headers(state, grpcHeaders("/gumdroptest.Echo/SayEcho"));
        stream.startRequestBody(state);
        assertEquals(HttpStatus.OK, state.responseStatus);
        assertEquals("12", state.responseHeaders.getValue("grpc-status"));
    }

    private static Headers grpcHeaders(String path) {
        Headers headers = new Headers();
        headers.add(new Header(":path", path));
        headers.add(new Header("content-type", "application/grpc"));
        return headers;
    }

    private static final class CapturingState implements HttpResponseState {
        Headers responseHeaders;
        HttpStatus responseStatus;

        @Override
        public void headers(Headers headers) {
            this.responseHeaders = headers;
            String code = headers.getValue(":status");
            this.responseStatus = code != null
                    ? HttpStatus.fromCode(Integer.parseInt(code)) : null;
        }

        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { }
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
