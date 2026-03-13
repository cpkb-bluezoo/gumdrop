/*
 * AsyncMessageContent.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;

/**
 * Asynchronous, positional reader for message content.
 *
 * <p>This is the mailbox-level abstraction for non-blocking message reads.
 * Protocol handlers consume this interface without knowledge of the
 * underlying storage: a Maildir implementation wraps an
 * {@link java.nio.channels.AsynchronousFileChannel}, a database backend
 * can serve from async BLOB reads, and an in-memory backend can copy from
 * a {@link ByteBuffer}.
 *
 * <p>All read operations are positional (no shared file position) so the
 * same instance may be read concurrently from multiple offsets.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Mailbox#openAsyncContent(int)
 */
public interface AsyncMessageContent extends Closeable {

    /**
     * Returns the total content size in bytes.
     *
     * @return the size of the complete message (headers + body)
     */
    long size();

    /**
     * Returns the byte offset where the message body begins (after the
     * CRLFCRLF separator between headers and body). Returns {@code -1}
     * if the offset is unknown or cannot be determined.
     *
     * @return the body offset, or -1 if unknown
     */
    long bodyOffset();

    /**
     * Reads bytes starting at the given file position into {@code dst}.
     * The completion handler receives the number of bytes read, or
     * {@code -1} at end-of-content.
     *
     * @param dst the destination buffer
     * @param position the byte position to start reading from
     * @param handler the completion handler
     */
    void read(ByteBuffer dst, long position,
              CompletionHandler<Integer, ByteBuffer> handler);

}
