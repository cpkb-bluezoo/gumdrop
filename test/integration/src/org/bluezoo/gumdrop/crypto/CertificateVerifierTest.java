/*
 * CertificateVerifierTest.java
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises {@link CertificateVerifier} against a real keytool-generated
 * self-signed certificate -- the trusted/hostname-matching happy path,
 * and the wrong-hostname and untrusted-chain rejection paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CertificateVerifierTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static X509Certificate certificate;

    @BeforeClass
    public static void generateCertificate() throws Exception {
        certsDirectory = Files.createTempDirectory("certificate-verifier-test");
        Path keystorePath = certsDirectory.resolve("server.p12");

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail("keytool failed to generate a test certificate");
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        certificate = (X509Certificate) keyStore.getCertificate("server");
    }

    @AfterClass
    public static void deleteCertificate() throws IOException {
        if (certsDirectory != null) {
            Files.walkFileTree(certsDirectory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    @Test
    public void trustedChainWithCorrectHostnameVerifies() throws Exception {
        List<X509Certificate> chain = Collections.singletonList(certificate);
        X509TrustManager trustManager = CertificateVerifier.trustManagerFromCertificates(chain);
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(chain, trustManager, SERVER_NAME);
        assertTrue(result.getError(), result.isOk());
    }

    @Test
    public void wrongHostnameIsRejected() throws Exception {
        List<X509Certificate> chain = Collections.singletonList(certificate);
        X509TrustManager trustManager = CertificateVerifier.trustManagerFromCertificates(chain);
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(chain, trustManager, "wrong.example.com");
        assertFalse(result.isOk());
    }

    @Test
    public void hostnameMatchesViaCommonNameWhenCertificateHasNoSan() throws Exception {
        // Reproduces the exact shape of certificate this project's own
        // test suites generate almost everywhere (plain "keytool
        // -genkeypair -dname CN=..." with no "-ext san=dns:..."): no
        // SAN extension at all, identity carried only in the Subject
        // CN. A real regression caught by HTTP3ProductionEndToEndTest
        // (and by extension every other production end-to-end suite)
        // when matchesHostname originally required a SAN match with no
        // CN fallback.
        Path keystorePath = certsDirectory.resolve("cn-only.p12");
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "cnonly",
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(process.exitValue() == 0);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        X509Certificate cnOnlyCert = (X509Certificate) keyStore.getCertificate("cnonly");

        assertTrue(CertificateVerifier.matchesHostname(cnOnlyCert, SERVER_NAME));
        assertFalse(CertificateVerifier.matchesHostname(cnOnlyCert, "wrong.example.com"));
    }

    @Test
    public void hostnameDoesNotFallBackToCommonNameWhenSanIsPresent() throws Exception {
        // The opposite case: a SAN extension is present but its only
        // dNSName entry does not match, even though the CN would --
        // the CN must not rescue this (RFC 6125 section 6.4.4: once a
        // SAN identity is declared, the CN is not consulted).
        Path keystorePath = certsDirectory.resolve("cn-matches-san-does-not.p12");
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "mismatch",
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:other.gumdrop.local",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(process.exitValue() == 0);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        X509Certificate mismatchCert = (X509Certificate) keyStore.getCertificate("mismatch");

        assertFalse(CertificateVerifier.matchesHostname(mismatchCert, SERVER_NAME));
        assertTrue(CertificateVerifier.matchesHostname(mismatchCert, "other.gumdrop.local"));
    }

    @Test
    public void untrustedChainIsRejected() throws Exception {
        Path otherKeystorePath = certsDirectory.resolve("other.p12");
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "other",
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", "1",
                "-dname", "CN=other.gumdrop.local",
                "-keystore", otherKeystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(process.exitValue() == 0);

        KeyStore otherStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(otherKeystorePath)) {
            otherStore.load(in, "changeit".toCharArray());
        }
        X509Certificate unrelated = (X509Certificate) otherStore.getCertificate("other");

        List<X509Certificate> chain = Collections.singletonList(certificate);
        X509TrustManager trustManager = CertificateVerifier.trustManagerFromCertificates(
                Collections.singletonList(unrelated));
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(chain, trustManager, SERVER_NAME);
        assertFalse(result.isOk());
    }

}
