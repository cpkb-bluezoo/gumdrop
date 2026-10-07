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

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import java.io.ByteArrayOutputStream;
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
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.junit.Test;

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

    private final Path folder = newFolder();

    private static Path newFolder() {
        try {
            return MemoryTemp.createTempDirectory("files");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] der) {
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        String body = encoder.encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private Path write(String name, String content) throws IOException {
        Path p = folder.resolve(name);
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
    public void testLoadsEd25519AndMlDsaCredentials() throws Exception {
        TestCertificates.KeyKind[] kinds = new TestCertificates.KeyKind[] {
            TestCertificates.KeyKind.ED25519, TestCertificates.KeyKind.ML_DSA_44,
            TestCertificates.KeyKind.ML_DSA_65, TestCertificates.KeyKind.ML_DSA_87
        };
        for (int i = 0; i < kinds.length; i++) {
            ServerCredentials creds = TestCertificates.newSelfSigned(kinds[i], "localhost").credentials();
            Path cert = writeCert(kinds[i] + ".crt", creds, 1);
            PrivateKey key = creds.getPrivateKey();
            Path keyFile = write(kinds[i] + ".key", pem("PRIVATE KEY", key.getEncoded()));
            ServerCredentials loaded = PemCredentials.loadServerCredentials(cert, keyFile);
            assertEquals(kinds[i].toString(), key, loaded.getPrivateKey());
        }
    }

    // ── SEC1 ("EC PRIVATE KEY") keys, as OpenSSL writes them by default ──

    /** DER OBJECT IDENTIFIER prime256v1 (1.2.840.10045.3.1.7). */
    private static final byte[] P256_OID = {
        0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07
    };

    private static byte[] der(int tag, byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            out.write(0x81);
            out.write(length);
        }
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /**
     * RFC 5915 ECPrivateKey: version 1, the scalar, and optionally the
     * named curve in [0]. The public key in [1] is left out, as it is
     * optional and some generators omit it.
     */
    private static byte[] sec1(ECPrivateKey key, boolean embedCurve) {
        byte[] s = key.getS().toByteArray();
        byte[] scalar = new byte[32];
        int skip = Math.max(0, s.length - 32);
        System.arraycopy(s, skip, scalar, 32 - (s.length - skip), s.length - skip);
        byte[] version = der(0x02, new byte[] {1});
        byte[] privateKey = der(0x04, scalar);
        if (embedCurve) {
            return der(0x30, version, privateKey, der(0xA0, P256_OID));
        }
        return der(0x30, version, privateKey);
    }

    private static ECPrivateKey newP256Key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return (ECPrivateKey) generator.generateKeyPair().getPrivate();
    }

    @Test
    public void testLoadsSec1EcPrivateKeyWithEmbeddedCurve() throws Exception {
        ECPrivateKey key = newP256Key();
        Path keyFile = write("sec1.key", pem("EC PRIVATE KEY", sec1(key, true)));
        ECPrivateKey loaded = (ECPrivateKey) PemCredentials.loadPrivateKey(keyFile);
        assertEquals(key.getS(), loaded.getS());
        assertEquals(key.getParams().getCurve(), loaded.getParams().getCurve());
    }

    @Test
    public void testLoadsSec1EcPrivateKeyWithSeparateParametersBlock() throws Exception {
        // "openssl ecparam -genkey" output: an EC PARAMETERS block naming
        // the curve, then the key, which may or may not repeat the curve.
        ECPrivateKey key = newP256Key();
        Path keyFile = write("ecparam.key",
                pem("EC PARAMETERS", P256_OID) + pem("EC PRIVATE KEY", sec1(key, false)));
        ECPrivateKey loaded = (ECPrivateKey) PemCredentials.loadPrivateKey(keyFile);
        assertEquals(key.getS(), loaded.getS());
        assertEquals(key.getParams().getCurve(), loaded.getParams().getCurve());
    }

    @Test
    public void testSec1EcPrivateKeyWithoutAnyCurveRejected() throws Exception {
        ECPrivateKey key = newP256Key();
        Path keyFile = write("nocurve.key", pem("EC PRIVATE KEY", sec1(key, false)));
        try {
            PemCredentials.loadPrivateKey(keyFile);
            fail("expected a key without curve parameters to be rejected");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("curve"));
        }
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
        // A key-agreement key: well-formed PKCS8, but nothing a TLS
        // endpoint can sign a handshake with.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
        KeyPair pair = generator.generateKeyPair();
        Path keyFile = write("xdh.key", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        try {
            PemCredentials.loadPrivateKey(keyFile);
            fail("expected InvalidKeyException");
        } catch (InvalidKeyException expected) {
            assertTrue(expected.getMessage().contains("xdh.key"));
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
        Path missing = folder.resolve("absent.crt");
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
