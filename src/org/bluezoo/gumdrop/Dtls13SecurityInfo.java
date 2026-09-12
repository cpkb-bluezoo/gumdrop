/*
 * Dtls13SecurityInfo.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13RecordEngine;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;

final class Dtls13SecurityInfo implements SecurityInfo {

    private final Dtls13RecordEngine engine;
    private final Dtls13HandshakeConfig config;
    private final long handshakeStartTime;
    private final long handshakeEndTime;

    Dtls13SecurityInfo(Dtls13RecordEngine engine, Dtls13HandshakeConfig config, long handshakeStartTime) {
        this.engine = engine;
        this.config = config;
        this.handshakeStartTime = handshakeStartTime;
        this.handshakeEndTime = System.currentTimeMillis();
    }

    @Override
    public String getProtocol() {
        return "DTLSv1.3";
    }

    @Override
    public String getCipherSuite() {
        CipherSuite suite = engine.getNegotiatedCipherSuite();
        return (suite != null) ? suite.name() : null;
    }

    @Override
    public int getKeySize() {
        CipherSuite suite = engine.getNegotiatedCipherSuite();
        return (suite != null) ? suite.getAeadKeyLength() * 8 : -1;
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
        sb.append("DTLS[");
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
