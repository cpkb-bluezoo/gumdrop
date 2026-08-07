/*
 * WebSocketReassemblyGrowthTest.java
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

package org.bluezoo.gumdrop.websocket;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for issue #119: fragmented WebSocket message
 * reassembly grew its buffer by an exact-fit amount on every
 * continuation frame instead of geometrically, making total reassembly
 * cost O(n^2) in the number of fragments (the first fragment allocates
 * an exact-fit buffer, so every subsequent continuation frame always
 * triggers a full reallocation-and-copy of everything received so far).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketReassemblyGrowthTest {

    private static class TestConnection extends WebSocketConnection {
        String lastTextMessage;
        final List<ByteBuffer> sentFrames = new ArrayList<>();

        TestConnection() {
            setTransport(new WebSocketTransport() {
                @Override
                public void sendFrame(ByteBuffer frameData) {
                    byte[] copy = new byte[frameData.remaining()];
                    frameData.get(copy);
                    sentFrames.add(ByteBuffer.wrap(copy));
                }
                @Override
                public void close(boolean normalClose) { }
            });
        }

        @Override protected void opened() { }
        @Override protected void textMessageReceived(String message) {
            lastTextMessage = message;
        }
        @Override protected void binaryMessageReceived(ByteBuffer data) { }
        @Override protected void closed(int code, String reason) { }
        @Override protected void error(Throwable cause) { }

        void openConnection() {
            notifyConnectionOpen();
        }

        /** Reads the private reassembly buffer's current capacity via reflection. */
        int messageBufferCapacity() throws Exception {
            Field f = WebSocketConnection.class.getDeclaredField("messageBuffer");
            f.setAccessible(true);
            ByteBuffer buf = (ByteBuffer) f.get(this);
            return buf == null ? -1 : buf.capacity();
        }
    }

    private TestConnection createOpenConnection() {
        TestConnection conn = new TestConnection();
        conn.openConnection();
        conn.setMaxMessageSize(0); // unlimited, isolate the growth behaviour
        return conn;
    }

    private static ByteBuffer buildFrame(boolean fin, int opcode, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        assertTrue("test fragments must stay under the 126-byte 1-byte-length "
                + "encoding for simplicity", bytes.length < 126);
        ByteBuffer buf = ByteBuffer.allocate(2 + bytes.length);
        buf.put((byte) ((fin ? 0x80 : 0x00) | opcode));
        buf.put((byte) bytes.length);
        buf.put(bytes);
        buf.flip();
        return buf;
    }

    @Test
    public void testManyFragmentsReassembleCorrectly() throws IOException {
        TestConnection conn = createOpenConnection();
        int fragmentCount = 50;
        StringBuilder expected = new StringBuilder();

        for (int i = 0; i < fragmentCount; i++) {
            String chunk = "fragment-" + i + ";";
            expected.append(chunk);
            boolean first = (i == 0);
            boolean last = (i == fragmentCount - 1);
            int opcode = first ? 0x01 /* text */ : 0x00 /* continuation */;
            conn.processIncomingData(buildFrame(last, opcode, chunk));
        }

        assertEquals("message must reassemble correctly across many fragments",
                expected.toString(), conn.lastTextMessage);
    }

    @Test
    public void testBufferGrowsGeometricallyNotExactFit() throws Exception {
        TestConnection conn = createOpenConnection();
        int fragmentCount = 40;
        String chunk = "0123456789"; // 10 bytes per fragment

        conn.processIncomingData(buildFrame(false, 0x01, chunk));
        int reallocations = 0;
        int lastCapacity = conn.messageBufferCapacity();

        for (int i = 1; i < fragmentCount; i++) {
            boolean last = (i == fragmentCount - 1);
            conn.processIncomingData(buildFrame(last, 0x00, chunk));
            if (last) {
                break; // buffer is nulled out once the message is delivered
            }
            int capacity = conn.messageBufferCapacity();
            if (capacity != lastCapacity) {
                reallocations++;
                lastCapacity = capacity;
            }
        }

        // Exact-fit growth (the bug) reallocates on literally every
        // fragment after the first: fragmentCount - 2 reallocations for
        // this loop. Geometric (doubling) growth reallocates roughly
        // log2(total size / initial size) times — a small constant here,
        // nowhere near linear in the fragment count.
        assertTrue("buffer growth must be geometric, not exact-fit per "
                        + "fragment (saw " + reallocations + " reallocations "
                        + "for " + fragmentCount + " fragments)",
                reallocations < fragmentCount / 4);
    }
}
