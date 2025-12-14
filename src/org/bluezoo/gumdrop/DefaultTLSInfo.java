/*
 * DefaultTLSInfo.java
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Default implementation of TLSInfo backed by an SSLSession.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DefaultTLSInfo implements TLSInfo {

    private final SSLSession session;
    private final String alpnProtocol;
    private final long handshakeStartTime;

    /**
     * Creates a TLSInfo from an SSL engine.
     * 
     * @param engine the SSL engine
     * @param handshakeStartTime the time when the handshake started (from System.currentTimeMillis())
     */
    DefaultTLSInfo(SSLEngine engine, long handshakeStartTime) {
        this.session = engine.getSession();
        this.alpnProtocol = engine.getApplicationProtocol();
        this.handshakeStartTime = handshakeStartTime;
    }

    @Override
    public String getProtocol() {
        return session.getProtocol();
    }

    @Override
    public String getCipherSuite() {
        return session.getCipherSuite();
    }

    @Override
    public Certificate[] getPeerCertificates() {
        try {
            return session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    @Override
    public Certificate[] getLocalCertificates() {
        return session.getLocalCertificates();
    }

    @Override
    public boolean isSessionResumed() {
        // A session is resumed if it existed before the current handshake started.
        // We detect this by comparing the session creation time with the handshake start time.
        // If the session was created before the handshake started, it's a resumed session.
        byte[] sessionId = session.getId();
        if (sessionId == null || sessionId.length == 0) {
            return false;
        }
        return session.getCreationTime() < handshakeStartTime;
    }

    @Override
    public String getALPNProtocol() {
        if (alpnProtocol == null || alpnProtocol.isEmpty()) {
            return null;
        }
        return alpnProtocol;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TLS[");
        sb.append(getProtocol());
        sb.append(", ");
        sb.append(getCipherSuite());
        if (alpnProtocol != null && !alpnProtocol.isEmpty()) {
            sb.append(", ALPN=");
            sb.append(alpnProtocol);
        }
        sb.append("]");
        return sb.toString();
    }

}

