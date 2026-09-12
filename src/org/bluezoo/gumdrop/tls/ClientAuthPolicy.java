/*
 * ClientAuthPolicy.java
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
 * Client-certificate authentication policy (server role) -- RFC 8446
 * section 4.3.2's {@code CertificateRequest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.3.2">RFC 8446 section 4.3.2</a>
 */
public enum ClientAuthPolicy {

    /**
     * Never send {@code CertificateRequest} -- the default, matching this
     * engine's behavior before client certificate authentication existed
     * at all.
     */
    NONE,

    /**
     * Send {@code CertificateRequest}; the handshake proceeds regardless
     * of what the client presents -- no certificate, or one that does not
     * verify against {@link HandshakeConfig#getClientTrustManager}.
     * {@link HandshakeEngine#getPeerCertificateChain} still reflects
     * whatever chain the client sent, even an unverified one, so the
     * caller can inspect and act on it itself.
     */
    REQUEST,

    /**
     * Send {@code CertificateRequest}; fail the handshake unless the
     * client presents a certificate chain that verifies against
     * {@link HandshakeConfig#getClientTrustManager}.
     */
    REQUIRE

}
