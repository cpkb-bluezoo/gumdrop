/*
 * TlsConfigSupport.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * Applies {@link TlsConfig} to a {@link Listener}. Kept out of {@code tls.*}
 * so {@link TlsConfig} can compile in the core bootstrap pass before
 * {@link Listener} exists.
 */
public final class TlsConfigSupport {

    private TlsConfigSupport() {
    }

    public static void apply(TlsConfig tls, Listener listener) {
        if (tls == null) {
            throw new NullPointerException("tls");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        if (tls.getServerCredentials() != null) {
            listener.setServerCredentials(tls.getServerCredentials());
        } else if (tls.getCertFile() != null && tls.getKeyFile() != null) {
            listener.setCertFile(tls.getCertFile());
            listener.setKeyFile(tls.getKeyFile());
        } else if (tls.getKeystoreFile() != null && tls.getKeystorePass() != null) {
            listener.setKeystoreFile(tls.getKeystoreFile());
            listener.setKeystorePass(tls.getKeystorePass());
            if (tls.getKeystoreFormat() != null) {
                listener.setKeystoreFormat(tls.getKeystoreFormat());
            }
        } else {
            throw new IllegalStateException("incomplete TLS configuration");
        }
        if (tls.getTrustManager() != null) {
            listener.setTrustManager(tls.getTrustManager());
        } else if (!tls.isVerifyPeer()) {
            listener.setTrustManager(new EmptyX509TrustManager());
        }
    }

}
