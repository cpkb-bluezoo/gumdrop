/*
 * Amqp1Described.java
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
 * An AMQP 1.0 described value: a descriptor (a {@code symbol} or
 * {@code ulong}, decoded as {@link Amqp1Symbol} or {@link Long}) followed
 * by the described value itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Described {

    private final Object descriptor;
    private final Object value;

    public Amqp1Described(Object descriptor, Object value) {
        this.descriptor = descriptor;
        this.value = value;
    }

    public Object getDescriptor() {
        return descriptor;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amqp1Described)) {
            return false;
        }
        Amqp1Described other = (Amqp1Described) o;
        return java.util.Objects.equals(descriptor, other.descriptor)
                && java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(descriptor, value);
    }

    @Override
    public String toString() {
        return "described(" + descriptor + ", " + value + ")";
    }
}
