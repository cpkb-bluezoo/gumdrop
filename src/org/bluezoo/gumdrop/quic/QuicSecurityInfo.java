/*
 * QuicSecurityInfo.java
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

package org.bluezoo.gumdrop.quic;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import org.bluezoo.gumdrop.SecurityInfo;

/**
 * SecurityInfo implementation backed by quiche/BoringSSL.
 *
 * <p>Reads security metadata from the native quiche connection after
 * the QUIC handshake completes. QUIC always uses TLS 1.3, so the
 * protocol is always "QUICv1".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SecurityInfo
 */
final class QuicSecurityInfo implements SecurityInfo {

    private final String cipherSuite;
    private final String applicationProtocol;
    private final Certificate[] peerCertificates;
    private final long handshakeDurationMs;

    /**
     * Creates a QuicSecurityInfo by reading state from a quiche connection.
     *
     * @param connPtr the native quiche_conn pointer
     * @param sslPtr the native BoringSSL SSL pointer
     * @param handshakeStartTime the time when the handshake started
     */
    QuicSecurityInfo(long connPtr, long sslPtr, long handshakeStartTime) {
        this.cipherSuite = QuicheNative.ssl_get_cipher_name(sslPtr);
        this.applicationProtocol =
                QuicheNative.quiche_conn_application_proto(connPtr);
        this.peerCertificates = parsePeerCerts(connPtr);
        this.handshakeDurationMs =
                System.currentTimeMillis() - handshakeStartTime;
    }

    @Override
    public String getProtocol() {
        return "QUICv1";
    }

    @Override
    public String getCipherSuite() {
        return cipherSuite;
    }

    @Override
    public int getKeySize() {
        if (cipherSuite == null) {
            return -1;
        }
        if (cipherSuite.contains("256")) {
            return 256;
        }
        if (cipherSuite.contains("128")) {
            return 128;
        }
        return -1;
    }

    @Override
    public Certificate[] getPeerCertificates() {
        return peerCertificates;
    }

    @Override
    public Certificate[] getLocalCertificates() {
        return null;
    }

    @Override
    public String getApplicationProtocol() {
        return applicationProtocol;
    }

    @Override
    public long getHandshakeDurationMs() {
        return handshakeDurationMs;
    }

    @Override
    public boolean isSessionResumed() {
        // QUIC uses TLS 1.3 session tickets rather than traditional
        // session resumption. This is not directly observable via quiche.
        return false;
    }

    private static Certificate[] parsePeerCerts(long connPtr) {
        byte[] derBytes = QuicheNative.quiche_conn_peer_cert(connPtr);
        if (derBytes == null || derBytes.length == 0) {
            return null;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream in = new ByteArrayInputStream(derBytes);
            Certificate cert = cf.generateCertificate(in);
            return new Certificate[] { cert };
        } catch (Exception e) {
            return null;
        }
    }
}
