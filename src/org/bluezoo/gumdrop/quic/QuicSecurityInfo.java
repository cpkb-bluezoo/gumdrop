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

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import tech.kwik.agent15.TlsConstants;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine;

/**
 * {@link SecurityInfo} backed by the QUIC connection's negotiated TLS 1.3
 * state. QUIC always uses TLS 1.3, so the protocol is always "QUICv1".
 *
 * <p>Not yet available: although ALPN itself is negotiated (it selects
 * "h3" for HTTP/3), the negotiated value isn't surfaced here, so {@link
 * #getApplicationProtocol} always returns {@code null}; the server side
 * has no client certificate chain accessor (mutual TLS is not
 * exercised), so {@link #getPeerCertificates} is only ever populated on
 * the client side.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SecurityInfo
 */
final class QuicSecurityInfo implements SecurityInfo {

    private final String cipherSuite;
    private final Certificate[] peerCertificates;
    private final long handshakeDurationMs;
    private final boolean earlyDataAccepted;

    /**
     * Creates a QuicSecurityInfo from an established TLS engine's state.
     *
     * @param tlsEngine the connection's TLS engine, once its handshake has finished
     * @param isServer true if this endpoint is the server
     * @param handshakeStartTime the time the handshake started, for {@link #getHandshakeDurationMs}
     * @param earlyDataAccepted whether 0-RTT was accepted on this connection
     *                          (RFC 9001 section 4.6.1); always false if
     *                          0-RTT was never attempted at all
     */
    QuicSecurityInfo(QuicTlsEngine tlsEngine, boolean isServer, long handshakeStartTime, boolean earlyDataAccepted) {
        TlsConstants.CipherSuite selected = isServer
                ? ((QuicTlsServerEngine) tlsEngine).getSelectedCipher()
                : ((QuicTlsClientEngine) tlsEngine).getSelectedCipher();
        this.cipherSuite = selected != null ? selected.toString() : null;
        this.peerCertificates = isServer ? null : parsePeerCerts((QuicTlsClientEngine) tlsEngine);
        this.handshakeDurationMs = System.currentTimeMillis() - handshakeStartTime;
        this.earlyDataAccepted = earlyDataAccepted;
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
        return null;
    }

    @Override
    public long getHandshakeDurationMs() {
        return handshakeDurationMs;
    }

    @Override
    public boolean isSessionResumed() {
        // Agent15 doesn't expose a "PSK resumption succeeded" signal
        // independent of 0-RTT acceptance, so there is no accurate value
        // to report here short of guessing; always false.
        return false;
    }

    @Override
    public boolean isEarlyDataAccepted() {
        return earlyDataAccepted;
    }

    private static Certificate[] parsePeerCerts(QuicTlsClientEngine clientEngine) {
        List<X509Certificate> chain = clientEngine.getServerCertificateChain();
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        return chain.toArray(new Certificate[0]);
    }
}
