/*
 * GrpcStreamingServerTest.java
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Streaming RPCs on {@link GrpcHandler}: many request messages, many
 * response messages, half-close, early errors, cancellation and backpressure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcStreamingServerTest {

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

    private static final String UNARY = "/gumdroptest.Echo/SayEcho";
    private static final String WATCH = "/gumdroptest.Echo/Watch";
    private static final String COLLECT = "/gumdroptest.Echo/Collect";
    private static final String CHAT = "/gumdroptest.Echo/Chat";

    private static ProtoFile protoFile;

    @BeforeClass
    public static void parseProto() throws Exception {
        protoFile = ProtoFileParser.parse(ECHO_PROTO);
        assertNotNull(protoFile.getRpcByPath(WATCH));
    }

    /** Records what a call's handler is told, and answers as the test scripts. */
    private static class Recorder extends ProtoDefaultHandler implements GrpcCallHandler {
        final List<String> events = new ArrayList<String>();
        GrpcCall call;
        String current;

        @Override
        public void startMessage(String typeName) {
            events.add("start");
            current = null;
        }

        @Override
        public void field(String name, Object value) {
            if ("message".equals(name)) {
                current = (String) value;
            }
        }

        @Override
        public void endMessage() throws ProtoParseException {
            events.add("end:" + current);
            onMessage(current);
        }

        @Override
        public void endRequests() {
            events.add("endRequests");
            onEndRequests();
        }

        @Override
        public void cancelled() {
            events.add("cancelled");
        }

        void onMessage(String message) throws ProtoParseException {
        }

        void onEndRequests() {
        }

        void reply(String text) throws ProtoParseException {
            try {
                GrpcResponseMessage msg = call.openMessage(null);
                ProtoModelSerializer ser = msg.getSerializer();
                ProtobufWriter w = msg.getWriter();
                ser.field(w, "message", text);
                ser.field(w, "length", text.length());
                msg.complete();
            } catch (IOException e) {
                throw new ProtoParseException(e.getMessage(), e);
            }
        }
    }

    private static GrpcServer serverOf(final Recorder recorder, final boolean streaming) {
        return new GrpcServer() {
            @Override
            public ProtoMessageHandler startCall(String path, GrpcCall call) {
                recorder.call = call;
                return recorder;
            }

            @Override
            public boolean isStreamingSupported() {
                return streaming;
            }
        };
    }

    private static GrpcHandler handlerFor(CapturingState state, Recorder recorder,
            String path) {
        return new GrpcHandler(protoFile, serverOf(recorder, true), state, path,
                GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, protoFile.getRpcByPath(path));
    }

    @Test
    public void streamingRpcsAreRefusedUnlessTheServerOptsIn() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = new GrpcHandler(protoFile, serverOf(recorder, false), state,
                WATCH, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE, protoFile.getRpcByPath(WATCH));
        handler.endHeaders();
        assertEquals("12", state.headers.getValue("grpc-status"));
        assertTrue(recorder.events.isEmpty());
        assertEquals(null, recorder.call);
    }

    @Test
    public void serverStreamingSendsManyMessagesThenTheStatus() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) throws ProtoParseException {
                reply(message + "1");
                reply(message + "2");
                reply(message + "3");
                call.close();
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.endMessage();

        assertEquals(200, state.headers.getStatus());
        assertEquals("application/grpc", state.headers.getValue("content-type"));
        assertEquals(java.util.Arrays.asList("a1", "a2", "a3"), replies(state));
        assertEquals("[grpc-status: 0]", state.trailers.toString());
        assertEquals(1, state.completeCount);
    }

    @Test
    public void serverStreamingStaysOpenUntilTheHandlerEndsIt() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) throws ProtoParseException {
                reply("only");
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.endMessage();

        assertEquals(1, replies(state).size());
        assertEquals(0, state.completeCount);
        assertTrue(state.trailers.isEmpty());

        recorder.call.close(GrpcStatus.OK, null);
        assertEquals(1, state.completeCount);
        assertEquals("[grpc-status: 0]", state.trailers.toString());
    }

    @Test
    public void unaryResponseStillEndsAfterItsMessage() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) throws ProtoParseException {
                reply("one");
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, UNARY);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.endMessage();
        assertEquals(1, state.completeCount);
        assertEquals("[grpc-status: 0]", state.trailers.toString());
    }

    @Test
    public void clientStreamingDeliversEachMessageThenTheHalfClose() throws Exception {
        CapturingState state = new CapturingState();
        final List<String> seen = new ArrayList<String>();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) {
                seen.add(message);
            }

            @Override
            void onEndRequests() {
                try {
                    reply("got " + seen.size());
                } catch (ProtoParseException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, COLLECT);
        handler.endHeaders();
        ByteBuffer two = request("b");
        // the first message whole, the second split across two chunks
        handler.bodyContent(request("a"));
        ByteBuffer rest = two.duplicate();
        rest.limit(rest.limit() - 1);
        handler.bodyContent(rest);
        assertEquals(java.util.Arrays.asList("start", "end:a", "start"), recorder.events);
        ByteBuffer last = two.duplicate();
        last.position(last.limit() - 1);
        handler.bodyContent(last);
        handler.endMessage();

        assertEquals(recorder.events.toString(), java.util.Arrays.asList("a", "b"), seen);
        assertEquals(java.util.Arrays.asList("start", "end:a", "start", "end:b", "endRequests"),
                recorder.events);
        assertEquals(java.util.Arrays.asList("got 2"), replies(state));
        assertEquals(1, state.completeCount);
    }

    @Test
    public void clientStreamingMayHaveNoMessages() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onEndRequests() {
                try {
                    reply("none");
                } catch (ProtoParseException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, COLLECT);
        handler.endHeaders();
        handler.endMessage();
        assertEquals(java.util.Arrays.asList("endRequests"), recorder.events);
        assertEquals(200, state.headers.getStatus());
        assertEquals(java.util.Arrays.asList("none"), replies(state));
    }

    @Test
    public void bidirectionalRepliesBeforeTheHalfClose() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) throws ProtoParseException {
                reply("re " + message);
            }

            @Override
            void onEndRequests() {
                call.close();
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, CHAT);
        handler.endHeaders();
        handler.bodyContent(request("x"));
        assertEquals(java.util.Arrays.asList("re x"), replies(state));
        assertEquals(0, state.completeCount);
        handler.bodyContent(request("y"));
        assertEquals(java.util.Arrays.asList("re x", "re y"), replies(state));
        handler.endMessage();
        assertEquals(1, state.completeCount);
        assertEquals("[grpc-status: 0]", state.trailers.toString());
    }

    @Test
    public void errorAfterMessagesIsSentInTheTrailers() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder() {
            @Override
            void onMessage(String message) throws ProtoParseException {
                reply("first");
                call.sendError(GrpcStatus.NOT_FOUND, "gone away");
            }
        };
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.endMessage();

        assertEquals(200, state.headers.getStatus());
        assertEquals(java.util.Arrays.asList("first"), replies(state));
        assertEquals("[grpc-status: 5, grpc-message: gone away]", state.trailers.toString());
        assertEquals(1, state.completeCount);
    }

    @Test
    public void nothingIsSentAfterTheCallEnds() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();
        recorder.call.close();
        recorder.call.close(GrpcStatus.INTERNAL, "late");
        recorder.call.sendError(GrpcStatus.INTERNAL, "later");
        assertEquals(1, state.completeCount);
        assertEquals("0", state.headers.getValue("grpc-status"));
        try {
            recorder.call.openMessage(null);
            fail("a message cannot be opened on a call that has ended");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void aResetStreamCancelsTheHandlerOnce() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, CHAT);
        assertTrue(handler.failedAfterEndMessage());
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.failed(new IOException("reset"));
        handler.failed(new IOException("again"));
        assertEquals(java.util.Arrays.asList("start", "end:a", "cancelled"), recorder.events);
        try {
            recorder.call.openMessage(null);
            fail("a cancelled call cannot send");
        } catch (IOException expected) {
            // expected
        }
        recorder.call.close();
        assertEquals(0, state.completeCount);
    }

    @Test
    public void aCallThatEndedIsNotCancelled() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.endMessage();
        recorder.call.close();
        handler.failed(new IOException("reset"));
        assertFalse(recorder.events.contains("cancelled"));
    }

    @Test
    public void aMalformedFrameMidStreamEndsTheCallAndCancelsTheHandler() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, CHAT);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        recorder.reply("hello");
        // a frame that announces compression, which is not supported
        handler.bodyContent(ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 0}));
        assertEquals("[grpc-status: 13, grpc-message: Invalid gRPC frame]",
                state.trailers.toString());
        assertEquals(1, state.completeCount);
        assertTrue(recorder.events.contains("cancelled"));
    }

    @Test
    public void aUnaryCallWithTwoRequestMessagesIsAnError() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, UNARY);
        handler.endHeaders();
        handler.bodyContent(request("a"));
        handler.bodyContent(request("b"));
        assertEquals("13", state.headers.getValue("grpc-status"));
        assertEquals(java.util.Arrays.asList("start", "end:a", "cancelled"), recorder.events);
    }

    @Test
    public void backpressureIsPassedToTheTransport() throws Exception {
        CapturingState state = new CapturingState();
        Recorder recorder = new Recorder();
        GrpcHandler handler = handlerFor(state, recorder, WATCH);
        handler.endHeaders();

        assertTrue(recorder.call.isReady());
        state.pending = 4096;
        assertFalse(recorder.call.isReady());

        final int[] ready = new int[1];
        recorder.call.onReady(new Runnable() {
            public void run() {
                ready[0]++;
            }
        });
        assertNotNull(state.writable);
        state.writable.run();
        assertEquals(1, ready[0]);

        recorder.call.pauseRequests();
        recorder.call.resumeRequests();
        assertEquals(1, state.pauseCount);
        assertEquals(1, state.resumeCount);
    }

    /** The messages of the response body, as the {@code message} fields. */
    private static List<String> replies(CapturingState state) throws Exception {
        List<String> out = new ArrayList<String>();
        ByteBuffer body = ByteBuffer.wrap(state.body.toByteArray());
        while (body.hasRemaining()) {
            int length = GrpcFraming.readHeader(body, GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
            byte[] payload = new byte[length];
            body.get(payload);
            // field 1, wire type 2: tag 0x0a, length, UTF-8
            assertEquals(0x0a, payload[0]);
            out.add(new String(payload, 2, payload[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    private static ByteBuffer request(String message) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(128);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", message);
        serializer.endMessage();
        return GrpcFraming.frame(channel.toByteBuffer());
    }
}
