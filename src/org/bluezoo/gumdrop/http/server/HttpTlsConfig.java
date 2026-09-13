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

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.tls.ServerCredentials;

import java.nio.file.Path;

/**
 * Server TLS identity for HTTP listeners (TCP HTTPS and QUIC HTTP/3).
 *
 * <p>Use the same {@code HttpTlsConfig} on {@link HttpListener} and
 * {@link org.bluezoo.gumdrop.http.h3.Http3Listener}, or pass it to
 * {@link org.bluezoo.gumdrop.http.HttpServer.Builder#secureEndpoint(int, HttpTlsConfig)}
 * to wire both transports on one port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpListener.Builder#tls(HttpTlsConfig)
 * @see org.bluezoo.gumdrop.http.h3.Http3Listener.Builder#tls(HttpTlsConfig)
 */
public final class HttpTlsConfig {

    private final Path certFile;
    private final Path keyFile;
    private final Path keystoreFile;
    private final String keystorePass;
    private final String keystoreFormat;
    private final ServerCredentials serverCredentials;

    private HttpTlsConfig(Path certFile, Path keyFile,
                          Path keystoreFile, String keystorePass,
                          String keystoreFormat,
                          ServerCredentials serverCredentials) {
        this.certFile = certFile;
        this.keyFile = keyFile;
        this.keystoreFile = keystoreFile;
        this.keystorePass = keystorePass;
        this.keystoreFormat = keystoreFormat;
        this.serverCredentials = serverCredentials;
    }

    /**
     * TLS identity from PEM certificate chain and private key files.
     */
    public static HttpTlsConfig pem(Path certFile, Path keyFile) {
        if (certFile == null || keyFile == null) {
            throw new NullPointerException("certFile and keyFile are required");
        }
        return new HttpTlsConfig(certFile, keyFile, null, null, null, null);
    }

    /**
     * TLS identity from PEM certificate chain and private key paths.
     */
    public static HttpTlsConfig pem(String certFile, String keyFile) {
        return pem(Path.of(certFile), Path.of(keyFile));
    }

    /**
     * TLS identity from a PKCS#12 or JKS keystore (default format PKCS12).
     */
    public static HttpTlsConfig keystore(Path keystoreFile, String keystorePass) {
        return keystore(keystoreFile, keystorePass, "PKCS12");
    }

    /**
     * TLS identity from a keystore with an explicit format.
     */
    public static HttpTlsConfig keystore(Path keystoreFile, String keystorePass,
                                         String keystoreFormat) {
        if (keystoreFile == null || keystorePass == null) {
            throw new NullPointerException("keystoreFile and keystorePass are required");
        }
        return new HttpTlsConfig(null, null, keystoreFile, keystorePass,
                keystoreFormat, null);
    }

    /**
     * TLS identity from loaded {@link ServerCredentials}.
     */
    public static HttpTlsConfig credentials(ServerCredentials serverCredentials) {
        if (serverCredentials == null) {
            throw new NullPointerException("serverCredentials");
        }
        return new HttpTlsConfig(null, null, null, null, null, serverCredentials);
    }

    /**
     * Applies this configuration to a listener before {@link Listener#start()}.
     */
    public void applyTo(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        if (serverCredentials != null) {
            listener.setServerCredentials(serverCredentials);
        } else if (certFile != null && keyFile != null) {
            listener.setCertFile(certFile);
            listener.setKeyFile(keyFile);
        } else if (keystoreFile != null && keystorePass != null) {
            listener.setKeystoreFile(keystoreFile);
            listener.setKeystorePass(keystorePass);
            if (keystoreFormat != null) {
                listener.setKeystoreFormat(keystoreFormat);
            }
        } else {
            throw new IllegalStateException("incomplete TLS configuration");
        }
    }

}
