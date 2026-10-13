/*
 * GrpcCallMetadataTest.java
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Call metadata, the peer and deadlines on the server side of a gRPC call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcCallMetadataTest {

    private static final String PATH = "/gumdroptest.Echo/Watch";
    private static ProtoFile protoFile;

    @BeforeClass
    public static void parseProto() throws Exception {
        protoFile = ProtoFileParser.parse(
                "syntax = \"proto3\";\n"
                        + "package gumdroptest;\n"
                        + "message EchoRequest { string message = 1; }\n"
                        + "message EchoResponse { string message = 1; }\n"
                        + "service Echo {\n"
                        + "  rpc Watch(EchoRequest) returns (stream EchoResponse);\n"
                        + "}\n");
    }

    private static final class Handler extends ProtoDefaultHandler implements GrpcCallHandler {
        int cancelled;

        @Override
        public void cancelled() {
            cancelled++;
        }
    }

    /** A call as it arrives over a stream, with the headers a test gives it. */
    private static final class Call {
        final CapturingState state = new CapturingState();
        final Handler handler = new Handler();
        GrpcCall call;
        HttpRequestHandler stream;

        Call() {
            GrpcServer server = new GrpcServer() {
                @Override
                public ProtoMessageHandler startCall(String path, GrpcCall c) {
                    call = c;
                    return handler;
                }

                @Override
                public boolean isStreamingSupported() {
                    return true;
                }
            };
            stream = new GrpcRequestHandler(protoFile, server).openStream(state);
        }

        void start(String... nameValuePairs) {
            stream.target(ByteBuffer.wrap(PATH.getBytes(StandardCharsets.US_ASCII)));
            stream.contentType(new ContentType("application", "grpc", null));
            for (int i = 0; i < nameValuePairs.length; i += 2) {
                stream.header(nameValuePairs[i], ByteBuffer.wrap(
                        nameValuePairs[i + 1].getBytes(StandardCharsets.US_ASCII)));
            }
            stream.endHeaders();
        }

        void reply() throws Exception {
            GrpcResponseMessage msg = call.openMessage(null);
            ProtoModelSerializer ser = msg.getSerializer();
            ser.field(msg.getWriter(), "message", "m");
            msg.complete();
        }
    }

    @Test
    public void requestMetadataIsExposedWithoutTheProtocolsOwnHeaders() throws Exception {
        Call c = new Call();
        c.start("x-trace", "abc", "x-trace", "def", "x-blob-bin", "AQID",
                "grpc-encoding", "identity", "te", "trailers", "grpc-accept-encoding", "gzip");
        assertNotNull(c.call);
        assertEquals(java.util.Arrays.asList("abc", "def"),
                c.call.getRequestMetadata().getAll("x-trace"));
        assertArrayEquals(new byte[] {1, 2, 3}, c.call.getRequestMetadata().getBinary("x-blob-bin"));
        assertEquals(java.util.Arrays.asList("x-trace", "x-blob-bin"),
                c.call.getRequestMetadata().keys());
    }

    @Test
    public void aCallWithoutMetadataHasEmptyMetadata() throws Exception {
        Call c = new Call();
        c.start();
        assertTrue(c.call.getRequestMetadata().isEmpty());
        assertTrue(c.call.getResponseMetadata().isEmpty());
        assertTrue(c.call.getTrailingMetadata().isEmpty());
    }

    @Test
    public void responseMetadataGoesWithTheHeadersAndTrailingMetadataWithTheStatus()
            throws Exception {
        Call c = new Call();
        c.start();
        c.call.getResponseMetadata().add("x-served-by", "node-1");
        c.call.getResponseMetadata().addBinary("x-id-bin", new byte[] {1, 2, 3});
        c.call.getTrailingMetadata().add("x-cost", "42");
        c.reply();
        assertEquals("node-1", c.state.headers.getValue("x-served-by"));
        assertEquals("AQID", c.state.headers.getValue("x-id-bin"));
        assertTrue(c.state.trailers.isEmpty());
        c.call.close();
        assertEquals("[x-cost: 42, grpc-status: 0]", c.state.trailers.toString());
    }

    @Test
    public void aCallThatSendsNoMessagesSendsBothKindsOfMetadataInItsOneHeaderBlock()
            throws Exception {
        Call c = new Call();
        c.start();
        c.call.getResponseMetadata().add("x-a", "1");
        c.call.getTrailingMetadata().add("x-b", "2");
        c.call.close(5, "gone");
        assertEquals("1", c.state.headers.getValue("x-a"));
        assertEquals("2", c.state.headers.getValue("x-b"));
        assertEquals("5", c.state.headers.getValue("grpc-status"));
        assertTrue(c.state.trailers.isEmpty());
    }

    @Test
    public void metadataAddedTooLateIsNotSent() throws Exception {
        Call c = new Call();
        c.start();
        c.reply();
        c.call.getResponseMetadata().add("x-late", "1");
        c.call.close();
        assertNull(c.state.headers.getValue("x-late"));
        assertFalse(c.state.trailers.toString().contains("x-late"));
    }

    @Test
    public void thePeerIsExposed() throws Exception {
        Call c = new Call();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 4321);
        Principal principal = new Principal() {
            public String getName() {
                return "alice";
            }
        };
        c.state.remoteAddress = address;
        c.state.principal = principal;
        c.start();
        assertSame(address, c.call.getRemoteAddress());
        assertSame(principal, c.call.getPrincipal());
        assertNull(c.call.getSecurityInfo());
    }

    @Test
    public void aDeadlineIsExposedAndScheduled() throws Exception {
        Call c = new Call();
        Instant before = Instant.now();
        c.start("grpc-timeout", "5S");
        Instant after = Instant.now();
        assertEquals(5000L, c.state.timerDelayMs);
        Instant deadline = c.call.getDeadline();
        assertFalse(deadline.isBefore(before.plus(Duration.ofSeconds(5))));
        assertFalse(deadline.isAfter(after.plus(Duration.ofSeconds(5))));
    }

    @Test
    public void noTimeoutMeansNoDeadline() throws Exception {
        Call c = new Call();
        c.start();
        assertNull(c.call.getDeadline());
        assertNull(c.state.timerCallback);
    }

    @Test
    public void anInvalidTimeoutIsIgnored() throws Exception {
        Call c = new Call();
        c.start("grpc-timeout", "soon");
        assertNull(c.call.getDeadline());
        assertNull(c.state.timerCallback);
    }

    @Test
    public void whenTheDeadlinePassesTheCallEndsAndTheHandlerIsCancelled() throws Exception {
        Call c = new Call();
        c.start("grpc-timeout", "100m");
        c.state.timerCallback.run();
        assertEquals("4", c.state.headers.getValue("grpc-status"));
        assertEquals("Deadline exceeded", c.state.headers.getValue("grpc-message"));
        assertEquals(1, c.state.completeCount);
        assertEquals(1, c.handler.cancelled);
        try {
            c.call.openMessage(null);
            org.junit.Assert.fail("a call past its deadline cannot send");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void aDeadlineAfterMessagesHaveGoneOutIsInTheTrailers() throws Exception {
        Call c = new Call();
        c.start("grpc-timeout", "100m");
        c.reply();
        c.state.timerCallback.run();
        assertEquals("[grpc-status: 4, grpc-message: Deadline exceeded]",
                c.state.trailers.toString());
        assertEquals(1, c.handler.cancelled);
    }

    @Test
    public void theTimerIsCancelledWhenTheCallEnds() throws Exception {
        Call c = new Call();
        c.start("grpc-timeout", "100m");
        c.call.close();
        assertTrue(c.state.timerCancelled);
        // a timer that fires anyway does nothing
        c.state.timerCallback.run();
        assertEquals(1, c.state.completeCount);
        assertEquals(0, c.handler.cancelled);
    }

    @Test
    public void theTimerIsCancelledWhenTheStreamIsReset() throws Exception {
        Call c = new Call();
        c.start("grpc-timeout", "100m");
        c.stream.failed(new IOException("reset"));
        assertTrue(c.state.timerCancelled);
        assertEquals(1, c.handler.cancelled);
    }
}
