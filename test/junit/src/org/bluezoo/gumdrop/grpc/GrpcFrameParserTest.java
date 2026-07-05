/*
 * GrpcFrameParserTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.grpc;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class GrpcFrameParserTest {

    @Test
    public void testStreamsPayloadWithoutBufferingEntireBody() {
        byte[] payload = "hello".getBytes();
        ByteBuffer framed = GrpcFraming.frame(payload);
        byte[] bytes = new byte[framed.remaining()];
        framed.get(bytes);

        List<Integer> chunkSizes = new ArrayList<>();
        GrpcFrameParser parser = new GrpcFrameParser(new GrpcEventHandler() {
            @Override
            public void startMessage(byte compressionFlag, int length) {
                assertEquals(5, length);
            }

            @Override
            public void messageData(ByteBuffer data) {
                chunkSizes.add(data.remaining());
            }

            @Override
            public void endMessage() {
            }

            @Override
            public void parseError(String message) {
                fail(message);
            }
        });

        parser.receive(ByteBuffer.wrap(bytes, 0, 7));
        parser.receive(ByteBuffer.wrap(bytes, 7, bytes.length - 7));

        assertTrue(parser.isMessageCompleted());
        assertEquals(2, chunkSizes.size());
        assertEquals(5, chunkSizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    public void testRejectsOversizedDeclaredLength() {
        ByteBuffer header = ByteBuffer.allocate(5);
        header.put((byte) 0);
        header.putInt(4096);
        header.flip();

        final boolean[] errored = {false};
        GrpcFrameParser parser = new GrpcFrameParser(new GrpcEventHandler() {
            @Override public void startMessage(byte compressionFlag, int length) { }
            @Override public void messageData(ByteBuffer data) { fail(); }
            @Override public void endMessage() { fail(); }
            @Override public void parseError(String message) { errored[0] = true; }
        });
        parser.setMaxMessageSize(1024);
        parser.receive(header);
        assertTrue(errored[0]);
    }
}
