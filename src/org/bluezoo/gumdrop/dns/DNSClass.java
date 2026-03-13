/*
 * DNSClass.java
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

package org.bluezoo.gumdrop.dns;

/**
 * DNS record classes.
 * RFC 1035 section 3.2.4 defines CLASS values.
 * RFC 1035 section 3.2.5 defines QCLASS values (superset of CLASS).
 *
 * <p>In practice, only IN (Internet) is commonly used.
 * CS (2, CSNET) is omitted as it is explicitly obsolete in RFC 1035.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DNSClass {

    /** Internet class. RFC 1035 section 3.2.4. */
    IN(1),

    /** CHAOS class. RFC 1035 section 3.2.4. */
    CH(3),

    /** Hesiod class. RFC 1035 section 3.2.4. */
    HS(4),

    /** Any class (QCLASS only). RFC 1035 section 3.2.5. */
    ANY(255);

    private final int value;

    DNSClass(int value) {
        this.value = value;
    }

    /**
     * Returns the numeric value of this class.
     *
     * @return the class value
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the DNSClass for the given numeric value.
     *
     * @param value the class value
     * @return the DNSClass, or null if unknown
     */
    public static DNSClass fromValue(int value) {
        for (DNSClass cls : values()) {
            if (cls.value == value) {
                return cls;
            }
        }
        return null;
    }

}
