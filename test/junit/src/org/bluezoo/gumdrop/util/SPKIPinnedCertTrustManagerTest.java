/*
 * SPKIPinnedCertTrustManagerTest.java
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

import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SPKIPinnedCertTrustManager}.
 * RFC 7858 section 4.2: SPKI fingerprint verification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SPKIPinnedCertTrustManagerTest {

    @Test
    public void testComputeSPKIFingerprintFormat() throws Exception {
        X509Certificate cert = generateSelfSignedCert();
        String fingerprint =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);

        assertNotNull(fingerprint);
        // SHA-256 produces 32 bytes = 32 hex pairs separated by 31 colons
        assertEquals(95, fingerprint.length());
        assertTrue(fingerprint.matches("([0-9a-f]{2}:){31}[0-9a-f]{2}"));
    }

    @Test
    public void testSPKIDiffersFromFullCert() throws Exception {
        X509Certificate cert = generateSelfSignedCert();
        String spki =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);
        String full =
                PinnedCertTrustManager.computeFingerprint(cert);

        // SPKI fingerprint and full-certificate fingerprint must differ
        assertNotEquals(spki, full);
    }

    @Test
    public void testSPKIConsistentForSameKey() throws Exception {
        X509Certificate cert = generateSelfSignedCert();
        String fp1 =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);
        String fp2 =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);
        assertEquals(fp1, fp2);
    }

    @Test
    public void testMatchingFingerprintAccepted() throws Exception {
        X509Certificate cert = generateSelfSignedCert();
        String fingerprint =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);

        // Use an accept-all delegate so we only test the pinning logic
        SPKIPinnedCertTrustManager tm = new SPKIPinnedCertTrustManager(
                new EmptyX509TrustManager(), fingerprint);

        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testMismatchedFingerprintRejected() throws Exception {
        X509Certificate cert = generateSelfSignedCert();

        SPKIPinnedCertTrustManager tm = new SPKIPinnedCertTrustManager(
                new EmptyX509TrustManager(),
                "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"
                        + ":00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff");

        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testEmptyChainRejected() throws Exception {
        SPKIPinnedCertTrustManager tm = new SPKIPinnedCertTrustManager(
                new EmptyX509TrustManager(), "aa:bb:cc:dd:ee:ff"
                + ":00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"
                + ":00:11:22:33:44:55:66:77:88:99:aa:bb");

        tm.checkServerTrusted(new X509Certificate[0], "RSA");
    }

    @Test
    public void testMultiplePinsOneMatches() throws Exception {
        X509Certificate cert = generateSelfSignedCert();
        String actual =
                SPKIPinnedCertTrustManager.computeSPKIFingerprint(cert);

        String bogus = "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"
                + ":00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff";

        SPKIPinnedCertTrustManager tm = new SPKIPinnedCertTrustManager(
                new EmptyX509TrustManager(), bogus, actual);

        tm.checkServerTrusted(new X509Certificate[]{ cert }, "RSA");
    }

    private static X509Certificate generateSelfSignedCert() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Use keytool-equivalent: generate via CertAndKeyGen-like approach
        // by creating a minimal self-signed cert with a PKCS12 keystore
        // round-trip through keytool subprocess
        String alias = "test";
        char[] password = "changeit".toCharArray();

        // Build the cert using ProcessBuilder to call keytool
        java.nio.file.Path tmpKs = java.nio.file.Files.createTempFile(
                "spki-test-", ".p12");
        try {
            java.nio.file.Files.delete(tmpKs);
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", alias,
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-sigalg", "SHA256withRSA",
                    "-validity", "1",
                    "-dname", "CN=Test",
                    "-storetype", "PKCS12",
                    "-keystore", tmpKs.toString(),
                    "-storepass", new String(password));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new RuntimeException("keytool failed with exit " + exit);
            }

            KeyStore generated = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in =
                         java.nio.file.Files.newInputStream(tmpKs)) {
                generated.load(in, password);
            }
            Certificate cert = generated.getCertificate(alias);
            return (X509Certificate) cert;
        } finally {
            java.nio.file.Files.deleteIfExists(tmpKs);
        }
    }
}
