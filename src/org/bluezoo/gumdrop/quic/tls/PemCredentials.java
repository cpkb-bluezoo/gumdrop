/*
 * PemCredentials.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

/**
 * Loads PEM-encoded certificate chain and private key files into an
 * Agent15 {@link TlsServerEngineFactory}, the pure-Java replacement for
 * the native path's {@code ssl_ctx_load_cert_chain}/
 * {@code ssl_ctx_load_priv_key} (BoringSSL reads PEM files directly;
 * Agent15 wants a {@link KeyStore}).
 *
 * <p>The private key must be in PKCS8 form (a
 * {@code -----BEGIN PRIVATE KEY-----} block, RSA or EC) -- the older
 * PKCS1 traditional format ({@code -----BEGIN RSA PRIVATE KEY-----})
 * is not supported; convert with
 * {@code openssl pkcs8 -topk8 -nocrypt} if needed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class PemCredentials {

    private static final String KEY_ALIAS = "gumdrop";

    private PemCredentials() {
    }

    /**
     * Loads a certificate chain and private key from PEM files and
     * builds a {@link TlsServerEngineFactory} from them.
     *
     * @param certFile the PEM certificate chain file
     * @param keyFile the PEM PKCS8 private key file
     * @return the engine factory
     * @throws IOException if either file cannot be read or parsed
     * @throws GeneralSecurityException if the key store cannot be built
     */
    public static TlsServerEngineFactory loadServerEngineFactory(Path certFile, Path keyFile)
            throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = loadCertificateChain(certFile);
        PrivateKey key = loadPrivateKey(keyFile);
        char[] password = KEY_ALIAS.toCharArray(); // in-memory KeyStore only, never persisted to disk

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(KEY_ALIAS, key, password, chain.toArray(new Certificate[0]));
        return new TlsServerEngineFactory(keyStore, KEY_ALIAS, password);
    }

    /**
     * Loads a certificate chain from a PEM file.
     *
     * @param certFile the PEM certificate chain file
     * @return the certificate chain, in file order
     * @throws IOException if the file cannot be read
     * @throws GeneralSecurityException if the certificates cannot be parsed
     */
    public static List<X509Certificate> loadCertificateChain(Path certFile)
            throws IOException, GeneralSecurityException {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        try (InputStream in = Files.newInputStream(certFile)) {
            // CertificateFactory reads PEM directly, and generateCertificates
            // handles a file containing more than one concatenated certificate.
            Collection<? extends Certificate> certs = certificateFactory.generateCertificates(in);
            for (Certificate cert : certs) {
                chain.add((X509Certificate) cert);
            }
        }
        if (chain.isEmpty()) {
            throw new IOException("No certificates found in " + certFile);
        }
        return chain;
    }

    /**
     * Loads a PEM CA certificate file as a trust manager, for verifying
     * peer certificates against a private/custom CA rather than the
     * platform default trust store.
     *
     * @param caFile the PEM CA certificate file (one or more concatenated certificates)
     * @return the trust manager
     * @throws IOException if the file cannot be read
     * @throws GeneralSecurityException if the certificates cannot be parsed
     *                                  or the trust manager cannot be built
     */
    public static X509TrustManager loadTrustManager(Path caFile) throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = loadCertificateChain(caFile);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int i = 0; i < chain.size(); i++) {
            trustStore.setCertificateEntry("ca" + i, chain.get(i));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available from " + caFile);
    }

    /**
     * Loads a PKCS8 PEM private key file.
     *
     * @param keyFile the PEM PKCS8 private key file
     * @return the private key
     * @throws IOException if the file cannot be read or does not contain
     *                     a PKCS8 private key block
     * @throws GeneralSecurityException if the key bytes cannot be parsed
     *                                  as an RSA or EC private key
     */
    public static PrivateKey loadPrivateKey(Path keyFile) throws IOException, GeneralSecurityException {
        String pem = new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII);
        byte[] der = decodePemBlock(pem, "PRIVATE KEY", keyFile);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (InvalidKeySpecException rsaFailure) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (InvalidKeySpecException ecFailure) {
                throw new InvalidKeyException(
                        "Unsupported private key format (expected PKCS8 RSA or EC): " + keyFile, ecFailure);
            }
        }
    }

    private static byte[] decodePemBlock(String pem, String label, Path source) throws IOException {
        String beginMarker = "-----BEGIN " + label + "-----";
        String endMarker = "-----END " + label + "-----";
        int begin = pem.indexOf(beginMarker);
        int end = begin < 0 ? -1 : pem.indexOf(endMarker, begin);
        if (begin < 0 || end < 0) {
            throw new IOException("No " + label + " block found in " + source);
        }
        String body = pem.substring(begin + beginMarker.length(), end);

        StringBuilder base64 = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isWhitespace(c)) {
                base64.append(c);
            }
        }
        return Base64.getDecoder().decode(base64.toString());
    }
}
