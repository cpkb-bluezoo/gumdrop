/*
 * GrpcClientMetadataTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.grpc.GrpcException;
import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.GrpcTimeout;
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
 * Request and response metadata, and deadlines, on {@link GrpcClient}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcClientMetadataTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Req { string name = 1; }\n"
            + "message Res { int32 code = 1; string text = 2; }\n"
            + "service Svc {\n"
            + "  rpc Say (Req) returns (Res);\n"
            + "  rpc Chat (stream Req) returns (stream Res);\n"
            + "}\n";

    private static final String SAY = "/example.v1.Svc/Say";
    private static final String CHAT = "/example.v1.Svc/Chat";

    private static final class StubRequest implements HttpRequest {
        final List<String> headers = new ArrayList<String>();
        HttpResponseHandler handler;
        int headersAtEndHeaders = -1;
        boolean cancelled;
        boolean timersSupported = true;
        long timerDelay = -1;
        Runnable timerTask;
        boolean timerCancelled;

        @Override public void header(String name, ByteBuffer rawValue) {
            headers.add(name + ": " + StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()));
        }
        @Override public void priority(int weight) { }
        @Override public void dependency(HttpRequest parent) { }
        @Override public void exclusive(boolean exclusive) { }
        @Override public void longHeader(String name, long value) { header(name, Long.toString(value)); }
        @Override public void dateHeader(String name, Instant value) { header(name, value.toString()); }
        @Override public void contentType(org.bluezoo.gumdrop.mime.ContentType contentType) { header("Content-Type", contentType.toHeaderValue()); }
        @Override public void contentDisposition(org.bluezoo.gumdrop.mime.ContentDisposition contentDisposition) { header("Content-Disposition", contentDisposition.toHeaderValue()); }
        @Override public void endHeaders() { headersAtEndHeaders = headers.size(); }
        @Override public int bodyContent(ByteBuffer data) {
            int n = data.remaining();
            data.position(data.limit());
            return n;
        }
        @Override public void endMessage() { }
        @Override public void cancel() { cancelled = true; }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            if (!timersSupported) {
                return null;
            }
            timerDelay = delayMs;
            timerTask = callback;
            return new TimerHandle() {
                public void cancel() { timerCancelled = true; }
                public boolean isCancelled() { return timerCancelled; }
            };
        }
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
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        GrpcMetadata initial;
        GrpcMetadata trailing;

        @Override
        public ProtoMessageHandler startMessage(String messageTypeName) {
            events.add("message");
            return new ProtoDefaultHandler();
        }

        @Override
        public void onInitialMetadata(GrpcMetadata metadata) {
            events.add("initial");
            initial = metadata;
        }

        @Override
        public void onTrailingMetadata(GrpcMetadata metadata) {
            events.add("trailing");
            trailing = metadata;
        }

        @Override
        public void onError(Exception e) {
            events.add("error");
            errors.add(e);
        }

        @Override
        public void onComplete(int status, String message) {
            events.add("complete:" + status + ":" + message);
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

    private static ByteBuffer message() {
        return ByteBuffer.wrap("req".getBytes(StandardCharsets.UTF_8));
    }

    private ByteBuffer response() throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(128);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer s = new ProtoModelSerializer(proto);
        s.startMessage(writer, "example.v1.Res");
        s.field(writer, "code", 1);
        s.endMessage();
        return GrpcFraming.frame(channel.toByteBuffer());
    }

    private void header(String name, String value) {
        http.request.handler.header(name, MessageEvents.octets(value));
    }

    private static String timeoutHeader(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).startsWith("grpc-timeout: ")) {
                return headers.get(i).substring("grpc-timeout: ".length());
            }
        }
        return null;
    }

    @Test
    public void requestMetadataIsSentAsHeaders() {
        GrpcCallOptions options = new GrpcCallOptions();
        options.getMetadata().add("x-a", "1");
        options.getMetadata().add("x-a", "2");
        options.getMetadata().addBinary("x-b-bin", new byte[] {1, 2, 3});
        client.call(http, SAY, message(), handler, options);
        assertTrue(http.request.headers.contains("x-a: 1"));
        assertTrue(http.request.headers.contains("x-a: 2"));
        assertTrue(http.request.headers.contains("x-b-bin: AQID"));
        assertTrue(http.request.headers.contains("Content-Type: application/grpc"));
    }

    @Test
    public void metadataGoesInTheHeaderSectionOfAStreamingCall() {
        GrpcCallOptions options = new GrpcCallOptions();
        options.getMetadata().add("x-a", "1");
        options.timeout(2, TimeUnit.SECONDS);
        client.call(http, CHAT, handler, options);
        assertTrue(http.request.headers.contains("x-a: 1"));
        // both were added before the header section ended
        assertEquals(http.request.headers.size(), http.request.headersAtEndHeaders);
        assertNotNull(timeoutHeader(http.request.headers));
    }

    @Test
    public void aTimeoutIsSentAndScheduled() {
        GrpcCallOptions options = new GrpcCallOptions().timeout(5, TimeUnit.SECONDS);
        client.call(http, SAY, message(), handler, options);
        assertEquals(5000000000L, GrpcTimeout.parse(timeoutHeader(http.request.headers)));
        assertEquals(5000L, http.request.timerDelay);
    }

    @Test
    public void aDeadlineIsSentAsTheTimeLeft() {
        GrpcCallOptions options = new GrpcCallOptions().deadline(Instant.now().plusSeconds(10));
        client.call(http, SAY, message(), handler, options);
        long nanos = GrpcTimeout.parse(timeoutHeader(http.request.headers));
        assertTrue(nanos > 5000000000L && nanos <= 10000000000L);
        assertTrue(http.request.timerDelay > 5000L && http.request.timerDelay <= 10000L);
    }

    @Test
    public void withoutOptionsThereIsNoDeadline() {
        client.call(http, SAY, message(), handler);
        assertNull(timeoutHeader(http.request.headers));
        assertNull(http.request.timerTask);
    }

    @Test
    public void aCallStillWorksWhereTheTransportHasNoTimers() {
        http.request.timersSupported = false;
        client.call(http, SAY, message(), handler,
                new GrpcCallOptions().timeout(1, TimeUnit.SECONDS));
        assertNotNull(timeoutHeader(http.request.headers));
        assertNull(http.request.timerTask);
    }

    @Test
    public void whenTheDeadlinePassesTheCallFailsLocallyAndIsCancelled() {
        client.call(http, SAY, message(), handler,
                new GrpcCallOptions().timeout(1, TimeUnit.SECONDS));
        http.request.timerTask.run();
        assertEquals(1, handler.errors.size());
        assertEquals(GrpcStatus.DEADLINE_EXCEEDED,
                ((GrpcException) handler.errors.get(0)).getStatus());
        assertTrue(handler.events.contains("complete:4:Deadline exceeded"));
        assertTrue(http.request.cancelled);
        // the cancellation the transport reports back is not a second failure
        http.request.handler.failed(new java.util.concurrent.CancellationException());
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void theTimerIsCancelledWhenTheCallCompletes() throws Exception {
        client.call(http, SAY, message(), handler,
                new GrpcCallOptions().timeout(1, TimeUnit.SECONDS));
        http.request.handler.status(HttpStatus.OK.code);
        http.request.handler.endHeaders();
        http.request.handler.bodyContent(response());
        header("grpc-status", "0");
        http.request.handler.endMessage();
        assertTrue(http.request.timerCancelled);
        // a timer that fires anyway changes nothing
        http.request.timerTask.run();
        assertFalse(http.request.cancelled);
        assertTrue(handler.errors.isEmpty());
    }

    @Test
    public void initialAndTrailingMetadataAreDeliveredAroundTheMessages() throws Exception {
        client.call(http, SAY, message(), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        header("x-served-by", "node-1");
        header("x-id-bin", "AQID");
        header("grpc-encoding", "identity");
        r.endHeaders();
        r.bodyContent(response());
        header("x-cost", "42");
        header("grpc-status", "0");
        r.endMessage();

        assertEquals(java.util.Arrays.asList("initial", "message", "trailing", "complete:0:null"),
                handler.events);
        assertEquals("node-1", handler.initial.get("x-served-by"));
        assertArrayEquals(new byte[] {1, 2, 3}, handler.initial.getBinary("x-id-bin"));
        assertEquals(java.util.Arrays.asList("x-served-by", "x-id-bin"), handler.initial.keys());
        assertEquals(java.util.Arrays.asList("x-cost"), handler.trailing.keys());
    }

    @Test
    public void aTrailersOnlyResponsesMetadataIsTrailingMetadata() throws Exception {
        client.call(http, SAY, message(), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        header("x-why", "because");
        header("grpc-status", "5");
        header("grpc-message", "gone");
        r.endHeaders();
        r.endMessage();
        assertEquals(java.util.Arrays.asList("trailing", "error", "complete:5:gone"),
                handler.events);
        assertEquals("because", handler.trailing.get("x-why"));
    }

    @Test
    public void metadataKeysAreLowerCasedAndBadEntriesDropped() throws Exception {
        client.call(http, SAY, message(), handler);
        HttpResponseHandler r = http.request.handler;
        r.status(HttpStatus.OK.code);
        header("X-Mixed", "v");
        header("x-bad-bin", "***");
        r.endHeaders();
        r.bodyContent(response());
        header("grpc-status", "0");
        r.endMessage();
        assertEquals("v", handler.initial.get("x-mixed"));
        assertNull(handler.initial.get("x-bad-bin"));
    }
}
