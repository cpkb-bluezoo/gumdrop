/*
 * Amqp1Symbol.java
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
 * An AMQP 1.0 {@code symbol}: a string of ASCII characters used for
 * names and identifiers. Distinct from {@link String} (an AMQP
 * {@code string}, UTF-8) so that a decoded value keeps its wire type
 * and re-encodes identically.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Symbol implements Comparable<Amqp1Symbol> {

    private final String value;

    public Amqp1Symbol(String value) {
        if (value == null) {
            throw new IllegalArgumentException("symbol must not be null");
        }
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amqp1Symbol)) {
            return false;
        }
        return value.equals(((Amqp1Symbol) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public int compareTo(Amqp1Symbol other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
