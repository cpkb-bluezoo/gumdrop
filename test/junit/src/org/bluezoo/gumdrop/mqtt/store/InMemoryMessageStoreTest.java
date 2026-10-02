/*
 * InMemoryMessageStoreTest.java
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

package org.bluezoo.gumdrop.mqtt.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link InMemoryMessageStore} writers and content.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InMemoryMessageStoreTest {

    private static ByteBuffer direct(byte[] data) {
        ByteBuffer b = ByteBuffer.allocateDirect(data.length);
        b.put(data);
        b.flip();
        return b;
    }

    @Test
    public void heapAndDirectWritesAreConcatenated() throws IOException {
        MqttMessageWriter w = new InMemoryMessageStore().createWriter();
        assertTrue(w.isOpen());
        ByteBuffer heap = ByteBuffer.wrap(new byte[] {1, 2, 3});
        assertEquals(3, w.write(heap));
        assertFalse(heap.hasRemaining());
        ByteBuffer dir = direct(new byte[] {4, 5});
        assertEquals(2, w.write(dir));
        assertFalse(dir.hasRemaining());
        MqttMessageContent c = w.commit();
        assertFalse(w.isOpen());
        assertEquals(5L, c.size());
        assertTrue(c.isBuffered());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, c.asByteArray());
    }

    @Test
    public void heapBufferWithOffsetAndPosition() throws IOException {
        MqttMessageWriter w = new InMemoryMessageStore().createWriter();
        byte[] backing = new byte[] {9, 9, 7, 8, 9};
        ByteBuffer slice = ByteBuffer.wrap(backing, 2, 2).slice();
        assertEquals(2, w.write(slice));
        MqttMessageContent c = w.commit();
        assertArrayEquals(new byte[] {7, 8}, c.asByteArray());
    }

    @Test
    public void openChannelReadsContent() throws IOException {
        MqttMessageWriter w = new InMemoryMessageStore().createWriter();
        w.write(ByteBuffer.wrap(new byte[] {10, 20, 30}));
        MqttMessageContent c = w.commit();
        ReadableByteChannel ch = c.openChannel();
        try {
            ByteBuffer dst = ByteBuffer.allocate(8);
            int n = ch.read(dst);
            assertEquals(3, n);
            assertEquals(20, dst.get(1));
        } finally {
            ch.close();
        }
    }

    @Test
    public void releasedContentIsEmptyAndUnreadable() throws IOException {
        MqttMessageContent c = new InMemoryMessageStore.InMemoryContent(
                new byte[] {1});
        c.release();
        assertEquals(0L, c.size());
        assertNull(c.asByteArray());
        try {
            c.openChannel();
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void discardAndCloseCloseTheWriter() throws IOException {
        MqttMessageWriter a = new InMemoryMessageStore().createWriter();
        a.write(ByteBuffer.wrap(new byte[] {1}));
        a.discard();
        assertFalse(a.isOpen());
        MqttMessageWriter b = new InMemoryMessageStore().createWriter();
        b.close();
        assertFalse(b.isOpen());
    }

    @Test
    public void emptyCommit() throws IOException {
        MqttMessageContent c =
                new InMemoryMessageStore().createWriter().commit();
        assertEquals(0L, c.size());
        assertEquals(0, c.asByteArray().length);
    }
}
