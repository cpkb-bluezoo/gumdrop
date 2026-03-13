/*
 * SPKIPinnedCertTrustManager.java
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
 * An {@link X509TrustManager} that verifies the server's leaf certificate
 * against pinned Subject Public Key Info (SPKI) SHA-256 fingerprints.
 *
 * <p>RFC 7858 section 4.2 (Strict usage profile) requires clients to
 * verify the hash of the server certificate's SubjectPublicKeyInfo
 * (SPKI) structure, not the full certificate. This allows certificate
 * renewal without changing the public key, and is consistent with
 * RFC 7469 (HTTP Public Key Pinning).
 *
 * <p>The fingerprint is computed as {@code SHA-256(cert.getPublicKey().getEncoded())},
 * where {@code getEncoded()} returns the DER-encoded SubjectPublicKeyInfo.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see PinnedCertTrustManager
 */
public class SPKIPinnedCertTrustManager implements X509TrustManager {

    private final X509TrustManager delegate;
    private final Set<String> expectedFingerprints;

    /**
     * Creates an SPKI-pinning trust manager using the JVM default trust
     * manager as the delegate.
     *
     * @param fingerprints one or more SPKI SHA-256 fingerprints
     *                     (colon-separated lowercase hex)
     */
    public SPKIPinnedCertTrustManager(String... fingerprints) {
        this(defaultTrustManager(), fingerprints);
    }

    /**
     * Creates an SPKI-pinning trust manager with an explicit delegate.
     *
     * @param delegate the trust manager to delegate chain validation to
     * @param fingerprints one or more SPKI SHA-256 fingerprints
     *                     (colon-separated lowercase hex)
     */
    public SPKIPinnedCertTrustManager(X509TrustManager delegate,
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

    /**
     * RFC 7858 section 4.2: after normal chain validation, verify that
     * the server's leaf certificate SPKI matches one of the pinned
     * fingerprints.
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain,
                                   String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        if (chain.length == 0) {
            throw new CertificateException(
                    "Empty certificate chain");
        }
        String actual = computeSPKIFingerprint(chain[0]);
        if (!expectedFingerprints.contains(actual)) {
            throw new CertificateException(
                    "SPKI fingerprint mismatch: expected one of "
                            + expectedFingerprints
                            + ", got " + actual);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    /**
     * Computes the SHA-256 fingerprint of a certificate's
     * SubjectPublicKeyInfo (SPKI).
     * RFC 7858 section 4.2, RFC 7469 section 2.4: the pin value is
     * the SHA-256 hash of the DER-encoded SubjectPublicKeyInfo.
     *
     * @param cert the certificate
     * @return the SPKI fingerprint as a colon-separated lowercase hex string
     * @throws CertificateException if the fingerprint cannot be computed
     */
    public static String computeSPKIFingerprint(X509Certificate cert)
            throws CertificateException {
        try {
            // getPublicKey().getEncoded() returns the DER-encoded
            // SubjectPublicKeyInfo, which is exactly what RFC 7858
            // section 4.2 requires.
            byte[] spki = cert.getPublicKey().getEncoded();
            MessageDigest md =
                    MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(spki);
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
                    "Failed to compute SPKI fingerprint", e);
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
