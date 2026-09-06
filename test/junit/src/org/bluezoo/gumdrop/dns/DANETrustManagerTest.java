/*
 * DANETrustManagerTest.java
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

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.junit.Test;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DANETrustManager}.
 * RFC 6698 (DANE), RFC 7671 (usage semantics).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DANETrustManagerTest {

    @Test
    public void testDaneEeMatchAcceptedWithoutDelegate() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-1");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_DANE_EE,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DANETrustManager tm = new DANETrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test
    public void testDaneTaMatchAcceptedWithoutDelegate() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-2");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_DANE_TA,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DANETrustManager tm = new DANETrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test
    public void testPkixEeMatchRequiresDelegateSuccess() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-3");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_PKIX_EE,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DANETrustManager tm = new DANETrustManager(
                new EmptyX509TrustManager(), tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testPkixEeMatchWithoutDelegateRejected() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-4");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_PKIX_EE,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        // Even though the TLSA record matches, PKIX-EE requires a
        // WebPKI delegate that was never supplied.
        DANETrustManager tm = new DANETrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testPkixTaMatchPropagatesDelegateFailure() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-5");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_PKIX_TA,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        X509TrustManager rejectingDelegate = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("not WebPKI trusted");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        DANETrustManager tm = new DANETrustManager(rejectingDelegate, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testNoMatchRejected() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-6");
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_DANE_EE,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, new byte[]{ 1, 2, 3 }));

        DANETrustManager tm = new DANETrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRejectsEmptyRecords() {
        new DANETrustManager(null, Collections.<DNSResourceRecord>emptyList());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRejectsNullRecords() {
        new DANETrustManager(null, null);
    }

    @Test
    public void testGetAcceptedIssuersFallsBackToEmptyWithoutDelegate() {
        List<DNSResourceRecord> tlsa = Collections.singletonList(
                DNSResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DANEVerifier.USAGE_DANE_EE,
                        DANEVerifier.SELECTOR_FULL_CERT,
                        DANEVerifier.MATCHING_TYPE_FULL, new byte[32]));
        DANETrustManager tm = new DANETrustManager(null, tlsa);
        assertEquals(0, tm.getAcceptedIssuers().length);
    }
}
