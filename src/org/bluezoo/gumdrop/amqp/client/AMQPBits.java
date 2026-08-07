/*
 * AMQPBits.java
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

package org.bluezoo.gumdrop.amqp.client;

/**
 * AMQP 0-9-1 §4.2.5.3 — consecutive {@code bit} method arguments are
 * packed together into as few octets as possible, first-declared bit in
 * the least-significant position.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AMQPBits {

    private AMQPBits() {
    }

    /** Packs up to 8 booleans (in declaration order) into one octet. */
    static byte pack(boolean... bits) {
        if (bits.length > 8) {
            throw new IllegalArgumentException("At most 8 bits fit in one octet");
        }
        int b = 0;
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                b |= (1 << i);
            }
        }
        return (byte) b;
    }

    /** Reads bit {@code index} (0 = least significant) from a packed octet. */
    static boolean unpack(byte packed, int index) {
        return (packed & (1 << index)) != 0;
    }
}
