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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.junit.Test;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DaneTrustManager}.
 * RFC 6698 (DANE), RFC 7671 (usage semantics).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DANETrustManagerTest {

    @Test
    public void testDaneEeMatchAcceptedWithoutDelegate() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-1");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_DANE_EE,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DaneTrustManager tm = new DaneTrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test
    public void testDaneTaMatchAcceptedWithoutDelegate() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-2");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_DANE_TA,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DaneTrustManager tm = new DaneTrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test
    public void testPkixEeMatchRequiresDelegateSuccess() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-3");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_PKIX_EE,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        DaneTrustManager tm = new DaneTrustManager(
                new EmptyX509TrustManager(), tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testPkixEeMatchWithoutDelegateRejected() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-4");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_PKIX_EE,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

        // Even though the TLSA record matches, PKIX-EE requires a
        // WebPKI delegate that was never supplied.
        DaneTrustManager tm = new DaneTrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testPkixTaMatchPropagatesDelegateFailure() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-5");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_PKIX_TA,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, cert.getEncoded()));

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

        DaneTrustManager tm = new DaneTrustManager(rejectingDelegate, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testNoMatchRejected() throws Exception {
        X509Certificate cert = DANEVerifierTest.generateSelfSignedCert("tm-6");
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_DANE_EE,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, new byte[]{ 1, 2, 3 }));

        DaneTrustManager tm = new DaneTrustManager(null, tlsa);
        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRejectsEmptyRecords() {
        new DaneTrustManager(null, Collections.<DnsResourceRecord>emptyList());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRejectsNullRecords() {
        new DaneTrustManager(null, null);
    }

    @Test
    public void testGetAcceptedIssuersFallsBackToEmptyWithoutDelegate() {
        List<DnsResourceRecord> tlsa = Collections.singletonList(
                DnsResourceRecord.tlsa("_25._tcp.mail.example.com", 3600,
                        DaneVerifier.USAGE_DANE_EE,
                        DaneVerifier.SELECTOR_FULL_CERT,
                        DaneVerifier.MATCHING_TYPE_FULL, new byte[32]));
        DaneTrustManager tm = new DaneTrustManager(null, tlsa);
        assertEquals(0, tm.getAcceptedIssuers().length);
    }
}
