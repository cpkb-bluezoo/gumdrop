/*
 * PyGrpcStreamingClientIntegrationTest.java
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
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.client.GrpcCallOptions;
import org.bluezoo.gumdrop.grpc.client.GrpcClient;
import org.bluezoo.gumdrop.grpc.client.GrpcClientCall;
import org.bluezoo.gumdrop.grpc.client.GrpcResponseHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Streaming RPCs, call metadata, deadlines and message compression of
 * gumdrop's gRPC client
 * against a real, locally-running Python (grpcio) gRPC server -- not run
 * in CI, see {@link PyGrpcTestSupport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PyGrpcStreamingClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String ECHO = "/gumdroptest.Echo/";

    private ProtoFile protoFile;
    private Gumdrop gumdrop;

    @Before
    public void checkReachableAndParseProto() throws Exception {
        assumeTrue(PyGrpcTestSupport.NOT_REACHABLE_MESSAGE, PyGrpcTestSupport.isReachable());
        protoFile = ProtoFileParser.parse(PyGrpcTestSupport.ECHO_PROTO);
        gumdrop = Gumdrop.boot();
    }

    @After
    public void tearDown() {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    /** What a call delivered, for the test thread to look at once it has ended. */
    private static final class Outcome implements GrpcResponseHandler {
        final List<String> messages = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        volatile GrpcMetadata initial;
        volatile GrpcMetadata trailing;
        volatile GrpcException status;
        volatile Exception failure;
        volatile int completedStatus = -1;

        @Override
        public ProtoMessageHandler startMessage(String messageTypeName) {
            return new ProtoDefaultHandler() {
                private String message = "";

                @Override
                public void field(String name, Object value) {
                    if ("message".equals(name)) {
                        message = (String) value;
                    }
                }

                @Override
                public void endMessage() {
                    messages.add(message);
                }
            };
        }

        @Override
        public void onInitialMetadata(GrpcMetadata metadata) {
            initial = metadata;
        }

        @Override
        public void onTrailingMetadata(GrpcMetadata metadata) {
            trailing = metadata;
        }

        @Override
        public void onError(Exception e) {
            if (e instanceof GrpcException) {
                status = (GrpcException) e;
            } else {
                fail(e);
            }
        }

        @Override
        public void onComplete(int status, String message) {
            completedStatus = status;
            done.countDown();
        }

        void fail(Exception e) {
            failure = e;
            done.countDown();
        }

        void await() throws Exception {
            assertTrue("did not complete within timeout",
                    done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Connects and runs {@code start} on the connection's thread. */
    private void connectAndRun(final Outcome outcome, final Starter start) {
        final HttpClient httpClient = new HttpClient(PyGrpcTestSupport.HOST, PyGrpcTestSupport.PORT);
        httpClient.h2WithPriorKnowledge(true);
        httpClient.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                try {
                    start.start(httpClient, new GrpcClient(protoFile));
                } catch (Exception e) {
                    outcome.fail(e);
                }
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                outcome.fail(cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
    }

    private interface Starter {
        void start(HttpClient http, GrpcClient client) throws Exception;
    }

    private ByteBuffer echoRequest(String message, int repeatCount) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", message);
        serializer.field(writer, "repeat_count", repeatCount);
        serializer.endMessage();
        return channel.toByteBuffer();
    }

    @Test
    public void serverStreamingDeliversEveryMessage() throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.call(http, ECHO + "Watch", echoRequest("x", 3), outcome);
            }
        });
        outcome.await();
        assertEquals(Arrays.asList("x1", "x2", "x3"), outcome.messages);
        assertEquals(GrpcStatus.OK, outcome.completedStatus);
    }

    @Test
    public void serverStreamingErrorAfterMessagesCarriesTheStatus() throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.call(http, ECHO + "Watch", echoRequest("fail", 1), outcome);
            }
        });
        outcome.await();
        assertEquals(Arrays.asList("first"), outcome.messages);
        assertNotNull(outcome.status);
        assertEquals(GrpcStatus.NOT_FOUND, outcome.status.getStatus());
        assertEquals(GrpcStatus.NOT_FOUND, outcome.completedStatus);
    }

    @Test
    public void clientStreamingSendsManyMessagesThenHalfCloses() throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                GrpcClientCall call = client.call(http, ECHO + "Collect", outcome);
                call.send(echoRequest("a", 0));
                call.send(echoRequest("b", 0));
                call.send(echoRequest("c", 0));
                call.halfClose();
            }
        });
        outcome.await();
        assertEquals(Arrays.asList("abc"), outcome.messages);
        assertEquals(GrpcStatus.OK, outcome.completedStatus);
    }

    @Test
    public void bidirectionalAnswersEachMessage() throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                GrpcClientCall call = client.call(http, ECHO + "Chat", outcome);
                call.send(echoRequest("a", 0));
                call.send(echoRequest("b", 0));
                call.send(echoRequest("c", 0));
                call.halfClose();
            }
        });
        outcome.await();
        assertEquals(Arrays.asList("re a", "re b", "re c"), outcome.messages);
        assertEquals(GrpcStatus.OK, outcome.completedStatus);
    }

    @Test
    public void metadataRoundTripsIncludingBinaryValues() throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                GrpcCallOptions options = new GrpcCallOptions();
                options.getMetadata().add("x-test", "hello");
                options.getMetadata().addBinary("x-test-bin", new byte[] {0, 1, 2});
                client.call(http, ECHO + "Metadata", echoRequest("m", 0), outcome, options);
            }
        });
        outcome.await();
        assertEquals(Arrays.asList("hello"), outcome.messages);
        assertEquals("hello", outcome.initial.get("x-echo"));
        assertArrayEquals(new byte[] {0, 1, 2}, outcome.initial.getBinary("x-echo-bin"));
        assertEquals("done", outcome.trailing.get("x-trailer"));
    }

    @Test
    public void aDeadlineFailsTheCallWithDeadlineExceeded() throws Exception {
        final Outcome outcome = new Outcome();
        long start = System.nanoTime();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.call(http, ECHO + "Slow", echoRequest("s", 0), outcome,
                        new GrpcCallOptions().timeout(500, TimeUnit.MILLISECONDS));
            }
        });
        outcome.await();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull(outcome.status);
        assertEquals(GrpcStatus.DEADLINE_EXCEEDED, outcome.status.getStatus());
        // the server takes three seconds, so this was cut short
        assertTrue("took " + elapsedMs + "ms", elapsedMs < 2500);
    }

    // ── Compression ──

    private static String letters(int count) {
        char[] text = new char[count];
        java.util.Arrays.fill(text, 'a');
        return new String(text);
    }

    private void echoCompressed(final Coding coding) throws Exception {
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.compression(coding).call(http, ECHO + "SayEcho",
                        echoRequest(letters(3000), 1), outcome);
            }
        });
        outcome.await();
        assertEquals(Arrays.asList(letters(3000)), outcome.messages);
        assertEquals(GrpcStatus.OK, outcome.completedStatus);
    }

    @Test
    public void gzipRequestsAreDecodedByTheServer() throws Exception {
        echoCompressed(Coding.GZIP);
    }

    @Test
    public void deflateRequestsAreDecodedByTheServer() throws Exception {
        echoCompressed(Coding.DEFLATE);
    }

    private void compressedResponse(String algorithm) throws Exception {
        final Outcome outcome = new Outcome();
        final String name = algorithm;
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.call(http, ECHO + "Compressed", echoRequest(name, 3000), outcome);
            }
        });
        outcome.await();
        assertEquals(Arrays.asList(letters(3000)), outcome.messages);
        assertEquals(GrpcStatus.OK, outcome.completedStatus);
    }

    @Test
    public void gzipResponsesAreDecoded() throws Exception {
        compressedResponse("gzip");
    }

    @Test
    public void deflateResponsesAreDecoded() throws Exception {
        compressedResponse("deflate");
    }

    @Test
    public void aResponseThatExpandsBeyondTheLimitIsRefused() throws Exception {
        // five million letters are a few KB of gzip, which the frame limit lets
        // through, so only the decoder can refuse it: had the server not
        // compressed it, the frame itself would have been too large, and the
        // status would not be RESOURCE_EXHAUSTED
        final Outcome outcome = new Outcome();
        connectAndRun(outcome, new Starter() {
            public void start(HttpClient http, GrpcClient client) throws Exception {
                client.call(http, ECHO + "Compressed", echoRequest("gzip", 5000000), outcome);
            }
        });
        outcome.await();
        assertNotNull(outcome.status);
        assertEquals(GrpcStatus.RESOURCE_EXHAUSTED, outcome.status.getStatus());
    }
}
