/*
 * DaneVerifierTest.java
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
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link DaneVerifier} and {@link DaneTrustManager} (RFC 6698, RFC 7671).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DaneVerifierTest {

    private static final String PEM =
        "-----BEGIN CERTIFICATE-----\n"
        + "MIIBNjCB3aADAgECAgh1I4skXWJPhDAKBggqhkjOPQQDAzAPMQ0wCwYDVQQDEwR0\n"
        + "ZXN0MCAXDTI2MDkxOTExMjM0MloYDzIxMjYwODI2MTEyMzQyWjAPMQ0wCwYDVQQD\n"
        + "EwR0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEV5gAI8gZ2YGb52tKX5N8\n"
        + "up+A8J/aKbeq2PgW+KvdRt2rCYmLzk4g67iRYGrLlQHOTMbw3GscbeDzVUPjatNT\n"
        + "8aMhMB8wHQYDVR0OBBYEFM8duMe4xH5zlyWDaoFV9EcsExlAMAoGCCqGSM49BAMD\n"
        + "A0gAMEUCIQCKhfqN22FNAhUruKJNOyPTa5hsajCb8FVXlbSvH+SXiAIgQdXT3fjg\n"
        + "IarFFg909wB0F1VwUU20xjGks1PiI3liFYI=\n"
        + "-----END CERTIFICATE-----\n";

    private X509Certificate cert;
    private byte[] full;
    private byte[] spki;

    /** Trust manager recording whether it was consulted. */
    private static final class RecordingTrustManager implements X509TrustManager {
        int serverCalls;
        int clientCalls;
        boolean reject;

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            clientCalls++;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            serverCalls++;
            if (reject) {
                throw new CertificateException("rejected");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    @Before
    public void setUp() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        byte[] bytes = PEM.getBytes(StandardCharsets.US_ASCII);
        cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes));
        full = cert.getEncoded();
        spki = cert.getPublicKey().getEncoded();
    }

    private static byte[] hash(String alg, byte[] data) throws Exception {
        return MessageDigest.getInstance(alg).digest(data);
    }

    private static DnsResourceRecord tlsa(int usage, int selector, int matching, byte[] data) {
        return DnsResourceRecord.tlsa("_443._tcp.example.com.", 300, usage, selector,
                matching, data);
    }

    private static List<DnsResourceRecord> one(DnsResourceRecord rr) {
        return Collections.singletonList(rr);
    }

    @Test
    public void testMatchesAllSelectorAndMatchingCombinations() throws Exception {
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 0, 0, full)));
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 0, 1, hash("SHA-256", full))));
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 0, 2, hash("SHA-512", full))));
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 1, 0, spki)));
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 1, 1, hash("SHA-256", spki))));
        assertTrue(DaneVerifier.matches(cert, tlsa(3, 1, 2, hash("SHA-512", spki))));
    }

    @Test
    public void testMismatchedDataDoesNotMatch() throws Exception {
        assertFalse(DaneVerifier.matches(cert, tlsa(3, 1, 1, new byte[32])));
        assertFalse(DaneVerifier.matches(cert, tlsa(3, 0, 1, hash("SHA-256", spki))));
    }

    @Test
    public void testUnsupportedSelectorAndMatchingType() {
        try {
            DaneVerifier.matches(cert, tlsa(3, 7, 1, new byte[32]));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("selector"));
        } catch (Exception e) {
            fail(e.toString());
        }
        try {
            DaneVerifier.matches(cert, tlsa(3, 1, 9, new byte[32]));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("matching type"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testFindMatchChainRules() throws Exception {
        DnsResourceRecord eeHit = tlsa(3, 1, 1, hash("SHA-256", spki));
        X509Certificate[] chain = new X509Certificate[] {cert};
        assertSame(eeHit, DaneVerifier.findMatch(chain, one(eeHit)));
        DnsResourceRecord pkixEe = tlsa(1, 1, 1, hash("SHA-256", spki));
        assertSame(pkixEe, DaneVerifier.findMatch(chain, one(pkixEe)));
        // TA usage may match a non-leaf certificate
        DnsResourceRecord ta = tlsa(2, 0, 0, full);
        X509Certificate[] pair = new X509Certificate[] {cert, cert};
        assertSame(ta, DaneVerifier.findMatch(pair, one(ta)));
        DnsResourceRecord pkixTa = tlsa(0, 0, 0, full);
        assertSame(pkixTa, DaneVerifier.findMatch(pair, one(pkixTa)));
        // no match
        DnsResourceRecord miss = tlsa(3, 1, 1, new byte[32]);
        assertNull(DaneVerifier.findMatch(chain, one(miss)));
        DnsResourceRecord taMiss = tlsa(2, 1, 1, new byte[32]);
        assertNull(DaneVerifier.findMatch(chain, one(taMiss)));
        assertNull(DaneVerifier.findMatch(null, one(eeHit)));
        assertNull(DaneVerifier.findMatch(new X509Certificate[0], one(eeHit)));
    }

    @Test
    public void testTrustManagerRequiresRecords() {
        try {
            new DaneTrustManager(null, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new DaneTrustManager(null, new ArrayList<DnsResourceRecord>());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testDaneEeAcceptedWithoutDelegate() throws Exception {
        DnsResourceRecord rr = tlsa(3, 1, 1, hash("SHA-256", spki));
        DaneTrustManager tm = new DaneTrustManager(null, one(rr));
        tm.checkServerTrusted(new X509Certificate[] {cert}, "ECDHE_ECDSA");
        assertEquals(0, tm.getAcceptedIssuers().length);
    }

    @Test
    public void testDaneTaDoesNotConsultDelegate() throws Exception {
        RecordingTrustManager delegate = new RecordingTrustManager();
        delegate.reject = true;
        DnsResourceRecord rr = tlsa(2, 0, 0, full);
        DaneTrustManager tm = new DaneTrustManager(delegate, one(rr));
        tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA");
        assertEquals(0, delegate.serverCalls);
    }

    @Test
    public void testPkixUsageConsultsDelegate() throws Exception {
        RecordingTrustManager delegate = new RecordingTrustManager();
        DnsResourceRecord rr = tlsa(1, 1, 1, hash("SHA-256", spki));
        DaneTrustManager tm = new DaneTrustManager(delegate, one(rr));
        tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA");
        assertEquals(1, delegate.serverCalls);
        delegate.reject = true;
        try {
            tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA");
            fail("expected CertificateException");
        } catch (CertificateException expected) {
            assertEquals("rejected", expected.getMessage());
        }
    }

    @Test
    public void testPkixUsageWithoutDelegateFails() throws Exception {
        DnsResourceRecord rr = tlsa(0, 1, 1, hash("SHA-256", spki));
        DaneTrustManager tm = new DaneTrustManager(null, one(rr));
        try {
            tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA");
            fail("expected CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("requires WebPKI"));
        }
    }

    @Test
    public void testNoMatchFails() throws Exception {
        DnsResourceRecord rr = tlsa(3, 1, 1, new byte[32]);
        DaneTrustManager tm = new DaneTrustManager(null, one(rr));
        try {
            tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA");
            fail("expected CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("No DANE TLSA"));
        }
    }

    @Test
    public void testClientTrustDelegation() throws Exception {
        DnsResourceRecord rr = tlsa(3, 1, 1, new byte[32]);
        DaneTrustManager none = new DaneTrustManager(null, one(rr));
        try {
            none.checkClientTrusted(new X509Certificate[] {cert}, "RSA");
            fail("expected CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("No delegate"));
        }
        RecordingTrustManager delegate = new RecordingTrustManager();
        DaneTrustManager tm = new DaneTrustManager(delegate, one(rr));
        tm.checkClientTrusted(new X509Certificate[] {cert}, "RSA");
        assertEquals(1, delegate.clientCalls);
        assertEquals(0, tm.getAcceptedIssuers().length);
    }
}
