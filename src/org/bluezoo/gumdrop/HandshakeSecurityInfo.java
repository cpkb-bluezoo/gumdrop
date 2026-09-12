/*
 * HandshakeSecurityInfo.java
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
import java.security.cert.X509Certificate;
import java.util.List;

import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;

/**
 * {@link SecurityInfo} implementation backed by the in-tree
 * {@link TlsRecordEngine} -- the direct replacement for the former
 * JSSE-backed {@code JSSESecurityInfo}. Downstream consumers depend only
 * on the transport-agnostic {@link SecurityInfo} interface, not this
 * class, so nothing else needed to change for this substitution.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SecurityInfo
 */
final class HandshakeSecurityInfo implements SecurityInfo {

    private final TlsRecordEngine engine;
    private final HandshakeConfig config;
    private final long handshakeStartTime;
    private final long handshakeEndTime;

    /**
     * Creates a HandshakeSecurityInfo from a completed handshake.
     *
     * @param engine the record engine (handshake must be complete)
     * @param config the configuration the handshake ran with
     * @param handshakeStartTime time when the handshake started
     */
    HandshakeSecurityInfo(TlsRecordEngine engine, HandshakeConfig config, long handshakeStartTime) {
        this.engine = engine;
        this.config = config;
        this.handshakeStartTime = handshakeStartTime;
        this.handshakeEndTime = System.currentTimeMillis();
    }

    @Override
    public String getProtocol() {
        return "TLSv1.3";
    }

    @Override
    public String getCipherSuite() {
        CipherSuite suite = engine.getNegotiatedCipherSuite();
        return (suite != null) ? suite.name() : null;
    }

    @Override
    public int getKeySize() {
        CipherSuite suite = engine.getNegotiatedCipherSuite();
        if (suite == null) {
            return -1;
        }
        switch (suite) {
            case TLS_AES_128_GCM_SHA256:
                return 128;
            case TLS_AES_256_GCM_SHA384:
            case TLS_CHACHA20_POLY1305_SHA256:
                return 256;
            default:
                return -1;
        }
    }

    @Override
    public Certificate[] getPeerCertificates() {
        List<X509Certificate> chain = engine.getPeerCertificateChain();
        return (chain != null) ? chain.toArray(new Certificate[0]) : null;
    }

    @Override
    public Certificate[] getLocalCertificates() {
        ServerCredentials credentials = (config.getRole() == HandshakeRole.SERVER)
                ? config.getServerCredentials() : config.getClientCredentials();
        if (credentials == null) {
            return null;
        }
        List<X509Certificate> chain = credentials.getCertificateChain();
        return (chain != null) ? chain.toArray(new Certificate[0]) : null;
    }

    @Override
    public String getApplicationProtocol() {
        return engine.getNegotiatedApplicationProtocol();
    }

    @Override
    public long getHandshakeDurationMs() {
        if (handshakeStartTime <= 0) {
            return -1;
        }
        return handshakeEndTime - handshakeStartTime;
    }

    @Override
    public boolean isSessionResumed() {
        return engine.isResumed();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TLS[");
        sb.append(getProtocol());
        sb.append(", ");
        sb.append(getCipherSuite());
        String alpn = getApplicationProtocol();
        if (alpn != null) {
            sb.append(", ALPN=");
            sb.append(alpn);
        }
        sb.append("]");
        return sb.toString();
    }

}
