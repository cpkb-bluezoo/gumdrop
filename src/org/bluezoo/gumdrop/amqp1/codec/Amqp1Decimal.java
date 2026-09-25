/*
 * Amqp1Decimal.java
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

import java.util.Arrays;

/**
 * The raw IEEE 754-2008 bit pattern of an AMQP 1.0 {@code decimal32},
 * {@code decimal64} or {@code decimal128} value. The codec carries these
 * through unchanged so they survive a decode/encode round trip; it does
 * not interpret them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Decimal {

    private final byte[] bits;

    /**
     * @param bits 4, 8 or 16 bytes, big-endian
     */
    public Amqp1Decimal(byte[] bits) {
        if (bits.length != 4 && bits.length != 8 && bits.length != 16) {
            throw new IllegalArgumentException("decimal must be 4, 8 or 16 bytes");
        }
        this.bits = bits.clone();
    }

    /** Returns a copy of the bit pattern. */
    public byte[] getBits() {
        return bits.clone();
    }

    public int getWidth() {
        return bits.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amqp1Decimal)) {
            return false;
        }
        return Arrays.equals(bits, ((Amqp1Decimal) o).bits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits);
    }
}
