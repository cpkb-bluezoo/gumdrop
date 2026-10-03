/*
 * HttpMessageRecorderTest.java
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


package org.bluezoo.gumdrop.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.RecordingMessageHandler;
import org.junit.Test;

/**
 * Tests for {@link HttpMessageRecorder}: events are kept, then replayed to a
 * handler that did not exist when they happened, as a server must do when it
 * has to see the whole header section (for authentication, limits, routing)
 * before the application handler is bound.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpMessageRecorderTest {

    private static ByteBuffer b(byte[] bytes) {
        return ByteBuffer.wrap(bytes);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void sendRequest(HttpMessageHandler h, byte[] pathBytes) {
        FieldSectionAdapter a = new FieldSectionAdapter(h, HttpVersion.HTTP_2_0, FieldSectionAdapter.Kind.REQUEST);
        a.field(b(bytes(":method")), b(bytes("POST")));
        a.field(b(bytes(":scheme")), b(bytes("https")));
        a.field(b(bytes(":path")), b(pathBytes));
        a.field(b(bytes(":authority")), b(bytes("example.test")));
        a.field(b(bytes("content-type")), b(bytes("text/plain; charset=utf-8")));
        a.field(b(bytes("content-disposition")), b(bytes("attachment; filename=\"a.txt\"")));
        a.field(b(bytes("content-length")), b(bytes("5")));
        a.field(b(bytes("x-custom")), b(bytes("value")));
        assertTrue(a.finish());
    }

    @Test
    public void replayGivesTheSameEventsAsTheOriginal() {
        RecordingMessageHandler direct = new RecordingMessageHandler();
        sendRequest(direct, bytes("/p"));

        HttpMessageRecorder recorder = new HttpMessageRecorder();
        sendRequest(recorder, bytes("/p"));
        RecordingMessageHandler replayed = new RecordingMessageHandler();
        recorder.replay(replayed);

        assertEquals(direct.events, replayed.events);
        assertTrue(replayed.viewsReadOnly);
    }

    @Test
    public void whatWasRecordedSurvivesTheSourceBufferBeingReused() {
        byte[] path = bytes("/original");
        HttpMessageRecorder recorder = new HttpMessageRecorder();
        sendRequest(recorder, path);
        Arrays.fill(path, (byte) 'X');      // the parser reuses its buffers

        RecordingMessageHandler replayed = new RecordingMessageHandler();
        recorder.replay(replayed);
        assertTrue(replayed.events.contains("target /original"));
    }

    @Test
    public void replayCanBeRepeated() {
        HttpMessageRecorder recorder = new HttpMessageRecorder();
        sendRequest(recorder, bytes("/p"));
        RecordingMessageHandler first = new RecordingMessageHandler();
        RecordingMessageHandler second = new RecordingMessageHandler();
        recorder.replay(first);
        recorder.replay(second);
        assertEquals(first.events, second.events);
    }

    @Test
    public void everyKindOfEventIsKept() {
        HttpMessageRecorder recorder = new HttpMessageRecorder();
        recorder.version(HttpVersion.HTTP_1_1);
        recorder.method(HttpMethod.of("PURGE"));
        recorder.target(b(bytes("/t")));
        recorder.scheme(b(bytes("http")));
        recorder.authority(b(bytes("h")));
        recorder.protocol(b(bytes("websocket")));
        recorder.status(204);
        recorder.reason(b(bytes("No Content")));
        recorder.longHeader("age", 7L);
        recorder.header("x", b(bytes("y")));
        recorder.endHeaders();
        recorder.bodyContent(b(bytes("abc")));
        recorder.trailer("t", b(bytes("u")));
        recorder.endMessage();
        recorder.error(HttpError.MALFORMED, "detail");

        RecordingMessageHandler r = new RecordingMessageHandler();
        recorder.replay(r);
        assertEquals(Arrays.asList("version HTTP/1.1", "method PURGE", "target /t", "scheme http",
                "authority h", "protocol websocket", "status 204", "reason No Content", "long age 7",
                "header x y", "endHeaders", "body abc", "trailer t u", "endMessage", "error MALFORMED"),
                r.events);
    }

    @Test
    public void clearForgetsEverything() {
        HttpMessageRecorder recorder = new HttpMessageRecorder();
        sendRequest(recorder, bytes("/p"));
        assertFalse(recorder.isEmpty());
        recorder.clear();
        assertTrue(recorder.isEmpty());
        RecordingMessageHandler r = new RecordingMessageHandler();
        recorder.replay(r);
        assertEquals(new ArrayList<String>(), r.events);
    }
}
