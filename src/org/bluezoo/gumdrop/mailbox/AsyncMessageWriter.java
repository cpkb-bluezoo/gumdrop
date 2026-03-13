/*
 * AsyncMessageWriter.java
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
 * Asynchronous writer for appending a message to a mailbox.
 *
 * <p>This interface provides streaming, non-blocking message append.
 * Protocol handlers write chunks of message data as they arrive from the
 * network, and the mailbox implementation persists them asynchronously.
 *
 * <p>The lifecycle is:
 * <ol>
 *   <li>Obtain an instance via {@link Mailbox#openAsyncAppend}</li>
 *   <li>Call {@link #write} repeatedly as data arrives</li>
 *   <li>Call {@link #finish} to finalize the message</li>
 *   <li>On error, call {@link #abort} to clean up</li>
 * </ol>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Mailbox#openAsyncAppend(java.util.Set, java.time.OffsetDateTime)
 */
public interface AsyncMessageWriter extends Closeable {

    /**
     * Writes a chunk of message content asynchronously.
     * The handler is called when the write completes and the writer is
     * ready for the next chunk.
     *
     * @param src the buffer containing data to write
     * @param handler the completion handler
     */
    void write(ByteBuffer src,
               CompletionHandler<Integer, ByteBuffer> handler);

    /**
     * Returns {@code true} when the writer's internal buffer is full
     * and the caller should wait before sending more data. Used for
     * back-pressure from the storage layer.
     *
     * @return true if the caller should pause
     */
    boolean wantsPause();

    /**
     * Finalizes the append, moving the message into the mailbox.
     * The handler receives the UID of the new message on success.
     *
     * @param handler the completion handler receiving the UID
     */
    void finish(CompletionHandler<Long, Void> handler);

    /**
     * Aborts the append, cleaning up any temporary state.
     */
    void abort();

}
