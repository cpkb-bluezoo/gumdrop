/*
 * TlsConfigSupport.java
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

import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * Applies {@link TlsConfig} to a {@link Listener}. Kept out of {@code tls.*}
 * so {@link TlsConfig} can compile in the core bootstrap pass before
 * {@link Listener} exists.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
        if (tls.getEchConfigListFile() != null) {
            listener.setEchConfigListFile(tls.getEchConfigListFile());
        }
        if (tls.getEchPrivateKeyFile() != null) {
            listener.setEchPrivateKeyFile(tls.getEchPrivateKeyFile());
        }
        listener.setEchServerRequired(tls.isEchServerRequired());
    }

}
