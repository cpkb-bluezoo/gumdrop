/*
 * MQTTMessageContent.java
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
import java.nio.channels.ReadableByteChannel;

/**
 * Readable handle for a stored MQTT message payload.
 *
 * <p>Replaces {@code byte[]} in the publish pipeline, allowing
 * payloads to be backed by memory, files, or any other storage.
 *
 * <p>Small payloads that fit in memory report {@link #isBuffered()}
 * as {@code true} and can be retrieved via {@link #asByteArray()}
 * for efficient single-buffer encoding. Large payloads should be
 * streamed via {@link #openChannel()}.
 *
 * <p>When the content is no longer needed, call {@link #release()}
 * to free any underlying storage resources.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MQTTMessageWriter#commit()
 * @see MQTTMessageStore
 */
public interface MQTTMessageContent {

    /**
     * Returns the size of the payload in bytes.
     *
     * @return the payload size
     */
    long size();

    /**
     * Returns {@code true} if the payload is held entirely in memory.
     *
     * <p>When true, {@link #asByteArray()} returns the backing array
     * without copying and is the preferred access method for encoding
     * the payload in a single buffer.
     *
     * @return true if the payload is buffered in memory
     */
    boolean isBuffered();

    /**
     * Returns the payload as a byte array.
     *
     * <p>For buffered content this is a direct reference to the
     * backing array. For non-buffered content this may load the
     * entire payload into memory.
     *
     * @return the payload bytes
     */
    byte[] asByteArray();

    /**
     * Opens a new channel for reading the payload from the beginning.
     *
     * <p>The caller is responsible for closing the returned channel.
     *
     * @return a readable channel positioned at the start of the payload
     * @throws IOException if an I/O error occurs
     */
    ReadableByteChannel openChannel() throws IOException;

    /**
     * Releases any storage resources held by this content.
     *
     * <p>For in-memory content this is a no-op (the byte array
     * becomes eligible for GC). For file-backed content this
     * deletes the backing file.
     *
     * <p>After release, the content must not be read.
     */
    void release();
}
