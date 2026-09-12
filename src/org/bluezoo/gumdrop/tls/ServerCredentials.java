/*
 * ServerCredentials.java
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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * A server's identity for the TLS 1.3 handshake: a certificate chain
 * (leaf first) and the private key matching the leaf certificate.
 * Replaces {@code tech.kwik.agent15.engine.TlsServerEngineFactory}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ServerCredentials {

    private final List<X509Certificate> certificateChain;
    private final PrivateKey privateKey;

    /**
     * Creates server credentials.
     *
     * @param certificateChain the certificate chain, leaf first
     * @param privateKey the private key matching the leaf certificate
     */
    public ServerCredentials(List<X509Certificate> certificateChain, PrivateKey privateKey) {
        this.certificateChain = certificateChain;
        this.privateKey = privateKey;
    }

    /**
     * Returns the certificate chain, leaf first.
     *
     * @return the certificate chain
     */
    public List<X509Certificate> getCertificateChain() {
        return certificateChain;
    }

    /**
     * Returns the private key matching the leaf certificate.
     *
     * @return the private key
     */
    public PrivateKey getPrivateKey() {
        return privateKey;
    }

}
