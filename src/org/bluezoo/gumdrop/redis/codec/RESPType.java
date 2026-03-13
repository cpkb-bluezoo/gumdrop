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

import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * RESP (Redis Serialization Protocol) data types
 * (RESP spec — "RESP protocol description").
 *
 * <p>Each type is identified by a single-byte prefix in the wire format.
 * RESP2 types: Simple String (+), Error (-), Integer (:), Bulk String ($),
 * Array (*). RESP3 adds: Map (%), Set (~), Double (,), Boolean (#),
 * Null (_), Push (&gt;), Verbatim String (=), Big Number ((), Blob Error (!).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 */
public enum RESPType {

    // RESP2 types

    /** Simple string, prefixed by '+'. */
    SIMPLE_STRING('+'),

    /** Error, prefixed by '-'. */
    ERROR('-'),

    /** Integer, prefixed by ':'. A signed 64-bit integer. */
    INTEGER(':'),

    /** Bulk string, prefixed by '$'. Binary-safe with explicit length. */
    BULK_STRING('$'),

    /** Array, prefixed by '*'. An ordered collection of RESP values. */
    ARRAY('*'),

    // RESP3 types

    /** Map, prefixed by '%'. Key-value pairs (RESP3). */
    MAP('%'),

    /** Set, prefixed by '~'. Unordered collection (RESP3). */
    SET('~'),

    /** Double, prefixed by ','. IEEE 754 double (RESP3). */
    DOUBLE(','),

    /** Boolean, prefixed by '#'. True (#t) or false (#f) (RESP3). */
    BOOLEAN('#'),

    /** Null, prefixed by '_'. Explicit null type (RESP3). */
    NULL('_'),

    /** Push, prefixed by '&gt;'. Server-initiated out-of-band data (RESP3). */
    PUSH('>'),

    /** Verbatim string, prefixed by '='. Like bulk string with encoding hint (RESP3). */
    VERBATIM_STRING('='),

    /** Big number, prefixed by '('. Arbitrary-precision integer (RESP3). */
    BIG_NUMBER('('),

    /** Blob error, prefixed by '!'. Like bulk string but an error (RESP3). */
    BLOB_ERROR('!');

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
            case '+': return SIMPLE_STRING;
            case '-': return ERROR;
            case ':': return INTEGER;
            case '$': return BULK_STRING;
            case '*': return ARRAY;
            case '%': return MAP;
            case '~': return SET;
            case ',': return DOUBLE;
            case '#': return BOOLEAN;
            case '_': return NULL;
            case '>': return PUSH;
            case '=': return VERBATIM_STRING;
            case '(': return BIG_NUMBER;
            case '!': return BLOB_ERROR;
            default:
                String msg = MessageFormat.format(RESPDecoder.L10N.getString("err.unknown_type_prefix"), (char) prefix);
                throw new RESPException(msg);
        }
    }

}
