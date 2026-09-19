/*
 * GrpcFrameParserTest.java
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

package org.bluezoo.gumdrop.grpc;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
        int totalChunkSize = 0;
        for (int size : chunkSizes) {
            totalChunkSize += size;
        }
        assertEquals(5, totalChunkSize);
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
