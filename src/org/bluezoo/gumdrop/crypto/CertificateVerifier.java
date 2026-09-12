/*
 * CertificateVerifier.java
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

package org.bluezoo.gumdrop.crypto;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate chain parsing and verification for the TLS 1.3 handshake
 * engine, over JCA's built-in X.509 stack ({@link CertificateFactory},
 * {@link X509TrustManager}) rather than a hand-rolled ASN.1 parser --
 * gumdrop's whole premise is JCA, and unlike some other language
 * ecosystems, JCA's X.509/PKIX support is mature and complete, so there
 * is nothing here worth reimplementing.
 *
 * <p>Trust is expressed as an {@link X509TrustManager}, not a bare
 * {@code Set<TrustAnchor>}: this is the same type gumdrop's own PEM/CA
 * loading ({@code PemCredentials#loadTrustManager}) and every other trust
 * configuration in the codebase already speaks (including an explicitly
 * permissive "accept anything" manager for insecure/test configurations,
 * which has no non-empty anchor set to hand a
 * {@code PKIXParameters}-based validator at all). Delegating the actual
 * path validation to the supplied {@link X509TrustManager} means a
 * caller can plug in the platform default trust store
 * ({@link TrustManagerFactory#getDefaultAlgorithm}), a private CA, or a
 * deliberately permissive manager, uniformly.
 *
 * <p>Revocation checking (OCSP/CRL) is deliberately not performed here --
 * out of scope for the handshake engine itself, matching hopf's own
 * explicit non-goal: the engine's job is validating that the presented
 * chain is a trusted, correctly-signed path for the presented name, not
 * real-world responder availability and network reachability policy. A
 * caller wanting revocation checking configures it into the supplied
 * {@link X509TrustManager} (e.g. via {@code PKIXRevocationChecker})
 * itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CertificateVerifier {

    private CertificateVerifier() {
    }

    /**
     * Parses a DER certificate chain as presented on the wire (leaf
     * first) into {@link X509Certificate} objects.
     *
     * @param derChain the DER-encoded certificates, leaf first
     * @return the parsed certificates, in the same order
     * @throws CertificateException if any certificate is malformed
     */
    public static List<X509Certificate> parseChain(List<byte[]> derChain) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> result = new ArrayList<X509Certificate>(derChain.size());
        for (int i = 0; i < derChain.size(); i++) {
            byte[] der = derChain.get(i);
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
            result.add(cert);
        }
        return result;
    }

    /**
     * Builds an {@link X509TrustManager} trusting exactly the given CA
     * certificates -- a convenience for tests and simple configurations
     * that have a certificate list rather than a {@link KeyStore} already
     * (mirrors {@code PemCredentials#loadTrustManager}'s own
     * key-store-from-certificates construction).
     *
     * @param certificates the trusted CA certificates
     * @return a trust manager trusting exactly these certificates
     * @throws GeneralSecurityException if the trust manager cannot be built
     */
    public static X509TrustManager trustManagerFromCertificates(List<X509Certificate> certificates)
            throws GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try {
            trustStore.load(null, null);
        } catch (java.io.IOException e) {
            // Never thrown for an empty in-memory key store.
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < certificates.size(); i++) {
            trustStore.setCertificateEntry("ca" + i, certificates.get(i));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    /**
     * Verifies a peer-presented certificate chain: delegates chain trust
     * to {@code trustManager.checkServerTrusted}, then (if a hostname is
     * given) checks the leaf certificate's subject alternative names
     * against it.
     *
     * @param chain the peer's certificate chain, leaf first
     * @param trustManager the configured trust manager
     * @param expectedHostname the hostname the peer should be identified
     *                         as, or null to skip hostname checking (for
     *                         client certificate verification, where
     *                         there is no hostname to check against)
     * @return the verification result
     */
    public static Result verifyChain(List<X509Certificate> chain, X509TrustManager trustManager,
            String expectedHostname) {
        if (chain.isEmpty()) {
            return Result.error("Empty certificate chain");
        }
        try {
            trustManager.checkServerTrusted(chain.toArray(new X509Certificate[0]), "UNKNOWN");
        } catch (CertificateException e) {
            return Result.error("Certificate chain not trusted: " + e.getMessage());
        }

        if (expectedHostname != null && !matchesHostname(chain.get(0), expectedHostname)) {
            return Result.error("Certificate does not match hostname " + expectedHostname);
        }
        return Result.ok();
    }

    /**
     * Checks a certificate's identity against an expected hostname
     * (case-insensitive, with a single leftmost-label wildcard permitted,
     * e.g. {@code *.example.com}). Chain trust validation alone does not
     * perform this check -- it establishes trust in the chain, not
     * identity of the presented name, so it is done here as a distinct
     * step.
     *
     * <p>Checks {@code dNSName} subject alternative names first (RFC 6125
     * section 6.4.4's preferred source), falling back to the certificate's
     * Subject Common Name only when the certificate has <em>no</em>
     * {@code dNSName} SAN entries at all -- a real, common case (this
     * project's own keytool-generated test certificates are typically
     * {@code CN=...} only, no SAN extension), not a deliberately
     * uncommon one. If {@code dNSName} SANs are present but none match,
     * that is a genuine mismatch -- the CN is not consulted as a
     * fallback in that case, matching RFC 6125's guidance not to trust
     * the CN once a SAN identity is declared.
     *
     * @param cert the certificate to check
     * @param hostname the expected hostname
     * @return true if the certificate identifies {@code hostname}
     */
    public static boolean matchesHostname(X509Certificate cert, String hostname) {
        String normalizedHost = hostname.toLowerCase(Locale.ROOT);
        Collection<List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (CertificateException e) {
            sans = null;
        }
        if (sans != null) {
            boolean hasDnsNameSan = false;
            for (List<?> san : sans) {
                Integer type = (Integer) san.get(0);
                if (type.intValue() != 2) {
                    // iPAddress (type 7) and other SAN forms are not
                    // needed for this milestone's QUIC/hostname-driven
                    // verification.
                    continue;
                }
                hasDnsNameSan = true;
                if (matchesDnsPattern((String) san.get(1), normalizedHost)) {
                    return true;
                }
            }
            if (hasDnsNameSan) {
                return false;
            }
        }
        String commonName = subjectCommonName(cert);
        return commonName != null && matchesDnsPattern(commonName, normalizedHost);
    }

    private static boolean matchesDnsPattern(String pattern, String normalizedHost) {
        String normalizedPattern = pattern.toLowerCase(Locale.ROOT);
        if (normalizedPattern.startsWith("*.")) {
            String suffix = normalizedPattern.substring(1);
            int firstDot = normalizedHost.indexOf('.');
            return firstDot >= 0 && normalizedHost.substring(firstDot).equals(suffix);
        }
        return normalizedPattern.equals(normalizedHost);
    }

    private static String subjectCommonName(X509Certificate cert) {
        try {
            javax.naming.ldap.LdapName subject =
                    new javax.naming.ldap.LdapName(cert.getSubjectX500Principal().getName());
            for (int i = subject.size() - 1; i >= 0; i--) {
                javax.naming.ldap.Rdn rdn = subject.getRdn(i);
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (javax.naming.InvalidNameException e) {
            return null;
        }
        return null;
    }

    /**
     * The outcome of a {@link #verifyChain} call: either ok, or a
     * human-readable reason it failed. A distinct result type rather than
     * a thrown exception, matching this engine's convention that
     * verification outcomes are ordinary values, not exceptions-as-control-flow
     * at the engine boundary.
     */
    public static final class Result {

        private final boolean ok;
        private final String error;

        private Result(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        private static Result ok() {
            return new Result(true, null);
        }

        private static Result error(String message) {
            return new Result(false, message);
        }

        /**
         * Returns whether the chain verified successfully.
         *
         * @return true if the chain is trusted (and, if a hostname was
         *         given, matches it)
         */
        public boolean isOk() {
            return ok;
        }

        /**
         * Returns the reason verification failed.
         *
         * @return a human-readable error message, or null if {@link #isOk}
         *         is true
         */
        public String getError() {
            return error;
        }

    }

}
