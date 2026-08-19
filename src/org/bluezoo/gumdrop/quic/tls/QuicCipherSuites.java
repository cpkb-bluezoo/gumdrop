/*
 * QuicCipherSuites.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.kwik.agent15.TlsConstants;

/**
 * Resolves {@code QuicTransportFactory#setCipherSuites} against the
 * cipher suites gumdrop's own QUIC AEAD layer ({@code
 * org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm}) actually
 * implements -- shared by {@link QuicTlsClientEngine} and {@link
 * QuicTlsServerEngine}, since both need the identical filtering.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QuicCipherSuites {

    private static final Logger LOGGER = Logger.getLogger(QuicCipherSuites.class.getName());

    /**
     * Every cipher suite {@code QuicAeadAlgorithm} actually implements,
     * in gumdrop's own preference order. Agent15 additionally knows
     * {@code TLS_AES_128_CCM_SHA256}/{@code TLS_AES_128_CCM_8_SHA256}
     * (RFC 9001 section 5.3 permits CCM for constrained implementations),
     * which gumdrop's AEAD layer has no algorithm for -- never offered,
     * regardless of configuration.
     */
    static final List<TlsConstants.CipherSuite> DEFAULT = Collections.unmodifiableList(Arrays.asList(
            TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256,
            TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384,
            TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256));

    private QuicCipherSuites() {
    }

    /**
     * Resolves a colon-separated {@code cipherSuites} configuration
     * string against {@link #DEFAULT}, preserving the configured order
     * and dropping duplicates. Names Agent15 doesn't recognise at all,
     * or recognises but gumdrop has no AEAD implementation for (CCM),
     * are silently skipped. Falls back to {@link #DEFAULT} (with a
     * logged warning) if nothing configured resolves to anything usable;
     * falls back to it silently if nothing was configured at all.
     *
     * @param cipherSuites the raw {@code QuicTransportFactory
     *                     #getCipherSuites()} value, or null
     * @return the cipher suites to offer/accept, in order, never empty
     */
    static List<TlsConstants.CipherSuite> resolve(String cipherSuites) {
        if (cipherSuites == null || cipherSuites.isEmpty()) {
            return DEFAULT;
        }
        List<TlsConstants.CipherSuite> resolved = new ArrayList<TlsConstants.CipherSuite>();
        for (String name : cipherSuites.split(":")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            TlsConstants.CipherSuite suite;
            try {
                suite = TlsConstants.CipherSuite.valueOf(name);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (DEFAULT.contains(suite) && !resolved.contains(suite)) {
                resolved.add(suite);
            }
        }
        if (resolved.isEmpty()) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("None of the configured cipher suite(s) \"" + cipherSuites
                        + "\" are implemented by the QUIC transport's AEAD layer "
                        + "(only AES-128-GCM, AES-256-GCM, and ChaCha20-Poly1305 are "
                        + "implemented; Agent15's CCM suites have no backing implementation "
                        + "here); falling back to the default list.");
            }
            return DEFAULT;
        }
        return resolved;
    }
}
