/*
 * JSSESecurityInfo.java
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
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * SecurityInfo implementation backed by a JSSE SSLEngine session.
 *
 * <p>Used for both TCP (TLS) and UDP (DTLS) endpoints where security
 * is provided by the standard Java JSSE framework.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SecurityInfo
 */
final class JSSESecurityInfo implements SecurityInfo {

    private static final Map<String,Integer> KNOWN_KEY_SIZES =
            new HashMap<String,Integer>();

    static {
        KNOWN_KEY_SIZES.put("3DES", Integer.valueOf(168));
        KNOWN_KEY_SIZES.put("CHACHA20", Integer.valueOf(256));
        KNOWN_KEY_SIZES.put("IDEA", Integer.valueOf(128));
    }

    private final SSLSession session;
    private final String alpnProtocol;
    private final long handshakeStartTime;
    private final long handshakeEndTime;

    /**
     * Creates a JSSESecurityInfo from an SSL engine.
     *
     * @param engine the SSL engine (handshake must be complete)
     * @param handshakeStartTime time when the handshake started
     */
    JSSESecurityInfo(SSLEngine engine, long handshakeStartTime) {
        this.session = engine.getSession();
        this.alpnProtocol = engine.getApplicationProtocol();
        this.handshakeStartTime = handshakeStartTime;
        this.handshakeEndTime = System.currentTimeMillis();
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
    public int getKeySize() {
        String cipher = getCipherSuite();
        if (cipher == null) {
            return -1;
        }
        int compStart = 0;
        int len = cipher.length();
        while (compStart <= len) {
            int compEnd = cipher.indexOf('_', compStart);
            if (compEnd < 0) {
                compEnd = len;
            }
            String comp = cipher.substring(compStart, compEnd);
            compStart = compEnd + 1;

            try {
                return Integer.parseInt(comp);
            } catch (NumberFormatException e) {
                // not a number, try known names
            }
            Integer known = KNOWN_KEY_SIZES.get(comp);
            if (known != null) {
                return known.intValue();
            }
        }
        return -1;
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
    public String getApplicationProtocol() {
        if (alpnProtocol == null || alpnProtocol.isEmpty()) {
            return null;
        }
        return alpnProtocol;
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
        // SSLSession does not expose a direct "isResumed" flag.
        // A session is considered resumed if the session ID was reused,
        // indicated by the creation time being earlier than the handshake.
        long creationTime = session.getCreationTime();
        return creationTime < handshakeStartTime;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("JSSE[");
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
