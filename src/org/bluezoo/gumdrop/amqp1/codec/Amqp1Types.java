/*
 * Amqp1Types.java
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

/**
 * Format codes of the AMQP 1.0 type system (core specification, section 1).
 *
 * <p>Every encoded value starts with a one-octet format code (the
 * "constructor"), except described values, which start with
 * {@link #DESCRIBED} followed by a descriptor value and then the
 * constructor of the value being described. The high nibble of a format
 * code selects the width class: {@code 0x4_} zero-width, {@code 0x5_}
 * one octet, {@code 0x6_} two, {@code 0x7_} four, {@code 0x8_} eight,
 * {@code 0x9_} sixteen, {@code 0xA_}/{@code 0xC_}/{@code 0xE_} variable
 * with a one-octet size, {@code 0xB_}/{@code 0xD_}/{@code 0xF_} variable
 * with a four-octet size.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-types-v1.0-os.html">AMQP 1.0 types</a>
 */
public final class Amqp1Types {

    private Amqp1Types() {
    }

    public static final int DESCRIBED = 0x00;
    public static final int NULL = 0x40;
    public static final int BOOLEAN_TRUE = 0x41;
    public static final int BOOLEAN_FALSE = 0x42;
    public static final int BOOLEAN = 0x56;
    public static final int UINT_0 = 0x43;
    public static final int ULONG_0 = 0x44;
    public static final int UBYTE = 0x50;
    public static final int USHORT = 0x60;
    public static final int UINT = 0x70;
    public static final int UINT_SMALL = 0x52;
    public static final int ULONG = 0x80;
    public static final int ULONG_SMALL = 0x53;
    public static final int BYTE = 0x51;
    public static final int SHORT = 0x61;
    public static final int INT = 0x71;
    public static final int INT_SMALL = 0x54;
    public static final int LONG = 0x81;
    public static final int LONG_SMALL = 0x55;
    public static final int FLOAT = 0x72;
    public static final int DOUBLE = 0x82;
    public static final int DECIMAL32 = 0x74;
    public static final int DECIMAL64 = 0x84;
    public static final int DECIMAL128 = 0x94;
    public static final int CHAR = 0x73;
    public static final int TIMESTAMP = 0x83;
    public static final int UUID = 0x98;
    public static final int BINARY8 = 0xA0;
    public static final int BINARY32 = 0xB0;
    public static final int STRING8 = 0xA1;
    public static final int STRING32 = 0xB1;
    public static final int SYMBOL8 = 0xA3;
    public static final int SYMBOL32 = 0xB3;
    public static final int LIST0 = 0x45;
    public static final int LIST8 = 0xC0;
    public static final int LIST32 = 0xD0;
    public static final int MAP8 = 0xC1;
    public static final int MAP32 = 0xD1;
    public static final int ARRAY8 = 0xE0;
    public static final int ARRAY32 = 0xF0;
}
