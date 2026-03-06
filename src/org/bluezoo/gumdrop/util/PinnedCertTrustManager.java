/*
 * PinnedCertTrustManager.java
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

package org.bluezoo.gumdrop.util;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * An {@link X509TrustManager} that delegates normal chain validation
 * to a base trust manager, then additionally checks the server's
 * leaf certificate against one or more pinned SHA-256 fingerprints.
 *
 * <p>If any of the supplied fingerprints match the server's leaf
 * certificate, the check succeeds. This allows zero-downtime
 * certificate rotation by pinning both the current and next
 * certificate simultaneously.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * // Pin to a specific certificate (with default JVM trust)
 * X509TrustManager tm = new PinnedCertTrustManager(
 *         "ab:cd:ef:01:23:...");
 *
 * // Pin with explicit delegate trust manager
 * X509TrustManager tm = new PinnedCertTrustManager(
 *         myDelegateTrustManager,
 *         "ab:cd:ef:01:23:...", "12:34:56:78:...");
 *
 * // Use with SSLContext
 * SSLContext ctx = SSLContext.getInstance("TLS");
 * ctx.init(null, new TrustManager[] { tm }, null);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see EmptyX509TrustManager
 */
public class PinnedCertTrustManager implements X509TrustManager {

    private final X509TrustManager delegate;
    private final Set<String> expectedFingerprints;

    /**
     * Creates a pinning trust manager using the JVM default trust
     * manager as the delegate.
     *
     * @param fingerprints one or more SHA-256 fingerprints
     *                     (colon-separated lowercase hex)
     */
    public PinnedCertTrustManager(String... fingerprints) {
        this(defaultTrustManager(), fingerprints);
    }

    /**
     * Creates a pinning trust manager with an explicit delegate.
     *
     * @param delegate the trust manager to delegate chain validation to
     * @param fingerprints one or more SHA-256 fingerprints
     *                     (colon-separated lowercase hex)
     */
    public PinnedCertTrustManager(X509TrustManager delegate,
                                  String... fingerprints) {
        this.delegate = delegate;
        this.expectedFingerprints =
                new HashSet<String>(Arrays.asList(fingerprints));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain,
                                   String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain,
                                   String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        if (chain.length == 0) {
            throw new CertificateException(
                    "Empty certificate chain");
        }
        String actual = computeFingerprint(chain[0]);
        if (!expectedFingerprints.contains(actual)) {
            throw new CertificateException(
                    "Server certificate fingerprint mismatch: "
                            + "expected one of "
                            + expectedFingerprints
                            + ", got " + actual);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    /**
     * Computes the SHA-256 fingerprint of a certificate.
     *
     * @param cert the certificate
     * @return the fingerprint as a colon-separated lowercase hex string
     * @throws CertificateException if the fingerprint cannot be computed
     */
    public static String computeFingerprint(X509Certificate cert)
            throws CertificateException {
        try {
            MessageDigest md =
                    MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb =
                    new StringBuilder(digest.length * 3 - 1);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x",
                        digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new CertificateException(
                    "Failed to compute certificate fingerprint",
                    e);
        }
    }

    private static X509TrustManager defaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            javax.net.ssl.TrustManager[] tms = tmf.getTrustManagers();
            for (javax.net.ssl.TrustManager tm : tms) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load default trust manager", e);
        }
        throw new RuntimeException("No X509TrustManager found");
    }

}
