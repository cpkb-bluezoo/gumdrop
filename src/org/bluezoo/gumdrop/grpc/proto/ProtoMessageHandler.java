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

/**
 * High-level semantic handler for protobuf message parsing events.
 *
 * <p>Analogous to {@link org.bluezoo.json.JSONContentHandler} and
 * {@link org.bluezoo.gumdrop.mime.MIMEHandler}. Receives semantic events
 * (message start/end, field name and value) rather than low-level wire format.
 * The application implements this interface to process messages without
 * building in-memory structures.
 *
 * <h3>Example event sequence</h3>
 * <pre>
 * startMessage("GetUserRequest")
 * field("user_id", 42)
 * startField("address", "Address")
 *   startMessage("Address")
 *   field("street", "123 Main")
 *   endMessage()
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
     * Start of a message (root or nested).
     *
     * @param typeName the fully qualified message type name
     * @throws ProtoParseException if processing fails
     */
    void startMessage(String typeName) throws ProtoParseException;

    /**
     * End of the current message.
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
     * Start of a nested message field.
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
}
