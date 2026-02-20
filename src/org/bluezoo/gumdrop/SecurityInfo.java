/*
 * SecurityInfo.java
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

package org.bluezoo.gumdrop;

import java.security.cert.Certificate;

/**
 * Unified security metadata for any transport.
 *
 * <p>This interface provides a transport-agnostic view of the negotiated
 * security parameters, supporting all secure transports:
 * <ul>
 * <li>TCP with TLS (backed by JSSE SSLSession)</li>
 * <li>UDP with DTLS (backed by JSSE SSLSession)</li>
 * <li>QUIC (backed by quiche/BoringSSL via JNI)</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 * <li>JSSESecurityInfo -- wraps an SSLEngine's session (TCP TLS, UDP DTLS)</li>
 * <li>QuicSecurityInfo -- reads security state from quiche via JNI</li>
 * <li>{@link NullSecurityInfo} -- singleton for plaintext endpoints</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint#getSecurityInfo()
 */
public interface SecurityInfo {

    /**
     * Returns the security protocol version.
     *
     * <p>Examples: "TLSv1.2", "TLSv1.3", "DTLSv1.2", "QUICv1".
     * Returns null for plaintext (NullSecurityInfo).
     *
     * @return the protocol name, or null if not secure
     */
    String getProtocol();

    /**
     * Returns the negotiated cipher suite.
     *
     * <p>Uses the standard IANA name, e.g., "TLS_AES_256_GCM_SHA384".
     *
     * @return the cipher suite name, or null if not secure
     */
    String getCipherSuite();

    /**
     * Returns the key size of the negotiated cipher in bits.
     *
     * @return the key size, or -1 if not available
     */
    int getKeySize();

    /**
     * Returns the peer's certificate chain.
     *
     * <p>The first certificate is the peer's own certificate, followed
     * by any intermediate CA certificates.
     *
     * @return the peer certificates, or null if not available
     */
    Certificate[] getPeerCertificates();

    /**
     * Returns the local certificate chain sent to the peer.
     *
     * @return the local certificates, or null if not sent
     */
    Certificate[] getLocalCertificates();

    /**
     * Returns the negotiated application-layer protocol (ALPN).
     *
     * <p>Common values include "h2" (HTTP/2), "h3" (HTTP/3),
     * "http/1.1", and "smtp".
     *
     * @return the ALPN protocol, or null if not negotiated
     */
    String getApplicationProtocol();

    /**
     * Returns the duration of the security handshake in milliseconds.
     *
     * @return handshake duration, or -1 if not available
     */
    long getHandshakeDurationMs();

    /**
     * Returns whether the security session was resumed from a previous
     * connection.
     *
     * <p>Session resumption allows faster connection establishment by
     * reusing cryptographic parameters from a previous session.
     * For JSSE (TLS/DTLS), this delegates to the SSLSession.
     * For QUIC, TLS 1.3 uses session tickets rather than traditional
     * resumption, so this always returns false.
     * For plaintext endpoints, this returns false.
     *
     * @return true if the session was resumed
     */
    boolean isSessionResumed();

}
