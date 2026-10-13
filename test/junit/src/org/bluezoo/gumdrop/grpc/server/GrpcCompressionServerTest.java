/*
 * GrpcCompressionServerTest.java
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Compressed request messages on the server: the algorithms, a request that
 * cannot be decoded, and what the response offers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcCompressionServerTest {

    private static final String UNARY = "/gumdroptest.Echo/SayEcho";
    private static final String COLLECT = "/gumdroptest.Echo/Collect";
    private static ProtoFile protoFile;

    @BeforeClass
    public static void parseProto() throws Exception {
        protoFile = ProtoFileParser.parse(
                "syntax = \"proto3\";\n"
                        + "package gumdroptest;\n"
                        + "message EchoRequest { string message = 1; }\n"
                        + "message EchoResponse { string message = 1; }\n"
                        + "service Echo {\n"
                        + "  rpc SayEcho(EchoRequest) returns (EchoResponse);\n"
                        + "  rpc Collect(stream EchoRequest) returns (EchoResponse);\n"
                        + "  rpc Watch(EchoRequest) returns (stream EchoResponse);\n"
                        + "}\n");
    }

    /** Records the requests, and cancellation, of the call it is given. */
    private static class Service extends ProtoDefaultHandler
            implements GrpcServer, GrpcCallHandler {
        final List<String> messages = new ArrayList<String>();
        int calls;
        int cancelled;
        private String current;

        @Override
        public ProtoMessageHandler startCall(String path, GrpcCall call) {
            calls++;
            return this;
        }

        @Override
        public boolean isStreamingSupported() {
            return true;
        }

        @Override
        public void field(String name, Object value) {
            current = (String) value;
        }

        @Override
        public void endMessage() {
            messages.add(current);
        }

        @Override
        public void cancelled() {
            cancelled++;
        }
    }

    /** A call, headers in the order given, then the body chunks. */
    private static final class Call {
        final CapturingState state = new CapturingState();
        final Service service = new Service();
        final HttpRequestHandler stream;

        Call(String path, GrpcRequestHandler handler, String... headers) {
            stream = handler.openStream(state);
            stream.target(ByteBuffer.wrap(path.getBytes(StandardCharsets.US_ASCII)));
            stream.contentType(new ContentType("application", "grpc", null));
            for (int i = 0; i < headers.length; i += 2) {
                stream.header(headers[i], ByteBuffer.wrap(
                        headers[i + 1].getBytes(StandardCharsets.US_ASCII)));
            }
            stream.endHeaders();
        }

        void send(byte[]... chunks) {
            for (int i = 0; i < chunks.length; i++) {
                stream.bodyContent(ByteBuffer.wrap(chunks[i]));
            }
        }
    }

    private GrpcRequestHandler handler(Service service, Coding... encodings) {
        GrpcRequestHandler h = new GrpcRequestHandler(protoFile, service);
        if (encodings.length > 0) {
            h.encodings(encodings);
        }
        return h;
    }

    private static byte[] serialised(String message) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(8192);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        serializer.startMessage(writer, "gumdroptest.EchoRequest");
        serializer.field(writer, "message", message);
        serializer.endMessage();
        ByteBuffer b = channel.toByteBuffer();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static byte[] frame(int flag, byte[] payload) {
        ByteBuffer framed = GrpcFraming.frame(payload);
        byte[] out = new byte[framed.remaining()];
        framed.get(out);
        out[0] = (byte) flag;
        return out;
    }

    private static byte[] compressedFrame(Coding coding, String message) throws Exception {
        return frame(1, ContentEncoding.compressFully(coding, serialised(message)));
    }

    private static byte[][] oneByteChunks(byte[] data) {
        byte[][] out = new byte[data.length][];
        for (int i = 0; i < data.length; i++) {
            out[i] = new byte[] {data[i]};
        }
        return out;
    }

    private Call run(Service service, GrpcRequestHandler handler, String path,
            String[] headers, byte[]... chunks) {
        Call c = new Call(path, handler, headers);
        c.send(chunks);
        c.stream.endMessage();
        return c;
    }

    @Test
    public void everyAlgorithmIsDecoded() throws Exception {
        Coding[] all = {Coding.GZIP, Coding.DEFLATE, Coding.BR};
        for (int i = 0; i < all.length; i++) {
            Service service = new Service();
            Call c = run(service, handler(service, all), UNARY,
                    new String[] {"grpc-encoding", all[i].token()},
                    compressedFrame(all[i], "hello " + all[i]));
            assertEquals(all[i].token(), Arrays.asList("hello " + all[i]), service.messages);
            assertEquals(all[i].token(), 0, service.cancelled);
            assertNull(all[i].token(), c.state.headers.getValue("grpc-message"));
        }
    }

    @Test
    public void aCompressedMessageSplitIntoSingleBytesIsDecoded() throws Exception {
        Service service = new Service();
        run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "gzip"},
                oneByteChunks(compressedFrame(Coding.GZIP, "split up")));
        assertEquals(Arrays.asList("split up"), service.messages);
    }

    @Test
    public void theEncodingMayComeAnywhereAmongTheHeaders() throws Exception {
        Service service = new Service();
        run(service, handler(service), UNARY,
                new String[] {"x-first", "1", "grpc-accept-encoding", "gzip", "x-last", "2",
                    "grpc-encoding", "deflate"},
                compressedFrame(Coding.DEFLATE, "late header"));
        assertEquals(Arrays.asList("late header"), service.messages);
    }

    @Test
    public void identityMeansNoCompression() throws Exception {
        Service service = new Service();
        run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "identity"},
                frame(0, serialised("plain")));
        assertEquals(Arrays.asList("plain"), service.messages);
    }

    @Test
    public void aStreamMayMixCompressedAndPlainMessages() throws Exception {
        Service service = new Service();
        run(service, handler(service), COLLECT,
                new String[] {"grpc-encoding", "gzip"},
                compressedFrame(Coding.GZIP, "one"), frame(0, serialised("two")),
                compressedFrame(Coding.GZIP, "three"));
        assertEquals(Arrays.asList("one", "two", "three"), service.messages);
    }

    @Test
    public void anUnknownEncodingIsUnimplementedAndNeverReachesTheService() throws Exception {
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "snappy"}, frame(0, serialised("x")));
        assertEquals("12", c.state.headers.getValue("grpc-status"));
        assertEquals("identity,gzip,deflate", c.state.headers.getValue("grpc-accept-encoding"));
        assertEquals(0, service.calls);
        assertEquals(0, c.state.body.size());
    }

    @Test
    public void anAlgorithmThatIsNotOfferedIsUnimplemented() throws Exception {
        // Brotli is an extension, and is not offered unless it is asked for
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "br"}, frame(0, serialised("x")));
        assertEquals("12", c.state.headers.getValue("grpc-status"));
        assertEquals(0, service.calls);

        Service only = new Service();
        Call d = run(only, handler(only, Coding.GZIP), UNARY,
                new String[] {"grpc-encoding", "deflate"}, frame(0, serialised("x")));
        assertEquals("12", d.state.headers.getValue("grpc-status"));
        assertEquals("identity,gzip", d.state.headers.getValue("grpc-accept-encoding"));
    }

    @Test
    public void aCompressedMessageWithoutAnEncodingIsAnError() throws Exception {
        Service service = new Service();
        Call c = run(service, handler(service), UNARY, new String[0],
                compressedFrame(Coding.GZIP, "no header"));
        assertEquals("13", c.state.headers.getValue("grpc-status"));
        assertTrue(service.messages.isEmpty());
        assertEquals(1, service.cancelled);

        Service other = new Service();
        Call d = run(other, handler(other), UNARY, new String[] {"grpc-encoding", "identity"},
                compressedFrame(Coding.GZIP, "identity"));
        assertEquals("13", d.state.headers.getValue("grpc-status"));
    }

    @Test
    public void aMessageThatExpandsBeyondTheLimitIsResourceExhausted() throws Exception {
        char[] big = new char[5000];
        Arrays.fill(big, 'a');
        byte[] frame = compressedFrame(Coding.GZIP, new String(big));
        assertTrue("it compresses well", frame.length < 100);
        Service service = new Service();
        Call c = run(service, handler(service).maxMessageSize(100), UNARY,
                new String[] {"grpc-encoding", "gzip"}, frame);
        assertEquals("8", c.state.headers.getValue("grpc-status"));
        assertTrue(service.messages.isEmpty());
        assertEquals(1, service.cancelled);
    }

    @Test
    public void aCorruptMessageIsInternal() throws Exception {
        byte[] frame = compressedFrame(Coding.GZIP, "corrupt me");
        frame[frame.length - 6] ^= 0x55;
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "gzip"}, frame);
        assertEquals("13", c.state.headers.getValue("grpc-status"));
        assertTrue(service.messages.isEmpty());
    }

    @Test
    public void aTruncatedCompressedStreamIsInternal() throws Exception {
        byte[] compressed = ContentEncoding.compressFully(Coding.GZIP, serialised("cut short"));
        byte[] cut = Arrays.copyOf(compressed, compressed.length - 8);
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "gzip"}, frame(1, cut));
        assertEquals("13", c.state.headers.getValue("grpc-status"));
    }

    @Test
    public void everyResponseOffersTheAlgorithmsItDecodes() throws Exception {
        // a call that is answered at once
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "snappy"}, new byte[0]);
        assertEquals("identity,gzip,deflate", c.state.headers.getValue("grpc-accept-encoding"));

        // and one that sends a message, with Brotli asked for
        final Service replying = new Service() {
            @Override
            public ProtoMessageHandler startCall(String path, final GrpcCall call) {
                return new ProtoDefaultHandler() {
                    @Override
                    public void endMessage() {
                        try {
                            GrpcResponseMessage msg = call.openMessage(null);
                            msg.getSerializer().field(msg.getWriter(), "message", "hi");
                            msg.complete();
                        } catch (Exception e) {
                            call.sendError(e);
                        }
                    }
                };
            }
        };
        Call d = run(replying, handler(replying, Coding.GZIP, Coding.DEFLATE, Coding.BR), UNARY,
                new String[0], frame(0, serialised("x")));
        assertEquals("identity,gzip,deflate,br", d.state.headers.getValue("grpc-accept-encoding"));
        assertFalse(d.state.body.size() == 0);
    }

    // ── Compressing responses ──

    /** Answers every request message with one response message of the given size. */
    private static class Replier extends Service {
        final int size;
        final int count;

        Replier(int size, int count) {
            this.size = size;
            this.count = count;
        }

        @Override
        public ProtoMessageHandler startCall(String path, final GrpcCall call) {
            calls++;
            return new ProtoDefaultHandler() {
                @Override
                public void endMessage() {
                    try {
                        for (int i = 0; i < count; i++) {
                            char[] text = new char[size];
                            Arrays.fill(text, (char) ('a' + i));
                            GrpcResponseMessage msg = call.openMessage(null);
                            msg.getSerializer().field(msg.getWriter(), "message",
                                    new String(text));
                            msg.complete();
                        }
                        call.close();
                    } catch (Exception e) {
                        call.sendError(e);
                    }
                }
            };
        }
    }

    /** The messages of a response body: the flag and the payload of each frame. */
    private static List<byte[]> frames(CapturingState state) {
        List<byte[]> out = new ArrayList<byte[]>();
        byte[] body = state.body.toByteArray();
        int pos = 0;
        while (pos < body.length) {
            int length = ((body[pos + 1] & 0xff) << 24) | ((body[pos + 2] & 0xff) << 16)
                    | ((body[pos + 3] & 0xff) << 8) | (body[pos + 4] & 0xff);
            out.add(Arrays.copyOfRange(body, pos, pos + GrpcFraming.HEADER_SIZE + length));
            pos += GrpcFraming.HEADER_SIZE + length;
        }
        return out;
    }

    private static byte[] payload(byte[] frame, Coding coding) throws Exception {
        byte[] data = Arrays.copyOfRange(frame, GrpcFraming.HEADER_SIZE, frame.length);
        return frame[0] == 0 ? data : ContentEncoding.decompress(coding, data, 1 << 20);
    }

    private Call answered(Service service, GrpcRequestHandler handler, String accept)
            throws Exception {
        String[] headers = accept == null ? new String[0]
                : new String[] {"grpc-accept-encoding", accept};
        return run(service, handler, UNARY, headers, frame(0, serialised("x")));
    }

    @Test
    public void aLargeResponseIsCompressedWithWhatTheClientAccepts() throws Exception {
        Replier service = new Replier(2000, 1);
        Call c = answered(service, handler(service), "identity,gzip");
        assertEquals("gzip", c.state.headers.getValue("grpc-encoding"));
        List<byte[]> frames = frames(c.state);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0)[0]);
        assertTrue(frames.get(0).length < 200);
        byte[] plain = payload(frames.get(0), Coding.GZIP);
        assertTrue(plain.length > 2000);
        assertEquals("[grpc-status: 0]", c.state.trailers.toString());
    }

    @Test
    public void everyAlgorithmCompressesResponses() throws Exception {
        Coding[] all = {Coding.GZIP, Coding.DEFLATE, Coding.BR};
        for (int i = 0; i < all.length; i++) {
            Replier service = new Replier(2000, 1);
            Call c = answered(service, handler(service, all[i]), all[i].token());
            assertEquals(all[i].token(), c.state.headers.getValue("grpc-encoding"));
            List<byte[]> frames = frames(c.state);
            assertEquals(1, frames.get(0)[0]);
            assertTrue(payload(frames.get(0), all[i]).length > 2000);
        }
    }

    @Test
    public void aSmallResponseIsSentAsItIs() throws Exception {
        Replier service = new Replier(100, 1);
        Call c = answered(service, handler(service), "gzip");
        assertEquals(0, frames(c.state).get(0)[0]);
    }

    @Test
    public void theThresholdIsConfigurable() throws Exception {
        Replier service = new Replier(100, 1);
        Call c = answered(service, handler(service).compressionThreshold(50), "gzip");
        assertEquals(1, frames(c.state).get(0)[0]);

        Replier large = new Replier(2000, 1);
        Call d = answered(large, handler(large).compressionThreshold(10000), "gzip");
        assertEquals(0, frames(d.state).get(0)[0]);
    }

    @Test
    public void noCompressionUnlessTheClientAsksForIt() throws Exception {
        Replier none = new Replier(2000, 1);
        Call a = answered(none, handler(none), null);
        assertNull(a.state.headers.getValue("grpc-encoding"));
        assertEquals(0, frames(a.state).get(0)[0]);

        Replier identity = new Replier(2000, 1);
        Call b = answered(identity, handler(identity), "identity");
        assertNull(b.state.headers.getValue("grpc-encoding"));

        Replier unknown = new Replier(2000, 1);
        Call c = answered(unknown, handler(unknown), "snappy,zstd");
        assertNull(c.state.headers.getValue("grpc-encoding"));
        assertEquals(0, frames(c.state).get(0)[0]);
    }

    @Test
    public void brotliIsUsedOnlyWhenItIsOfferedAndAccepted() throws Exception {
        // the client accepts it, but it is an extension that is not on by default
        Replier service = new Replier(2000, 1);
        Call c = answered(service, handler(service), "br");
        assertNull(c.state.headers.getValue("grpc-encoding"));

        Replier both = new Replier(2000, 1);
        Call d = answered(both, handler(both, Coding.GZIP, Coding.BR), "br,gzip");
        assertEquals("gzip", d.state.headers.getValue("grpc-encoding"));
    }

    @Test
    public void theOrderOfTheOfferedAlgorithmsIsThePreference() throws Exception {
        Replier service = new Replier(2000, 1);
        Call c = answered(service, handler(service, Coding.DEFLATE, Coding.GZIP), "gzip,deflate");
        assertEquals("deflate", c.state.headers.getValue("grpc-encoding"));
    }

    @Test
    public void compressionCanBeSwitchedOff() throws Exception {
        Replier service = new Replier(2000, 1);
        Call c = answered(service, handler(service).compressResponses(false), "gzip");
        assertNull(c.state.headers.getValue("grpc-encoding"));
        assertEquals(0, frames(c.state).get(0)[0]);
        // the handler still decodes what it is sent
        assertEquals("identity,gzip,deflate", c.state.headers.getValue("grpc-accept-encoding"));
    }

    @Test
    public void everyMessageOfAStreamIsCompressedOnItsOwn() throws Exception {
        Replier service = new Replier(2000, 3);
        Call c = run(service, handler(service), "/gumdroptest.Echo/Watch",
                new String[] {"grpc-accept-encoding", "gzip"}, frame(0, serialised("x")));
        List<byte[]> frames = frames(c.state);
        assertEquals(3, frames.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1, frames.get(i)[0]);
            byte[] plain = payload(frames.get(i), Coding.GZIP);
            assertTrue(new String(plain, StandardCharsets.UTF_8).contains("" + (char) ('a' + i)));
        }
        assertEquals("gzip", c.state.headers.getValue("grpc-encoding"));
    }

    @Test
    public void anErrorWithoutMessagesNamesNoEncoding() throws Exception {
        Service service = new Service();
        Call c = run(service, handler(service), UNARY,
                new String[] {"grpc-encoding", "snappy", "grpc-accept-encoding", "gzip"},
                new byte[0]);
        assertEquals("12", c.state.headers.getValue("grpc-status"));
        assertNull(c.state.headers.getValue("grpc-encoding"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theThresholdMayNotBeNegative() {
        handler(new Service()).compressionThreshold(-1);
    }
}
