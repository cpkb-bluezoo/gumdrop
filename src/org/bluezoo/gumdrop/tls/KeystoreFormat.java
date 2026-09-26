/*
 * KeystoreFormat.java
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

package org.bluezoo.gumdrop.tls;

/**
 * The format of a keystore or truststore file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum KeystoreFormat {

    /** PKCS #12, the default. */
    PKCS12,

    /** The Java keystore format. */
    JKS,

    /** The Java cryptography extension keystore format. */
    JCEKS;

    /**
     * Returns the JCA keystore type name for this format.
     *
     * @return the name to pass to {@link java.security.KeyStore#getInstance(String)}
     */
    public String keystoreType() {
        return name();
    }

    /**
     * Reads a format from configuration text, ignoring case.
     *
     * @param text the format name, such as {@code pkcs12}
     * @return the format
     * @throws IllegalArgumentException if it names no known format
     */
    public static KeystoreFormat parse(String text) {
        return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
