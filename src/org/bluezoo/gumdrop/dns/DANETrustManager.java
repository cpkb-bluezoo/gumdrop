/*
 * DANETrustManager.java
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

package org.bluezoo.gumdrop.dns;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * An {@link X509TrustManager} that authenticates a server's
 * certificate chain against one or more DNSSEC-validated TLSA
 * records, per RFC 6698 (DANE) and RFC 7671 (its operational
 * semantics).
 *
 * <p>For certificate usage DANE-TA (2) and DANE-EE (3), a TLSA match
 * is sufficient on its own -- RFC 7671 section 4.2: "PKIX validation
 * is not tested" for these usages, so no delegate is required. For
 * PKIX-TA (0) and PKIX-EE (1), a TLSA match narrows which certificate
 * is acceptable but does not replace WebPKI validation, so the
 * delegate's {@code checkServerTrusted} must also succeed.
 *
 * <p>Callers are responsible for only constructing this class when
 * the TLSA lookup itself came back DNSSEC-secure -- RFC 7672 section
 * 3.1.3 requires an insecure or indeterminate TLSA answer to be
 * ignored, as if no TLSA records existed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DANEVerifier
 * @see org.bluezoo.gumdrop.util.PinnedCertTrustManager
 */
public class DANETrustManager implements X509TrustManager {

    private final X509TrustManager delegate;
    private final List<DNSResourceRecord> tlsaRecords;

    /**
     * Creates a DANE trust manager.
     *
     * @param delegate the trust manager to use for PKIX-TA/PKIX-EE
     *                 usages, or null if only DANE-TA/DANE-EE records
     *                 are expected
     * @param tlsaRecords the DNSSEC-validated TLSA records to match
     *                    against; must not be empty
     */
    public DANETrustManager(X509TrustManager delegate,
                            List<DNSResourceRecord> tlsaRecords) {
        if (tlsaRecords == null || tlsaRecords.isEmpty()) {
            throw new IllegalArgumentException(
                    "No TLSA records supplied");
        }
        this.delegate = delegate;
        this.tlsaRecords = tlsaRecords;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain,
                                   String authType)
            throws CertificateException {
        if (delegate == null) {
            throw new CertificateException(
                    "No delegate trust manager configured for client "
                            + "certificate validation");
        }
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain,
                                   String authType)
            throws CertificateException {
        DNSResourceRecord match;
        try {
            match = DANEVerifier.findMatch(chain, tlsaRecords);
        } catch (CertificateEncodingException e) {
            throw new CertificateException(
                    "Failed to re-encode certificate for DANE matching",
                    e);
        }
        if (match == null) {
            throw new CertificateException(
                    "No DANE TLSA record matched the presented "
                            + "certificate chain");
        }
        int usage = match.getTLSACertUsage();
        if (usage == DANEVerifier.USAGE_PKIX_TA
                || usage == DANEVerifier.USAGE_PKIX_EE) {
            if (delegate == null) {
                throw new CertificateException(
                        "TLSA usage " + usage + " requires WebPKI "
                                + "validation, but no delegate trust "
                                + "manager was configured");
            }
            delegate.checkServerTrusted(chain, authType);
        }
        // DANE-TA (2) and DANE-EE (3): the TLSA match alone is
        // sufficient, per RFC 7671 section 4.2.
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate != null ? delegate.getAcceptedIssuers()
                : new X509Certificate[0];
    }

}
