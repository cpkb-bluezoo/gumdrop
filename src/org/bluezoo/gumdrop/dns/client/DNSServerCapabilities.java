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
 * {@link DNSServerCapabilityCache} -- either seeded in advance for a
 * well-known public resolver, or learned at runtime via RFC 9462
 * Discovery of Designated Resolvers (DDR).
 *
 * <p>Each transport's port defaults to 0, meaning "let the transport
 * use its own well-known default" (RFC 7858 §3.1 / RFC 9250 §4.1.1:
 * 853 for DoT/DoQ; RFC 8484 §5.1: 443 for DoH) -- the same "port &lt;= 0
 * means use the default" convention {@link DNSClientTransport}
 * implementations already follow. DDR's {@code port} SvcParam (RFC
 * 9460 §7.3) overrides this when a resolver advertises a non-default
 * port for a given transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSServerCapabilityCache
 */
final class DNSServerCapabilities {

    /** No known support for any encrypted transport. */
    static final DNSServerCapabilities UNKNOWN =
            new DNSServerCapabilities(false, 0, false, 0, null, 0);

    private final boolean doqSupported;
    private final int doqPort;
    private final boolean dotSupported;
    private final int dotPort;
    private final String dohPath;
    private final int dohPort;

    private DNSServerCapabilities(boolean doqSupported, int doqPort,
                                  boolean dotSupported, int dotPort,
                                  String dohPath, int dohPort) {
        this.doqSupported = doqSupported;
        this.doqPort = doqPort;
        this.dotSupported = dotSupported;
        this.dotPort = dotPort;
        this.dohPath = dohPath;
        this.dohPort = dohPort;
    }

    /**
     * @param doqSupported true if the server is known to speak RFC 9250 DoQ
     * @param doqPort the DoQ port, or 0 for the transport's default (853)
     * @param dotSupported true if the server is known to speak RFC 7858 DoT
     * @param dotPort the DoT port, or 0 for the transport's default (853)
     * @param dohPath the RFC 8484 §4.1 URI template path, if the server
     *                is known to speak DoH, or null otherwise
     * @param dohPort the DoH port, or 0 for the transport's default (443)
     */
    static DNSServerCapabilities of(boolean doqSupported, int doqPort,
                                    boolean dotSupported, int dotPort,
                                    String dohPath, int dohPort) {
        return new DNSServerCapabilities(doqSupported, doqPort, dotSupported, dotPort,
                dohPath, dohPort);
    }

    boolean isDoqSupported() {
        return doqSupported;
    }

    /** The DoQ port, or 0 to use the transport's own default (853). */
    int getDoqPort() {
        return doqPort;
    }

    boolean isDotSupported() {
        return dotSupported;
    }

    /** The DoT port, or 0 to use the transport's own default (853). */
    int getDotPort() {
        return dotPort;
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

    /** The DoH port, or 0 to use the transport's own default (443). */
    int getDohPort() {
        return dohPort;
    }
}
