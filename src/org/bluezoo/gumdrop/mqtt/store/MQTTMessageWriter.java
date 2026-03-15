/*
 * MQTTMessageWriter.java
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
import java.nio.channels.WritableByteChannel;

/**
 * Writable handle for accumulating an MQTT message payload.
 *
 * <p>Extends {@link WritableByteChannel} so callers can write payload
 * chunks as {@link java.nio.ByteBuffer} instances. Once all data has
 * been written, call {@link #commit()} to finalize the write and
 * obtain a readable {@link MQTTMessageContent} handle.
 *
 * <p>If the payload should be abandoned (e.g. authorization rejected),
 * call {@link #discard()} to release any resources without committing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MQTTMessageStore#createWriter()
 * @see MQTTMessageContent
 */
public interface MQTTMessageWriter extends WritableByteChannel {

    /**
     * Finalizes the written data and returns a readable content handle.
     *
     * <p>After commit, this writer is closed and must not be written
     * to again.
     *
     * @return a readable handle for the accumulated payload
     * @throws IOException if an I/O error occurs
     */
    MQTTMessageContent commit() throws IOException;

    /**
     * Discards the written data without committing.
     *
     * <p>Releases any resources (temp files, buffers) held by this
     * writer. After discard, this writer is closed and must not be
     * written to again.
     *
     * @throws IOException if an I/O error occurs
     */
    void discard() throws IOException;
}
