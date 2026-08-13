/*
 * PyGrpcClientIntegrationTest.java
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

package org.bluezoo.gumdrop.grpc.pygrpc;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.grpc.client.GrpcClient;
import org.bluezoo.gumdrop.grpc.client.GrpcResponseHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's gRPC client against a real, locally-running
 * Python (grpcio) gRPC server -- not run in CI, see {@link
 * PyGrpcTestSupport}.
 *
 * <p>Same rationale as the Postfix/vsftpd/Dante/OpenLDAP/Redis/Mosquitto
 * tests: an independent implementation on the other end of the wire
 * catches bugs a same-lineage fake server can't. Writing this test
 * surfaced a real one -- see the class comment on {@code
 * GrpcClient.StreamingResponseHandler} (now capturing grpc-status/
 * grpc-message trailers) for what {@link #testAlwaysFailSurfacesGrpcStatus}
 * exercises.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PyGrpcClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private ProtoFile protoFile;

    @Before
    public void checkReachableAndParseProto() throws Exception {
        assumeTrue(PyGrpcTestSupport.NOT_REACHABLE_MESSAGE, PyGrpcTestSupport.isReachable());
        protoFile = ProtoFileParser.parse(PyGrpcTestSupport.ECHO_PROTO);
    }

    private HTTPClient newHttpClient() {
        HTTPClient client = new HTTPClient(PyGrpcTestSupport.HOST, PyGrpcTestSupport.PORT);
        client.setH2WithPriorKnowledge(true);
        return client;
    }

    private ByteBuffer encodeEchoRequest(String message, int repeatCount) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", message);
        serializer.field(writer, "repeat_count", repeatCount);
        serializer.endMessage();
        return channel.toByteBuffer();
    }

    private ByteBuffer encodeFailRequest(String reason) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.FailRequest");
        serializer.field(writer, "reason", reason);
        serializer.endMessage();
        return channel.toByteBuffer();
    }

    // ── Unary call, real request/response round trip ──

    @Test
    public void testSayEchoRoundTrip() throws Exception {
        HTTPClient httpClient = newHttpClient();
        GrpcClient grpcClient = new GrpcClient(protoFile);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        Map<String, Object> fields = new HashMap<>();

        httpClient.connect(new TestHttpHandler(error, doneLatch) {
            @Override
            public void onConnected(Endpoint endpoint) {
                try {
                    ByteBuffer request = encodeEchoRequest("ab", 3);
                    grpcClient.unaryCall(httpClient, "/gumdroptest.Echo/SayEcho", request,
                            new GrpcResponseHandler() {
                                @Override
                                public ProtoMessageHandler startMessage(String messageTypeName) {
                                    return new FieldCapturingHandler(fields, doneLatch);
                                }

                                @Override
                                public void onError(Exception e) {
                                    fail(error, doneLatch, e);
                                }
                            });
                } catch (Exception e) {
                    fail(error, doneLatch, e);
                }
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals("ababab", fields.get("message"));
        assertEquals(6, ((Number) fields.get("length")).intValue());
    }

    // ── gRPC application error: zero-body response, status via trailers ──

    @Test
    public void testAlwaysFailSurfacesGrpcStatus() throws Exception {
        HTTPClient httpClient = newHttpClient();
        GrpcClient grpcClient = new GrpcClient(protoFile);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> grpcError = new AtomicReference<>();
        AtomicReference<Exception> connError = new AtomicReference<>();

        httpClient.connect(new TestHttpHandler(connError, doneLatch) {
            @Override
            public void onConnected(Endpoint endpoint) {
                try {
                    ByteBuffer request = encodeFailRequest("integration test");
                    grpcClient.unaryCall(httpClient, "/gumdroptest.Echo/AlwaysFail", request,
                            new GrpcResponseHandler() {
                                @Override
                                public ProtoMessageHandler startMessage(String messageTypeName) {
                                    return new FieldCapturingHandler(new HashMap<>(), doneLatch);
                                }

                                @Override
                                public void onError(Exception e) {
                                    grpcError.set(e);
                                    doneLatch.countDown();
                                }
                            });
                } catch (Exception e) {
                    fail(connError, doneLatch, e);
                }
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (connError.get() != null) {
            throw connError.get();
        }
        assertTrue("expected the gRPC client to surface an error for AlwaysFail", grpcError.get() != null);
        // Status code 3 is INVALID_ARGUMENT (grpc.StatusCode.INVALID_ARGUMENT
        // in server.py); the real assertion here is that the *actual*
        // status/message reached the client at all, not the generic
        // "Incomplete gRPC response frame" it used to report for any
        // trailers-only response regardless of what the trailers said.
        String message = grpcError.get().getMessage();
        assertTrue("expected the real gRPC status in the error, got: " + message,
                message != null && message.contains("gRPC error 3")
                        && message.contains("always fails: integration test"));
    }

    // ── Shared plumbing ──

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, Exception e) {
        error.set(e);
        doneLatch.countDown();
    }

    private static final class FieldCapturingHandler extends ProtoDefaultHandler {
        private final Map<String, Object> fields;
        private final CountDownLatch doneLatch;

        FieldCapturingHandler(Map<String, Object> fields, CountDownLatch doneLatch) {
            this.fields = fields;
            this.doneLatch = doneLatch;
        }

        @Override
        public void field(String name, Object value) {
            fields.put(name, value);
        }

        @Override
        public void endMessage() {
            doneLatch.countDown();
        }
    }

    private abstract class TestHttpHandler implements HTTPClientHandler {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestHttpHandler(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void onError(Exception cause) {
            error.set(cause);
            latch.countDown();
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }
}
