/*
 * FieldType.java
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
 * Protobuf field type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum FieldType {

    DOUBLE,
    FLOAT,
    INT32,
    INT64,
    UINT32,
    UINT64,
    SINT32,
    SINT64,
    FIXED32,
    FIXED64,
    SFIXED32,
    SFIXED64,
    BOOL,
    STRING,
    BYTES,
    MESSAGE,
    ENUM,
    MAP;

    /**
     * Returns true if this type is a scalar (not message, enum, or map).
     */
    public boolean isScalar() {
        return this != MESSAGE && this != ENUM && this != MAP;
    }

    /**
     * Returns true if this type uses wire type VARINT.
     */
    public boolean isVarint() {
        return this == INT32 || this == INT64 || this == UINT32 || this == UINT64
                || this == SINT32 || this == SINT64 || this == BOOL || this == ENUM;
    }

    /**
     * Returns true if this type uses wire type I64.
     */
    public boolean isFixed64() {
        return this == FIXED64 || this == SFIXED64 || this == DOUBLE;
    }

    /**
     * Returns true if this type uses wire type I32.
     */
    public boolean isFixed32() {
        return this == FIXED32 || this == SFIXED32 || this == FLOAT;
    }

    /**
     * Returns true if this type uses wire type LEN.
     */
    public boolean isLengthDelimited() {
        return this == STRING || this == BYTES || this == MESSAGE || this == MAP;
    }
}
