/*
 * HttpTlsConfig.java
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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.TlsConfigSupport;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsConfig;

import java.nio.file.Path;

/**
 * @deprecated use {@link TlsConfig} for all secure listeners (HTTP, SMTP,
 * DNS-over-TLS, …).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
*/
@Deprecated
public final class HttpTlsConfig {

    private final TlsConfig config;

    private HttpTlsConfig(TlsConfig config) {
        this.config = config;
    }

    public TlsConfig unwrap() {
        return config;
    }

    /**
     * @deprecated use {@link TlsConfig#pem(Path, Path)}.
     */
    @Deprecated
    public static HttpTlsConfig pem(Path certFile, Path keyFile) {
        return new HttpTlsConfig(TlsConfig.pem(certFile, keyFile));
    }

    /**
     * @deprecated use {@link TlsConfig#keystore(Path, String)}.
     */
    @Deprecated
    public static HttpTlsConfig keystore(Path keystoreFile, String keystorePass) {
        return new HttpTlsConfig(TlsConfig.keystore(keystoreFile, keystorePass));
    }

    /**
     * @deprecated use {@link TlsConfig#keystore(Path, String, String)}.
     */
    @Deprecated
    public static HttpTlsConfig keystore(Path keystoreFile, String keystorePass,
                                         KeystoreFormat keystoreFormat) {
        return new HttpTlsConfig(
                TlsConfig.keystore(keystoreFile, keystorePass, keystoreFormat));
    }

    /**
     * @deprecated use {@link TlsConfig#credentials(ServerCredentials)}.
     */
    @Deprecated
    public static HttpTlsConfig credentials(ServerCredentials serverCredentials) {
        return new HttpTlsConfig(TlsConfig.credentials(serverCredentials));
    }

    /**
     * @deprecated use {@link TlsConfigSupport#apply(TlsConfig, Listener)}.
     */
    @Deprecated
    public void applyTo(Listener listener) {
        TlsConfigSupport.apply(config, listener);
    }

}
