/*
 * SpkiPinnedCertTrustManagerTest.java
 * Copyright (C) 2025 Chris Burdess
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

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link SpkiPinnedCertTrustManager}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpkiPinnedCertTrustManagerTest {

    @Test
    public void fingerprintIsColonSeparatedLowercaseHex() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("spki");
        String fp = SpkiPinnedCertTrustManager.computeSPKIFingerprint(id.getCertificate());
        assertEquals(32 * 3 - 1, fp.length());
        assertEquals(fp.toLowerCase(), fp);
        assertEquals(':', fp.charAt(2));
    }

    @Test
    public void matchingPinIsAccepted() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("spki-ok");
        String fp = SpkiPinnedCertTrustManager.computeSPKIFingerprint(id.getCertificate());
        X509TrustManager delegate = TestCertificates.trustAll();
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager(delegate, "00:11", fp);
        X509Certificate[] chain = new X509Certificate[] {id.getCertificate()};
        tm.checkServerTrusted(chain, "ECDSA");
    }

    @Test
    public void mismatchedPinIsRejected() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("spki-bad");
        X509TrustManager delegate = TestCertificates.trustAll();
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager(delegate, "aa:bb");
        X509Certificate[] chain = new X509Certificate[] {id.getCertificate()};
        try {
            tm.checkServerTrusted(chain, "ECDSA");
            fail("expected CertificateException");
        } catch (CertificateException e) {
            assertTrue(e.getMessage().startsWith("SPKI fingerprint mismatch"));
        }
    }

    @Test
    public void emptyChainIsRejected() throws Exception {
        X509TrustManager delegate = TestCertificates.trustAll();
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager(delegate, "aa");
        try {
            tm.checkServerTrusted(new X509Certificate[0], "ECDSA");
            fail("expected CertificateException");
        } catch (CertificateException e) {
            assertEquals("Empty certificate chain", e.getMessage());
        }
    }

    @Test
    public void delegateRejectionPropagates() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("spki-deleg");
        String fp = SpkiPinnedCertTrustManager.computeSPKIFingerprint(id.getCertificate());
        X509TrustManager delegate = TestCertificates.trustNone();
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager(delegate, fp);
        X509Certificate[] chain = new X509Certificate[] {id.getCertificate()};
        try {
            tm.checkServerTrusted(chain, "ECDSA");
            fail("expected CertificateException");
        } catch (CertificateException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void clientChecksAndIssuersDelegate() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("spki-client");
        X509TrustManager delegate = TestCertificates.trustAll();
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager(delegate, "aa");
        X509Certificate[] chain = new X509Certificate[] {id.getCertificate()};
        tm.checkClientTrusted(chain, "ECDSA");
        X509Certificate[] issuers = tm.getAcceptedIssuers();
        X509Certificate[] expected = delegate.getAcceptedIssuers();
        assertEquals(expected.length, issuers.length);
        X509TrustManager none = TestCertificates.trustNone();
        SpkiPinnedCertTrustManager strict = new SpkiPinnedCertTrustManager(none, "aa");
        try {
            strict.checkClientTrusted(chain, "ECDSA");
            fail("expected CertificateException");
        } catch (CertificateException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void defaultDelegateConstructor() throws Exception {
        SpkiPinnedCertTrustManager tm = new SpkiPinnedCertTrustManager("aa");
        X509Certificate[] issuers = tm.getAcceptedIssuers();
        assertNotNull(issuers);
        assertSame(issuers.getClass(), X509Certificate[].class);
    }

    @Test
    public void fingerprintOfBrokenCertificateFails() throws Exception {
        try {
            SpkiPinnedCertTrustManager.computeSPKIFingerprint(null);
            fail("expected CertificateException");
        } catch (CertificateException e) {
            assertEquals("Failed to compute SPKI fingerprint", e.getMessage());
        }
    }
}
