/*
 * GrpcClientStreamingTest.java
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
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
 * Streaming calls on {@link GrpcClient}: many request messages, many
 * response messages and half-close, against a stub transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClientStreamingTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Req { string name = 1; }\n"
            + "message Res { int32 code = 1; string text = 2; }\n"
            + "service Svc {\n"
            + "  rpc Say (Req) returns (Res);\n"
            + "  rpc Watch (Req) returns (stream Res);\n"
            + "  rpc Collect (stream Req) returns (Res);\n"
            + "  rpc Chat (stream Req) returns (stream Res);\n"
            + "}\n";

    private static final String WATCH = "/example.v1.Svc/Watch";
    private static final String COLLECT = "/example.v1.Svc/Collect";
    private static final String CHAT = "/example.v1.Svc/Chat";

    private static final class StubRequest implements HttpRequest {
        final List<String> headers = new ArrayList<String>();
        final List<byte[]> body = new ArrayList<byte[]>();
        HttpResponseHandler handler;
        boolean headersEnded;
        boolean ended;
        boolean cancelled;
        /** How many bytes of each write the transport takes; negative for all. */
        int accept = -1;

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
        @Override public void endHeaders() { headersEnded = true; }
        @Override public int bodyContent(ByteBuffer data) {
            int n = accept < 0 ? data.remaining() : Math.min(accept, data.remaining());
            byte[] b = new byte[n];
            data.get(b);
            body.add(b);
            return n;
        }
        @Override public void endMessage() { ended = true; }
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
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        final List<String> completions = new ArrayList<String>();
        boolean refuseSecond;
        int starts;

        @Override
        public ProtoMessageHandler startMessage(String messageTypeName) {
            starts++;
            if (refuseSecond && starts == 2) {
                return null;
            }
            events.add("start");
            return new ProtoDefaultHandler() {
                @Override
                public void field(String name, Object value) {
                    if ("text".equals(name)) {
                        texts.add((String) value);
                    }
                }

                @Override
                public void endMessage() {
                    events.add("end");
                }
            };
        }

        @Override
        public void onError(Exception e) {
            errors.add(e);
        }

        @Override
        public void onComplete(int status, String message) {
            completions.add(status + ":" + message);
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

    private static ByteBuffer message(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private void finish(int status, String message) {
        HttpResponseHandler r = http.request.handler;
        r.header("grpc-status", MessageEvents.octets(Integer.toString(status)));
        if (message != null) {
            r.header("grpc-message", MessageEvents.octets(message));
        }
        r.endMessage();
    }

    @Test
    public void serverStreamingDeliversEachResponseMessage() throws Exception {
        client.call(http, WATCH, message("req"), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        // two messages in one chunk, a third split inside its text field
        ByteBuffer one = response(1, "one");
        ByteBuffer two = response(2, "two");
        ByteBuffer three = response(3, "three");
        ByteBuffer both = ByteBuffer.allocate(one.remaining() + two.remaining());
        both.put(one).put(two).flip();
        r.bodyContent(both);
        byte[] bytes = new byte[three.remaining()];
        three.get(bytes);
        r.bodyContent(ByteBuffer.wrap(bytes, 0, bytes.length - 3));
        r.bodyContent(ByteBuffer.wrap(bytes, bytes.length - 3, 3));
        finish(0, null);

        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals(java.util.Arrays.asList("one", "two", "three"), handler.texts);
        assertEquals(java.util.Arrays.asList("start", "end", "start", "end", "start", "end"),
                handler.events);
        assertEquals(java.util.Arrays.asList("0:null"), handler.completions);
    }

    @Test
    public void aSingleRequestMessageIsSentAndEndedForAnyKindOfRpc() throws Exception {
        client.call(http, COLLECT, message("req"), handler);
        assertEquals(1, http.request.body.size());
        assertTrue(http.request.ended);
    }

    @Test
    public void clientStreamingSendsFramedMessagesUntilTheHalfClose() throws Exception {
        GrpcClientCall call = client.call(http, COLLECT, handler);
        assertTrue(http.request.headers.contains("Content-Type: application/grpc"));
        assertTrue(http.request.headers.contains("Te: trailers"));
        // the headers go out now, so that the first message is not held back
        assertTrue(http.request.headersEnded);
        assertTrue(http.request.body.isEmpty());
        assertFalse(http.request.ended);

        call.send(message("a"));
        assertEquals(1, http.request.body.size());
        call.send(message("bc"));
        assertEquals(2, http.request.body.size());
        assertEquals(GrpcFraming.HEADER_SIZE + 1, http.request.body.get(0).length);
        assertEquals(GrpcFraming.HEADER_SIZE + 2, http.request.body.get(1).length);
        assertEquals(2, http.request.body.get(1)[4]);
        assertFalse(http.request.ended);

        call.halfClose();
        assertTrue(http.request.ended);
        call.halfClose();

        try {
            call.send(message("late"));
            fail("a message cannot follow the half-close");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void aCallMadeWithItsOneMessageCannotSendMore() throws Exception {
        GrpcClientCall call = client.call(http, WATCH, message("req"), handler);
        try {
            call.send(message("more"));
            fail("the request has ended");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void clientStreamingEndsWithOneResponse() throws Exception {
        GrpcClientCall call = client.call(http, COLLECT, handler);
        call.send(message("a"));
        call.halfClose();
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        r.bodyContent(response(2, "got 1"));
        finish(0, null);
        assertEquals(java.util.Arrays.asList("got 1"), handler.texts);
        assertEquals(java.util.Arrays.asList("0:null"), handler.completions);
    }

    @Test
    public void bidirectionalReceivesBeforeTheHalfClose() throws Exception {
        GrpcClientCall call = client.call(http, CHAT, handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        call.send(message("a"));
        r.bodyContent(response(1, "re a"));
        assertEquals(java.util.Arrays.asList("re a"), handler.texts);
        assertTrue(handler.completions.isEmpty());
        call.send(message("b"));
        r.bodyContent(response(2, "re b"));
        call.halfClose();
        finish(0, null);
        assertEquals(java.util.Arrays.asList("re a", "re b"), handler.texts);
        assertEquals(2, http.request.body.size());
    }

    @Test
    public void anErrorAfterMessagesIsReportedWithTheStatus() throws Exception {
        client.call(http, WATCH, message("req"), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        r.bodyContent(response(1, "one"));
        finish(GrpcStatus.NOT_FOUND, "gone%20away");
        assertEquals(java.util.Arrays.asList("one"), handler.texts);
        assertEquals(1, handler.errors.size());
        GrpcException e = (GrpcException) handler.errors.get(0);
        assertEquals(GrpcStatus.NOT_FOUND, e.getStatus());
        assertEquals(java.util.Arrays.asList("5:gone away"), handler.completions);
    }

    @Test
    public void aTrailersOnlyErrorCompletesWithItsStatus() throws Exception {
        client.call(http, WATCH, message("req"), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        finish(GrpcStatus.UNIMPLEMENTED, "nope");
        assertEquals(1, handler.errors.size());
        assertEquals(java.util.Arrays.asList("12:nope"), handler.completions);
    }

    @Test
    public void aSuccessfulStreamMayHaveNoMessages() throws Exception {
        client.call(http, WATCH, message("req"), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        finish(0, null);
        assertTrue(handler.errors.toString(), handler.errors.isEmpty());
        assertEquals(java.util.Arrays.asList("0:null"), handler.completions);
    }

    @Test
    public void aHandlerThatRefusesAMessageEndsTheCallWithAnError() throws Exception {
        handler.refuseSecond = true;
        client.call(http, WATCH, message("req"), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        r.bodyContent(response(1, "one"));
        r.bodyContent(response(2, "two"));
        r.bodyContent(response(3, "three"));
        finish(0, null);
        assertEquals(java.util.Arrays.asList("one"), handler.texts);
        assertEquals(1, handler.errors.size());
        assertEquals(GrpcStatus.INTERNAL, ((GrpcException) handler.errors.get(0)).getStatus());
        assertTrue(handler.completions.isEmpty());
    }

    @Test
    public void aLostConnectionIsAnErrorWithNoStatus() throws Exception {
        client.call(http, CHAT, handler);
        http.request.handler.failed(new IOException("reset"));
        assertEquals(1, handler.errors.size());
        assertTrue(handler.completions.isEmpty());
    }

    @Test
    public void nothingIsSentAfterTheCallHasEnded() throws Exception {
        GrpcClientCall call = client.call(http, CHAT, handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        r.endHeaders();
        finish(GrpcStatus.CANCELLED, "server stopped");
        call.send(message("late"));
        assertTrue(http.request.body.isEmpty());
        call.halfClose();
    }

    @Test
    public void aCancelledCallSendsNothingMore() throws Exception {
        GrpcClientCall call = client.call(http, CHAT, handler);
        call.cancel();
        assertTrue(http.request.cancelled);
        call.send(message("late"));
        assertTrue(http.request.body.isEmpty());
    }

    @Test
    public void whatTheTransportDoesNotTakeIsSentWithTheNextWrite() throws Exception {
        GrpcClientCall call = client.call(http, COLLECT, handler);
        http.request.accept = 3;
        call.send(message("abcdef"));
        // 5 bytes of header and 6 of payload: 3 taken now
        int taken = 0;
        for (int i = 0; i < http.request.body.size(); i++) {
            taken += http.request.body.get(i).length;
        }
        assertEquals(3, taken);
        http.request.accept = -1;
        call.halfClose();
        taken = 0;
        ByteBuffer all = ByteBuffer.allocate(64);
        for (int i = 0; i < http.request.body.size(); i++) {
            all.put(http.request.body.get(i));
        }
        all.flip();
        assertEquals(GrpcFraming.HEADER_SIZE + 6, all.remaining());
        assertEquals(6, GrpcFraming.readHeader(all));
        assertEquals("abcdef", StandardCharsets.UTF_8.decode(all).toString());
        assertTrue(http.request.ended);
    }
}
