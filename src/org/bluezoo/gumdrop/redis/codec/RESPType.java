/*
 * RESPType.java
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

package org.bluezoo.gumdrop.redis.codec;

/**
 * RESP (Redis Serialization Protocol) data types.
 *
 * <p>Each type is identified by a single-byte prefix in the wire format.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum RESPType {

    /**
     * Simple string, prefixed by '+'.
     * Used for short, non-binary strings like "OK" or "PONG".
     */
    SIMPLE_STRING('+'),

    /**
     * Error, prefixed by '-'.
     * Contains an error message, typically with an error type prefix.
     */
    ERROR('-'),

    /**
     * Integer, prefixed by ':'.
     * A signed 64-bit integer.
     */
    INTEGER(':'),

    /**
     * Bulk string, prefixed by '$'.
     * A binary-safe string with explicit length.
     * Can be null (represented as "$-1\r\n").
     */
    BULK_STRING('$'),

    /**
     * Array, prefixed by '*'.
     * An ordered collection of RESP values.
     * Can be null (represented as "*-1\r\n").
     */
    ARRAY('*');

    private final byte prefix;

    RESPType(char prefix) {
        this.prefix = (byte) prefix;
    }

    /**
     * Returns the wire format prefix byte for this type.
     *
     * @return the prefix byte
     */
    public byte getPrefix() {
        return prefix;
    }

    /**
     * Returns the RESP type for the given prefix byte.
     *
     * @param prefix the prefix byte
     * @return the corresponding RESP type
     * @throws RESPException if the prefix is not recognized
     */
    public static RESPType fromPrefix(byte prefix) throws RESPException {
        switch (prefix) {
            case '+':
                return SIMPLE_STRING;
            case '-':
                return ERROR;
            case ':':
                return INTEGER;
            case '$':
                return BULK_STRING;
            case '*':
                return ARRAY;
            default:
                throw new RESPException("Unknown RESP type prefix: " + (char) prefix);
        }
    }

}

