/*
 * PinnedCertTrustManagerTest.java
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

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PinnedCertTrustManager} and
 * {@link EmptyX509TrustManager} using an in-memory certificate.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PinnedCertTrustManagerTest {

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

    @Before
    public void setUp() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        byte[] bytes = PEM.getBytes(StandardCharsets.US_ASCII);
        cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes));
    }

    @Test
    public void fingerprintIsColonSeparatedSha256() throws Exception {
        String fp = PinnedCertTrustManager.computeFingerprint(cert);
        assertEquals(32 * 3 - 1, fp.length());
        assertEquals(fp.toLowerCase(), fp);
        assertEquals(':', fp.charAt(2));
    }

    @Test
    public void matchingFingerprintIsAccepted() throws Exception {
        String fp = PinnedCertTrustManager.computeFingerprint(cert);
        PinnedCertTrustManager tm =
            new PinnedCertTrustManager(new EmptyX509TrustManager(), fp);
        tm.checkServerTrusted(new X509Certificate[] { cert }, "ECDHE_ECDSA");
    }

    @Test
    public void mismatchedFingerprintIsRejected() throws Exception {
        PinnedCertTrustManager tm =
            new PinnedCertTrustManager(new EmptyX509TrustManager(), "00:11");
        try {
            tm.checkServerTrusted(new X509Certificate[] { cert }, "ECDHE_ECDSA");
            fail("expected CertificateException");
        } catch (CertificateException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }
    }

    @Test(expected = CertificateException.class)
    public void emptyChainIsRejected() throws Exception {
        PinnedCertTrustManager tm =
            new PinnedCertTrustManager(new EmptyX509TrustManager(), "00:11");
        tm.checkServerTrusted(new X509Certificate[0], "RSA");
    }

    @Test
    public void clientChecksAndIssuersDelegate() throws Exception {
        PinnedCertTrustManager tm =
            new PinnedCertTrustManager(new EmptyX509TrustManager(), "00:11");
        tm.checkClientTrusted(new X509Certificate[] { cert }, "RSA");
        assertEquals(0, tm.getAcceptedIssuers().length);
    }

    @Test
    public void emptyTrustManagerAcceptsEverything() throws Exception {
        EmptyX509TrustManager tm = new EmptyX509TrustManager();
        tm.checkClientTrusted(null, null);
        tm.checkServerTrusted(null, null);
        assertEquals(0, tm.getAcceptedIssuers().length);
    }
}
