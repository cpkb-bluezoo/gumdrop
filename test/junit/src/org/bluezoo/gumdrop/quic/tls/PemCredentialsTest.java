/*
 * PemCredentialsTest.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.bluezoo.gumdrop.tls.ServerCredentials;

import static org.junit.Assert.*;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Unit tests for {@link PemCredentials}, using throwaway material generated
 * in memory and written to a temporary folder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PemCredentialsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static String pem(String label, byte[] der) {
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        String body = encoder.encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private Path write(String name, String content) throws IOException {
        Path p = folder.newFile(name).toPath();
        Files.write(p, content.getBytes(StandardCharsets.US_ASCII));
        return p;
    }

    private Path writeCert(String name, ServerCredentials creds, int copies) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] der = creds.getCertificateChain().get(0).getEncoded();
        for (int i = 0; i < copies; i++) {
            sb.append(pem("CERTIFICATE", der));
        }
        return write(name, sb.toString());
    }

    @Test
    public void testLoadsEcCredentials() throws Exception {
        ServerCredentials creds = TestCertificates.newEc256("localhost").credentials();
        Path cert = writeCert("ec.crt", creds, 1);
        PrivateKey key = creds.getPrivateKey();
        Path keyFile = write("ec.key", pem("PRIVATE KEY", key.getEncoded()));
        ServerCredentials loaded = PemCredentials.loadServerCredentials(cert, keyFile);
        assertEquals(1, loaded.getCertificateChain().size());
        assertEquals(key, loaded.getPrivateKey());
    }

    @Test
    public void testLoadsRsaPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Path keyFile = write("rsa.key", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        PrivateKey loaded = PemCredentials.loadPrivateKey(keyFile);
        assertEquals("RSA", loaded.getAlgorithm());
    }

    @Test
    public void testUnsupportedKeyAlgorithmRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        Path keyFile = write("ed.key", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        try {
            PemCredentials.loadPrivateKey(keyFile);
            fail("expected InvalidKeyException");
        } catch (InvalidKeyException expected) {
            assertTrue(expected.getMessage().contains("ed.key"));
        }
    }

    @Test
    public void testKeyFileWithoutBlockRejected() throws Exception {
        Path keyFile = write("none.key", "just some text\n");
        try {
            PemCredentials.loadPrivateKey(keyFile);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PRIVATE KEY"));
        }
    }

    @Test
    public void testKeyFileWithBeginButNoEndRejected() throws Exception {
        Path keyFile = write("trunc.key", "-----BEGIN PRIVATE KEY-----\nAAAA\n");
        try {
            PemCredentials.loadPrivateKey(keyFile);
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testChainWithMultipleCertificates() throws Exception {
        ServerCredentials creds = TestCertificates.newEc256("localhost").credentials();
        Path cert = writeCert("chain.crt", creds, 2);
        List<X509Certificate> chain = PemCredentials.loadCertificateChain(cert);
        assertEquals(2, chain.size());
    }

    @Test
    public void testEmptyCertificateFileRejected() throws Exception {
        Path empty = write("empty.crt", "");
        try {
            PemCredentials.loadCertificateChain(empty);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("empty.crt"));
        }
    }

    @Test
    public void testMissingCertificateFileRejected() throws Exception {
        Path missing = folder.getRoot().toPath().resolve("absent.crt");
        try {
            PemCredentials.loadCertificateChain(missing);
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testGarbageCertificateRejected() throws Exception {
        Path bad = write("bad.crt", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");
        try {
            PemCredentials.loadCertificateChain(bad);
            fail("expected a failure");
        } catch (GeneralSecurityException expected) {
            assertNotNull(expected);
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testTrustManagerTrustsLoadedCa() throws Exception {
        ServerCredentials creds = TestCertificates.newEc256("localhost").credentials();
        Path ca = writeCert("ca.crt", creds, 1);
        X509TrustManager tm = PemCredentials.loadTrustManager(ca);
        X509Certificate cert = creds.getCertificateChain().get(0);
        tm.checkServerTrusted(new X509Certificate[] {cert}, "ECDHE_ECDSA");
        assertEquals(1, tm.getAcceptedIssuers().length);
    }

    @Test
    public void testTrustManagerRejectsUnknownCertificate() throws Exception {
        ServerCredentials trusted = TestCertificates.newEc256("localhost").credentials();
        ServerCredentials other = TestCertificates.newEc256("localhost").credentials();
        Path ca = writeCert("ca2.crt", trusted, 1);
        X509TrustManager tm = PemCredentials.loadTrustManager(ca);
        X509Certificate cert = other.getCertificateChain().get(0);
        try {
            tm.checkServerTrusted(new X509Certificate[] {cert}, "ECDHE_ECDSA");
            fail("expected untrusted");
        } catch (java.security.cert.CertificateException expected) {
            assertNotNull(expected);
        }
    }

}
