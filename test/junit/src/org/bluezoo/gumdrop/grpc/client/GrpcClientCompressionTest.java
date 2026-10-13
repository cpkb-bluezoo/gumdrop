/*
 * GrpcClientCompressionTest.java
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

package org.bluezoo.gumdrop.grpc.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;
import org.junit.Before;
import org.junit.Test;

/**
 * Compressed response messages in {@link GrpcClient}: the algorithms, a
 * response that cannot be decoded, and what a request offers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClientCompressionTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Req { string name = 1; }\n"
            + "message Res { int32 code = 1; string text = 2; }\n"
            + "service Svc { rpc Watch (Req) returns (stream Res); }\n";

    private static final String WATCH = "/example.v1.Svc/Watch";

    private static final class StubRequest implements HttpRequest {
        final List<String> headers = new ArrayList<String>();
        HttpResponseHandler handler;
        boolean cancelled;

        @Override public void header(String name, ByteBuffer rawValue) {
            headers.add(name + ": " + StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()));
        }
        @Override public void priority(int weight) { }
        @Override public void dependency(HttpRequest parent) { }
        @Override public void exclusive(boolean exclusive) { }
        @Override public void longHeader(String name, long value) { header(name, Long.toString(value)); }
        @Override public void dateHeader(String name, java.time.Instant value) { header(name, value.toString()); }
        @Override public void contentType(org.bluezoo.gumdrop.mime.ContentType contentType) { header("Content-Type", contentType.toHeaderValue()); }
        @Override public void contentDisposition(org.bluezoo.gumdrop.mime.ContentDisposition contentDisposition) { header("Content-Disposition", contentDisposition.toHeaderValue()); }
        @Override public void endHeaders() { }
        @Override public int bodyContent(ByteBuffer data) {
            int n = data.remaining();
            data.position(data.limit());
            return n;
        }
        @Override public void endMessage() { }
        @Override public void cancel() { cancelled = true; }
    }

    private static final class StubHttpClient extends HttpClient {
        final StubRequest request = new StubRequest();

        @Override
        public HttpRequest post(String path, HttpResponseHandler handler) {
            request.handler = handler;
            return request;
        }
    }

    private static final class Handler implements GrpcResponseHandler {
        final List<String> texts = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public ProtoMessageHandler startMessage(String messageTypeName) {
            return new ProtoDefaultHandler() {
                @Override
                public void field(String name, Object value) {
                    if ("text".equals(name)) {
                        texts.add((String) value);
                    }
                }
            };
        }

        @Override
        public void onError(Exception e) {
            errors.add(e);
        }

        int status() {
            return ((GrpcException) errors.get(0)).getStatus();
        }
    }

    private ProtoFile proto;
    private StubHttpClient http;
    private Handler handler;

    @Before
    public void setUp() throws Exception {
        proto = ProtoFileParser.parse(PROTO);
        http = new StubHttpClient();
        handler = new Handler();
    }

    private GrpcClient client(Coding... encodings) {
        GrpcClient client = new GrpcClient(proto);
        if (encodings.length > 0) {
            client.encodings(encodings);
        }
        return client;
    }

    private byte[] serialised(String text) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(8192);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer s = new ProtoModelSerializer(proto);
        s.startMessage(writer, "example.v1.Res");
        s.field(writer, "code", 1);
        s.field(writer, "text", text);
        s.endMessage();
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

    private byte[] compressedFrame(Coding coding, String text) throws Exception {
        return frame(1, ContentEncoding.compressFully(coding, serialised(text)));
    }

    /** Starts a call and delivers the response head and the given frames. */
    private void respond(GrpcClient client, String encoding, byte[]... frames) {
        client.call(http, WATCH, ByteBuffer.wrap("req".getBytes(StandardCharsets.UTF_8)), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        if (encoding != null) {
            r.header("grpc-encoding", MessageEvents.octets(encoding));
        }
        r.endHeaders();
        for (int i = 0; i < frames.length; i++) {
            r.bodyContent(ByteBuffer.wrap(frames[i]));
        }
        r.header("grpc-status", MessageEvents.octets("0"));
        r.endMessage();
    }

    @Test
    public void everyAlgorithmIsDecoded() throws Exception {
        Coding[] all = {Coding.GZIP, Coding.DEFLATE, Coding.BR};
        for (int i = 0; i < all.length; i++) {
            setUp();
            respond(client(all), all[i].token(), compressedFrame(all[i], "hello " + all[i]));
            assertTrue(all[i] + " " + handler.errors, handler.errors.isEmpty());
            assertEquals(all[i].token(), Arrays.asList("hello " + all[i]), handler.texts);
        }
    }

    @Test
    public void aCompressedMessageSplitIntoSingleBytesIsDecoded() throws Exception {
        byte[] data = compressedFrame(Coding.GZIP, "split up");
        byte[][] chunks = new byte[data.length][];
        for (int i = 0; i < data.length; i++) {
            chunks[i] = new byte[] {data[i]};
        }
        respond(client(), "gzip", chunks);
        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals(Arrays.asList("split up"), handler.texts);
    }

    @Test
    public void aStreamMayMixCompressedAndPlainMessages() throws Exception {
        respond(client(), "gzip", compressedFrame(Coding.GZIP, "one"),
                frame(0, serialised("two")), compressedFrame(Coding.GZIP, "three"));
        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals(Arrays.asList("one", "two", "three"), handler.texts);
    }

    @Test
    public void identityMeansNoCompression() throws Exception {
        respond(client(), "identity", frame(0, serialised("plain")));
        assertEquals(Arrays.asList("plain"), handler.texts);
    }

    @Test
    public void anUnknownEncodingIsUnimplementedAndTheStreamIsCancelled() throws Exception {
        respond(client(), "snappy", compressedFrame(Coding.GZIP, "x"));
        assertEquals(1, handler.errors.size());
        assertEquals(GrpcStatus.UNIMPLEMENTED, handler.status());
        assertTrue(handler.texts.isEmpty());
        assertTrue(http.request.cancelled);
    }

    @Test
    public void anAlgorithmThatWasNotOfferedIsUnimplemented() throws Exception {
        // Brotli is an extension, and is not offered unless it is asked for
        respond(client(), "br", compressedFrame(Coding.BR, "x"));
        assertEquals(GrpcStatus.UNIMPLEMENTED, handler.status());
    }

    @Test
    public void aCompressedMessageWithoutAnEncodingIsAnError() throws Exception {
        respond(client(), null, compressedFrame(Coding.GZIP, "x"));
        assertEquals(GrpcStatus.INTERNAL, handler.status());
        assertTrue(handler.texts.isEmpty());
    }

    @Test
    public void aMessageThatExpandsBeyondTheLimitIsResourceExhausted() throws Exception {
        char[] big = new char[(int) GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE + 1000];
        Arrays.fill(big, 'a');
        byte[] frame = compressedFrame(Coding.GZIP, new String(big));
        assertTrue("it compresses well", frame.length < GrpcFraming.DEFAULT_MAX_MESSAGE_SIZE);
        respond(client(), "gzip", frame);
        assertEquals(GrpcStatus.RESOURCE_EXHAUSTED, handler.status());
        assertTrue(handler.texts.isEmpty());
        assertTrue(http.request.cancelled);
    }

    @Test
    public void aCorruptMessageIsInternal() throws Exception {
        byte[] frame = compressedFrame(Coding.GZIP, "corrupt me");
        frame[frame.length - 6] ^= 0x55;
        respond(client(), "gzip", frame);
        // the trailer that fails comes after the data, which streams on
        assertEquals(GrpcStatus.INTERNAL, handler.status());
        assertTrue(http.request.cancelled);
    }

    @Test
    public void aTruncatedCompressedStreamIsInternal() throws Exception {
        byte[] compressed = ContentEncoding.compressFully(Coding.GZIP, serialised("cut short"));
        respond(client(), "gzip", frame(1, Arrays.copyOf(compressed, compressed.length - 8)));
        assertEquals(GrpcStatus.INTERNAL, handler.status());
    }

    @Test
    public void everyRequestOffersTheAlgorithmsItDecodes() throws Exception {
        client().call(http, WATCH, ByteBuffer.wrap(new byte[0]), handler);
        assertTrue(http.request.headers.toString(),
                http.request.headers.contains("grpc-accept-encoding: identity,gzip,deflate"));

        http = new StubHttpClient();
        client(Coding.GZIP, Coding.BR).call(http, WATCH, ByteBuffer.wrap(new byte[0]), handler);
        assertTrue(http.request.headers.toString(),
                http.request.headers.contains("grpc-accept-encoding: identity,gzip,br"));
    }
}
