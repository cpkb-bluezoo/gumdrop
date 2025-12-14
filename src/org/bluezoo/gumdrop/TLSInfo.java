/*
 * TLSInfo.java
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

package org.bluezoo.gumdrop;

import java.security.cert.Certificate;

/**
 * Provides information about a TLS connection.
 * 
 * <p>This interface exposes details about the negotiated TLS session,
 * including the protocol version, cipher suite, and certificates.
 * It is available via {@link ConnectionInfo#getTLSInfo()} when the
 * connection is secure.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectionInfo
 */
public interface TLSInfo {

    /**
     * Returns the TLS protocol version.
     * 
     * @return the protocol name, e.g., "TLSv1.2" or "TLSv1.3"
     */
    String getProtocol();

    /**
     * Returns the negotiated cipher suite.
     * 
     * @return the cipher suite name, e.g., "TLS_AES_256_GCM_SHA384"
     */
    String getCipherSuite();

    /**
     * Returns the peer's certificate chain.
     * 
     * <p>For client connections, this is the server's certificate chain.
     * The first certificate is the peer's own certificate, followed by
     * any certificate authority certificates.
     * 
     * @return the peer's certificates, or null if not available
     */
    Certificate[] getPeerCertificates();

    /**
     * Returns the local certificate chain sent to the peer.
     * 
     * <p>This is only available if client certificate authentication
     * was used. For most client connections, this will be null.
     * 
     * @return the local certificates, or null if not sent
     */
    Certificate[] getLocalCertificates();

    /**
     * Returns whether TLS session resumption was used.
     * 
     * <p>Session resumption allows faster connection establishment
     * by reusing cryptographic parameters from a previous session.
     * 
     * @return true if the session was resumed
     */
    boolean isSessionResumed();

    /**
     * Returns the negotiated ALPN (Application-Layer Protocol Negotiation) protocol.
     * 
     * <p>ALPN allows the client and server to negotiate which application
     * protocol to use over the TLS connection. Common values include:
     * <ul>
     * <li>"h2" - HTTP/2</li>
     * <li>"http/1.1" - HTTP/1.1</li>
     * </ul>
     * 
     * @return the negotiated protocol, or null if ALPN was not used
     */
    String getALPNProtocol();

}

