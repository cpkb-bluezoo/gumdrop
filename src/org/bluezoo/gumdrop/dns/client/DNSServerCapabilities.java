/*
 * DNSServerCapabilities.java
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

package org.bluezoo.gumdrop.dns.client;

/**
 * A DNS server's known support for encrypted transports, as recorded by
 * {@link DNSServerCapabilityCache}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSServerCapabilityCache
 */
final class DNSServerCapabilities {

    /** No known support for any encrypted transport. */
    static final DNSServerCapabilities UNKNOWN =
            new DNSServerCapabilities(false, false, null);

    private final boolean doqSupported;
    private final boolean dotSupported;
    private final String dohPath;

    private DNSServerCapabilities(boolean doqSupported, boolean dotSupported,
                                  String dohPath) {
        this.doqSupported = doqSupported;
        this.dotSupported = dotSupported;
        this.dohPath = dohPath;
    }

    /**
     * @param doqSupported true if the server is known to speak RFC 9250 DoQ
     * @param dotSupported true if the server is known to speak RFC 7858 DoT
     * @param dohPath the RFC 8484 §4.1 URI template path, if the server
     *                is known to speak DoH, or null otherwise
     */
    static DNSServerCapabilities of(boolean doqSupported, boolean dotSupported,
                                    String dohPath) {
        return new DNSServerCapabilities(doqSupported, dotSupported, dohPath);
    }

    boolean isDoqSupported() {
        return doqSupported;
    }

    boolean isDotSupported() {
        return dotSupported;
    }

    boolean isDohSupported() {
        return dohPath != null;
    }

    /**
     * The RFC 8484 §4.1 URI template path to use for DoH, or null if
     * DoH isn't known to be supported.
     */
    String getDohPath() {
        return dohPath;
    }
}
