/*
 * MessageHandler.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Receives the sections of a message as a {@link MessageParser} decodes
 * them.
 *
 * <p>Small sections (header, properties, annotations, application
 * properties, footer) are delivered decoded. The body is delivered as it
 * arrives: each {@code data} section is streamed as a
 * {@link #startData}, some {@link #dataChunk}s and an {@link #endData},
 * so a large binary payload is never assembled in memory.
 *
 * <p>Map keys and values use the Java mapping documented on
 * {@link Amqp1Decoder}. {@code ByteBuffer} parameters are only valid for
 * the duration of the call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageParser
 */
public interface MessageHandler {

    void header(MessageHeader header);

    void deliveryAnnotations(Map<Object, Object> annotations);

    void messageAnnotations(Map<Object, Object> annotations);

    void properties(MessageProperties properties);

    void applicationProperties(Map<Object, Object> properties);

    /**
     * A {@code data} section began.
     *
     * @param length the number of body octets that will follow
     */
    void startData(long length);

    /** The next body octets of the current {@code data} section. */
    void dataChunk(ByteBuffer chunk);

    /** The current {@code data} section is complete. */
    void endData();

    /** An {@code amqp-sequence} section: one row of a structured body. */
    void amqpSequence(List<Object> row);

    /** An {@code amqp-value} section: the whole body as one AMQP value. */
    void amqpValue(Object value);

    void footer(Map<Object, Object> footer);

    /** The message is complete and well formed. */
    void endMessage();

    /**
     * The message is malformed. The parser stops; nothing further is delivered.
     *
     * @param message what is wrong
     */
    void messageError(String message);
}
