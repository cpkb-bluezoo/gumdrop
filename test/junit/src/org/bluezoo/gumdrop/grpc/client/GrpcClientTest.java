/*
 * GrpcClientTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponse;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link GrpcClient} unary calls against a stub {@link HttpClient}
 * whose request records what is written and hands back the response
 * handler, so responses can be replayed without any transport.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClientTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Req { string name = 1; }\n"
            + "message Res { int32 code = 1; string text = 2; }\n"
            + "service Greeter { rpc Say (Req) returns (Res); }\n";

    private static final String PATH = "/example.v1.Greeter/Say";

    private static final class StubRequest implements HttpRequest {
        final List<String> headers = new ArrayList<String>();
        final List<byte[]> body = new ArrayList<byte[]>();
        HttpResponseHandler handler;
        boolean ended;

        @Override public void header(String name, String value) {
            headers.add(name + ": " + value);
        }
        @Override public void priority(int weight) { }
        @Override public void dependency(HttpRequest parent) { }
        @Override public void exclusive(boolean exclusive) { }
        @Override public void send(HttpResponseHandler h) { }
        @Override public void startRequestBody(HttpResponseHandler h) {
            handler = h;
        }
        @Override public int requestBodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            body.add(b);
            return b.length;
        }
        @Override public void endRequestBody() { ended = true; }
        @Override public void cancel() { }
    }

    private static final class StubHttpClient extends HttpClient {
        final StubRequest request = new StubRequest();
        String postedPath;

        @Override
        public HttpRequest post(String path) {
            postedPath = path;
            return request;
        }
    }

    private static final class Recorder extends ProtoDefaultHandler {
        final List<String> starts = new ArrayList<String>();
        final List<String> fieldNames = new ArrayList<String>();
        final List<Object> fieldValues = new ArrayList<Object>();
        int ends;

        @Override
        public void startMessage(String typeName) {
            starts.add(typeName);
        }

        @Override
        public void endMessage() {
            ends++;
        }

        @Override
        public void field(String name, Object value) {
            fieldNames.add(name);
            fieldValues.add(value);
        }
    }

    private static final class Handler implements GrpcResponseHandler {
        final Recorder recorder = new Recorder();
        final List<String> requestedTypes = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        boolean refuse;

        @Override
        public ProtoMessageHandler startMessage(String messageTypeName) {
            requestedTypes.add(messageTypeName);
            return refuse ? null : recorder;
        }

        @Override
        public void onError(Exception e) {
            errors.add(e);
        }
    }

    private ProtoFile proto;
    private GrpcClient client;
    private StubHttpClient http;
    private Handler handler;

    @Before
    public void setUp() throws Exception {
        proto = ProtoFileParser.parse(PROTO);
        client = new GrpcClient(proto);
        http = new StubHttpClient();
        handler = new Handler();
    }

    private ByteBuffer response(int code, String text) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(128);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer s = new ProtoModelSerializer(proto);
        s.startMessage(writer, "example.v1.Res");
        s.field(writer, "code", code);
        s.field(writer, "text", text);
        s.endMessage();
        return GrpcFraming.frame(channel.toByteBuffer());
    }

    private ByteBuffer request() {
        return ByteBuffer.wrap("req".getBytes(StandardCharsets.UTF_8));
    }

    private void call() {
        client.unaryCall(http, PATH, request(), handler);
    }

    @Test
    public void requestIsFramedWithGrpcHeaders() {
        call();
        assertEquals(PATH, http.postedPath);
        assertTrue(http.request.headers.contains(
                "Content-Type: application/grpc"));
        assertTrue(http.request.headers.contains("Te: trailers"));
        assertEquals(1, http.request.body.size());
        byte[] body = http.request.body.get(0);
        assertEquals(GrpcFraming.HEADER_SIZE + 3, body.length);
        assertEquals(0, body[0]);
        assertEquals(3, body[4]);
        assertTrue(http.request.ended);
    }

    @Test
    public void responseMessageDecodedIntoHandler() throws Exception {
        call();
        HttpResponseHandler r = http.request.handler;
        r.ok(new HttpResponse(HttpStatus.OK));
        r.startResponseBody();
        r.responseBodyContent(response(7, "seven"));
        r.endResponseBody();
        r.header("grpc-status", "0");
        r.close();
        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals("example.v1.Res", handler.requestedTypes.get(0));
        assertEquals(1, handler.recorder.ends);
        assertEquals("code", handler.recorder.fieldNames.get(0));
        assertEquals(7, handler.recorder.fieldValues.get(0));
        assertEquals("seven", handler.recorder.fieldValues.get(1));
    }

    @Test
    public void responseSplitAcrossChunksDecoded() throws Exception {
        call();
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        ByteBuffer whole = response(1, "chunked");
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        r.responseBodyContent(ByteBuffer.wrap(bytes, 0, 3));
        r.responseBodyContent(ByteBuffer.wrap(bytes, 3, bytes.length - 3));
        r.responseBodyContent(null);
        r.responseBodyContent(ByteBuffer.allocate(0));
        r.close();
        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals("chunked", handler.recorder.fieldValues.get(1));
    }

    @Test
    public void convenienceOverloadUsesGivenResponseType() throws Exception {
        Recorder rec = new Recorder();
        client.unaryCall(http, PATH, request(), "example.v1.Res", rec);
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        r.responseBodyContent(response(3, "x"));
        r.close();
        assertEquals(1, rec.ends);
        assertEquals(3, rec.fieldValues.get(0));
    }

    @Test
    public void unknownPathStartsMessageWithoutType() {
        client.unaryCall(http, "/unknown.Service/Method", request(), handler);
        http.request.handler.startResponseBody();
        assertEquals(1, handler.requestedTypes.size());
        assertNull(handler.requestedTypes.get(0));
    }

    @Test
    public void httpErrorStatusReported() {
        call();
        http.request.handler.error(new HttpResponse(HttpStatus.BAD_GATEWAY));
        assertEquals(1, handler.errors.size());
        assertTrue(handler.errors.get(0).getMessage().contains("gRPC error"));
        http.request.handler.failed(new IOException("second"));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void transportFailureReported() {
        call();
        IOException cause = new IOException("reset");
        http.request.handler.failed(cause);
        assertEquals(1, handler.errors.size());
        assertEquals(cause, handler.errors.get(0));
    }

    @Test
    public void nonZeroGrpcStatusReportedWithDecodedMessage() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        r.header("GRPC-Status", "5");
        r.header("grpc-message", "not%20found%zz%E2%9C%93");
        r.header("content-type", "application/grpc");
        r.close();
        assertEquals(1, handler.errors.size());
        String m = handler.errors.get(0).getMessage();
        assertTrue(m, m.contains("gRPC error 5"));
        assertTrue(m, m.contains("not found%zz✓"));
    }

    @Test
    public void nonZeroGrpcStatusWithoutMessage() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.header("grpc-status", "13");
        r.close();
        assertEquals("gRPC error 13", handler.errors.get(0).getMessage());
    }

    @Test
    public void plainGrpcMessageNotDecoded() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.header("grpc-status", "2");
        r.header("grpc-message", "plain");
        r.close();
        assertTrue(handler.errors.get(0).getMessage().endsWith(": plain"));
    }

    @Test
    public void handlerRefusingMessageFailsCall() {
        handler.refuse = true;
        call();
        http.request.handler.startResponseBody();
        assertEquals(1, handler.errors.size());
        http.request.handler.responseBodyContent(ByteBuffer.wrap(new byte[5]));
        http.request.handler.endResponseBody();
        http.request.handler.close();
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void truncatedFrameAtEndOfBodyReported() throws Exception {
        call();
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        ByteBuffer whole = response(1, "cut");
        byte[] bytes = new byte[whole.remaining()];
        whole.get(bytes);
        r.responseBodyContent(ByteBuffer.wrap(bytes, 0, bytes.length - 2));
        r.endResponseBody();
        assertEquals(1, handler.errors.size());
        assertTrue(handler.errors.get(0).getMessage().contains("Incomplete"));
    }

    @Test
    public void closeWithoutCompleteMessageReported() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        r.close();
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void bodyEventsBeforeStartAreIgnored() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.responseBodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        r.endResponseBody();
        r.pushPromise(null);
        assertTrue(handler.errors.isEmpty());
        assertNotNull(r);
    }

    @Test
    public void corruptMessageBodyReported() {
        call();
        HttpResponseHandler r = http.request.handler;
        r.startResponseBody();
        // frame header claims 3 bytes; payload is not valid protobuf
        r.responseBodyContent(ByteBuffer.wrap(
                new byte[] {0, 0, 0, 0, 3, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff}));
        assertFalse(handler.errors.isEmpty());
    }
}
