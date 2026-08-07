/*
 * PublishBody.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

import java.nio.ByteBuffer;

/**
 * Streams a message body to the broker after {@link
 * ClientChannel#basicPublish}.
 *
 * <p>AMQP's content-header frame declares the total body size up front
 * (there is no chunked-transfer-style "size unknown" option in the
 * protocol, unlike HTTP), but that total does not need to be
 * materialised as one contiguous buffer in memory — write it in
 * whatever chunks are convenient (as they're read off disk, generated,
 * relayed from another connection, etc.) via repeated {@link
 * #writeBody(ByteBuffer)} calls, mirroring
 * {@link org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientChannel#basicPublish
 */
public interface PublishBody {

    /**
     * The sequence number this publish was assigned, for correlating
     * {@link ConfirmListener} callbacks — 1-based counting from the most
     * recent {@link ClientChannel#confirmSelect}. Meaningless (returns 0)
     * if the channel is not in confirm mode.
     */
    long getSequenceNumber();

    /**
     * Writes a chunk of body content. May be called any number of times;
     * the total bytes written across all calls must equal the
     * {@code bodySize} passed to {@link ClientChannel#basicPublish}.
     *
     * @param chunk the content bytes; not retained after this call returns
     */
    void writeBody(ByteBuffer chunk);

    /**
     * Registers a one-shot callback invoked when the transport is ready
     * for more data. Use this to pace a large publish: write a chunk,
     * register a callback, write the next chunk from within it.
     *
     * @param callback the callback, or null to clear
     */
    void onWriteReady(Runnable callback);

    /**
     * Signals that all body content has been written.
     *
     * @throws IllegalStateException if fewer or more bytes were written
     *      than the declared {@code bodySize}
     */
    void complete();
}
