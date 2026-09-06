/*
 * DANEVerifierTest.java
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

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DANEVerifier}.
 * RFC 6698 section 2: certificate usage, selector, and matching type
 * comparisons.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DANEVerifierTest {

    @Test
    public void testFullCertMatch() throws Exception {
        X509Certificate cert = generateSelfSignedCert("dane-1");
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_FULL, cert.getEncoded());

        assertTrue(DANEVerifier.matches(cert, tlsa));
    }

    @Test
    public void testFullCertSha256Match() throws Exception {
        X509Certificate cert = generateSelfSignedCert("dane-2");
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(cert.getEncoded());
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_SHA256, hash);

        assertTrue(DANEVerifier.matches(cert, tlsa));
    }

    @Test
    public void testSpkiSha256Match() throws Exception {
        X509Certificate cert = generateSelfSignedCert("dane-3");
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(cert.getPublicKey().getEncoded());
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_SPKI,
                DANEVerifier.MATCHING_TYPE_SHA256, hash);

        assertTrue(DANEVerifier.matches(cert, tlsa));
    }

    @Test
    public void testSpkiSha512Match() throws Exception {
        X509Certificate cert = generateSelfSignedCert("dane-4");
        byte[] hash = MessageDigest.getInstance("SHA-512")
                .digest(cert.getPublicKey().getEncoded());
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_SPKI,
                DANEVerifier.MATCHING_TYPE_SHA512, hash);

        assertTrue(DANEVerifier.matches(cert, tlsa));
    }

    @Test
    public void testMismatchDoesNotMatch() throws Exception {
        X509Certificate cert = generateSelfSignedCert("dane-5");
        byte[] wrongHash = new byte[32];
        Arrays.fill(wrongHash, (byte) 0x11);
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_SHA256, wrongHash);

        assertFalse(DANEVerifier.matches(cert, tlsa));
    }

    @Test
    public void testFindMatchUsageEeOnlyChecksLeaf() throws Exception {
        X509Certificate leaf = generateSelfSignedCert("dane-leaf-1");
        X509Certificate other = generateSelfSignedCert("dane-other-1");

        // TLSA record matches "other", not "leaf" -- usage DANE-EE (3)
        // must only ever compare against chain[0] (the leaf), so this
        // must not match even though "other" is in the chain.
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_FULL, other.getEncoded());

        DNSResourceRecord match = DANEVerifier.findMatch(
                new X509Certificate[]{ leaf, other },
                Collections.singletonList(tlsa));
        assertNull(match);
    }

    @Test
    public void testFindMatchUsageTaChecksWholeChain() throws Exception {
        X509Certificate leaf = generateSelfSignedCert("dane-leaf-2");
        X509Certificate ca = generateSelfSignedCert("dane-ca-2");

        // Usage DANE-TA (2) may match any certificate in the chain,
        // not just the leaf.
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_TA, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_FULL, ca.getEncoded());

        DNSResourceRecord match = DANEVerifier.findMatch(
                new X509Certificate[]{ leaf, ca },
                Collections.singletonList(tlsa));
        assertSame(tlsa, match);
    }

    @Test
    public void testFindMatchReturnsNullWhenNoneMatch() throws Exception {
        X509Certificate leaf = generateSelfSignedCert("dane-leaf-3");
        byte[] wrongHash = new byte[32];
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_SHA256, wrongHash);

        assertNull(DANEVerifier.findMatch(
                new X509Certificate[]{ leaf },
                Collections.singletonList(tlsa)));
    }

    @Test
    public void testFindMatchNullOrEmptyChainReturnsNull() throws Exception {
        DNSResourceRecord tlsa = DNSResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600,
                DANEVerifier.USAGE_DANE_EE, DANEVerifier.SELECTOR_FULL_CERT,
                DANEVerifier.MATCHING_TYPE_FULL, new byte[32]);

        assertNull(DANEVerifier.findMatch(null,
                Collections.singletonList(tlsa)));
        assertNull(DANEVerifier.findMatch(new X509Certificate[0],
                Collections.singletonList(tlsa)));
    }

    static X509Certificate generateSelfSignedCert(String alias) throws Exception {
        char[] password = "changeit".toCharArray();
        Path tmpKs = Files.createTempFile("dane-test-", ".p12");
        try {
            Files.delete(tmpKs);
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", alias,
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-sigalg", "SHA256withRSA",
                    "-validity", "1",
                    "-dname", "CN=Test " + alias,
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
            try (java.io.InputStream in = Files.newInputStream(tmpKs)) {
                generated.load(in, password);
            }
            Certificate cert = generated.getCertificate(alias);
            return (X509Certificate) cert;
        } finally {
            Files.deleteIfExists(tmpKs);
        }
    }
}
