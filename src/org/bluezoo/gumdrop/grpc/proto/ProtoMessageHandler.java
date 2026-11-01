/*
 * ProtoMessageHandler.java
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

package org.bluezoo.gumdrop.grpc.proto;

import java.nio.ByteBuffer;

/**
 * High-level semantic handler for protobuf message parsing events.
 *
 * <p>Analogous to {@link org.bluezoo.json.JSONContentHandler} and
 * {@link org.bluezoo.gumdrop.mime.MimeHandler}. Receives semantic events
 * (message start/end, field name and value) rather than low-level wire format.
 * The application implements this interface to process messages without
 * building in-memory structures.
 *
 * <p>{@link #startMessage} and {@link #endMessage} bracket a whole message as
 * it is received, so a handler can treat {@code endMessage()} as "this message
 * is complete" without tracking depth. A nested message is not bracketed by
 * them: it is the content between {@link #startField} and {@link #endField}.
 *
 * <p>A handler can ask for a bytes or string field to be streamed rather than
 * delivered whole by {@link #field}, by returning true from
 * {@link #isStreaming}. The field is then reported as {@link #startBytes},
 * any number of {@link #bytesContent} pieces and {@link #endBytes}, and is
 * never held in full. Use this for large fields.
 *
 * <h3>Example event sequence</h3>
 * <pre>
 * startMessage("GetUserRequest")
 * field("user_id", 42)
 * startField("address", "Address")
 *   field("street", "123 Main")
 * endField()
 * endMessage()
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ProtoMessageHandler {

    /**
     * Receives the locator for parse position information.
     *
     * @param locator the locator
     */
    void setLocator(ProtoLocator locator);

    /**
     * Start of a message. Called once for each message received, not for
     * messages nested inside it.
     *
     * @param typeName the fully qualified message type name
     * @throws ProtoParseException if processing fails
     */
    void startMessage(String typeName) throws ProtoParseException;

    /**
     * End of the message: it has been received in full. Not called for
     * nested messages.
     *
     * @throws ProtoParseException if processing fails
     */
    void endMessage() throws ProtoParseException;

    /**
     * Scalar field. Value is String, Number, Boolean, or byte[] for bytes.
     *
     * @param name the field name
     * @param value the scalar value
     * @throws ProtoParseException if processing fails
     */
    void field(String name, Object value) throws ProtoParseException;

    /**
     * Start of a nested message field; the fields of the nested message
     * follow, up to {@link #endField}.
     *
     * @param name the field name
     * @param typeName the nested message type name
     * @throws ProtoParseException if processing fails
     */
    void startField(String name, String typeName) throws ProtoParseException;

    /**
     * End of a nested message field.
     *
     * @throws ProtoParseException if processing fails
     */
    void endField() throws ProtoParseException;

    /**
     * Returns true if the named bytes or string field should be streamed
     * rather than delivered whole by {@link #field}. Asked for each such
     * field as it is met; other field types are never streamed. The default
     * is false.
     *
     * @param name the field name
     * @return true if the field is to be streamed
     */
    default boolean isStreaming(String name) {
        return false;
    }

    /**
     * Start of a streamed field. Followed by zero or more
     * {@link #bytesContent} calls and then {@link #endBytes}. A streamed
     * string field is delivered as its UTF-8 bytes.
     *
     * @param name the field name
     * @param length the whole length of the payload, in bytes
     * @throws ProtoParseException if processing fails
     */
    default void startBytes(String name, int length) throws ProtoParseException {
    }

    /**
     * A piece of the payload of the streamed field. The buffer is valid only
     * for the duration of the call. Bytes the handler reads, by advancing the
     * buffer's position, are consumed; those it leaves are offered again at
     * the front of the next piece. The default consumes the whole piece.
     *
     * @param data the piece of the payload
     * @throws ProtoParseException if processing fails
     */
    default void bytesContent(ByteBuffer data) throws ProtoParseException {
        data.position(data.limit());
    }

    /**
     * End of the streamed field: its payload is complete.
     *
     * @throws ProtoParseException if processing fails
     */
    default void endBytes() throws ProtoParseException {
    }
}
