/*
 * InMemoryMessageStore.java
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/**
 * Default in-memory implementation of {@link MQTTMessageStore}.
 *
 * <p>Payloads are accumulated in a {@link ByteArrayOutputStream} and
 * committed as a {@code byte[]} wrapped in an {@link MQTTMessageContent}.
 * The {@link MQTTMessageContent#isBuffered()} method always returns
 * {@code true}, and {@link MQTTMessageContent#asByteArray()} returns
 * the backing array directly.
 *
 * <p>This preserves the same memory characteristics as the pre-store
 * implementation while providing a clean override point for
 * persistent (file-backed, database-backed) storage.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InMemoryMessageStore implements MQTTMessageStore {

    @Override
    public MQTTMessageWriter createWriter() {
        return new InMemoryWriter();
    }

    static class InMemoryWriter implements MQTTMessageWriter {

        private ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public int write(ByteBuffer src) {
            int len = src.remaining();
            if (src.hasArray()) {
                buf.write(src.array(),
                        src.arrayOffset() + src.position(), len);
                src.position(src.limit());
            } else {
                byte[] bytes = new byte[len];
                src.get(bytes);
                buf.write(bytes, 0, len);
            }
            return len;
        }

        @Override
        public MQTTMessageContent commit() {
            byte[] data = buf.toByteArray();
            buf = null;
            return new InMemoryContent(data);
        }

        @Override
        public void discard() {
            buf = null;
        }

        @Override
        public boolean isOpen() {
            return buf != null;
        }

        @Override
        public void close() {
            buf = null;
        }
    }

    /**
     * In-memory content backed by a byte array.
     * Visible for use in coalesced delivery paths.
     */
    public static class InMemoryContent implements MQTTMessageContent {

        private byte[] data;

        public InMemoryContent(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data != null ? data.length : 0;
        }

        @Override
        public boolean isBuffered() {
            return true;
        }

        @Override
        public byte[] asByteArray() {
            return data;
        }

        @Override
        public ReadableByteChannel openChannel() throws IOException {
            if (data == null) {
                throw new IOException("Content has been released");
            }
            return Channels.newChannel(new ByteArrayInputStream(data));
        }

        @Override
        public void release() {
            data = null;
        }
    }
}
