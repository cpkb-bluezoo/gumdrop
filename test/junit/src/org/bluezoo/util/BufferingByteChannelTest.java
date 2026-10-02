/*
 * BufferingByteChannelTest.java
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

package org.bluezoo.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.junit.Test;

/**
 * Unit tests for {@link BufferingByteChannel}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BufferingByteChannelTest {

    /** Channel that records bytes and write call count. */
    private static class Sink implements WritableByteChannel {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int writes;
        boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            writes++;
            int n = src.remaining();
            byte[] b = new byte[n];
            src.get(b);
            out.write(b, 0, n);
            return n;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    public void smallWritesAreBufferedUntilFlush() throws IOException {
        Sink sink = new Sink();
        BufferingByteChannel ch = new BufferingByteChannel(sink, 16);
        int n = ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(3, n);
        assertEquals(0, sink.writes);
        ch.flush();
        assertEquals(1, sink.writes);
        assertArrayEquals(new byte[] {1, 2, 3}, sink.out.toByteArray());
        ch.flush();
        assertEquals(1, sink.writes);
    }

    @Test
    public void fullBufferFlushesAutomatically() throws IOException {
        Sink sink = new Sink();
        BufferingByteChannel ch = new BufferingByteChannel(sink, 4);
        byte[] data = new byte[10];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        int n = ch.write(ByteBuffer.wrap(data));
        assertEquals(10, n);
        assertEquals(8, sink.out.size());
        ch.close();
        assertArrayEquals(data, sink.out.toByteArray());
    }

    @Test
    public void closeFlushesAndClosesDelegateOnce() throws IOException {
        Sink sink = new Sink();
        BufferingByteChannel ch = new BufferingByteChannel(sink, 8);
        assertTrue(ch.isOpen());
        ch.write(ByteBuffer.wrap(new byte[] {9}));
        ch.close();
        assertFalse(ch.isOpen());
        assertFalse(sink.open);
        assertEquals(1, sink.out.size());
        ch.close();
        assertEquals(1, sink.writes);
    }

    @Test
    public void emptyWriteIsNoop() throws IOException {
        Sink sink = new Sink();
        BufferingByteChannel ch = new BufferingByteChannel(sink, 8);
        assertEquals(0, ch.write(ByteBuffer.allocate(0)));
        ch.close();
        assertEquals(0, sink.writes);
    }
}
