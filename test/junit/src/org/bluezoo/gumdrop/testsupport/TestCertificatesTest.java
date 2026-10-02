/*
 * TestCertificatesTest.java
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

package org.bluezoo.gumdrop.testsupport;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.bluezoo.gumdrop.testsupport.TestCertificates.Identity;
import org.bluezoo.gumdrop.testsupport.TestCertificates.KeyKind;
import org.bluezoo.gumdrop.tls.ServerCredentials;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the in-memory certificate generator against independent
 * implementations: the JDK certificate parser, the JCA signature
 * verification, a JSSE handshake and PKCS12.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestCertificatesTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<Identity> all() throws Exception {
        List<Identity> list = new ArrayList<Identity>();
        list.add(TestCertificates.ec256());
        list.add(TestCertificates.ec384());
        list.add(TestCertificates.rsa2048());
        list.add(TestCertificates.rsa4096());
        return list;
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream in = new ByteArrayInputStream(der);
        return (X509Certificate) factory.generateCertificate(in);
    }

    @Test
    public void testParsesAsX509V3() throws Exception {
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            byte[] der = id.getDer();
            X509Certificate cert = parse(der);
            assertEquals(3, cert.getVersion());
            assertEquals("CN=" + TestCertificates.SERVER_NAME, cert.getSubjectX500Principal().getName());
            assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
            assertEquals(id.getKind().getSignatureName().toUpperCase(), cert.getSigAlgName().toUpperCase());
            assertTrue(cert.getSerialNumber().signum() > 0);
        }
    }

    @Test
    public void testSelfSignedSignatureVerifies() throws Exception {
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            X509Certificate cert = identities.get(i).getCertificate();
            PublicKey key = cert.getPublicKey();
            cert.verify(key);
        }
    }

    @Test
    public void testValidityWindow() throws Exception {
        X509Certificate cert = TestCertificates.ec256().getCertificate();
        cert.checkValidity();
        long span = cert.getNotAfter().getTime() - cert.getNotBefore().getTime();
        assertEquals(365L * 24L * 3600L * 1000L, span);
        Date before = new Date(cert.getNotBefore().getTime() - 1000L);
        try {
            cert.checkValidity(before);
            fail("expected not yet valid");
        } catch (java.security.cert.CertificateNotYetValidException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testSubjectAltNamesBasicConstraintsAndUsages() throws Exception {
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            X509Certificate cert = id.getCertificate();
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            List<String> dns = new ArrayList<String>();
            List<String> ips = new ArrayList<String>();
            for (List<?> entry : sans) {
                Integer type = (Integer) entry.get(0);
                String value = (String) entry.get(1);
                if (type.intValue() == 2) {
                    dns.add(value);
                } else if (type.intValue() == 7) {
                    ips.add(value);
                }
            }
            assertTrue(dns.contains(TestCertificates.SERVER_NAME));
            assertTrue(dns.contains("localhost"));
            assertTrue(ips.contains("127.0.0.1"));
            assertTrue(ips.contains("0:0:0:0:0:0:0:1"));
            assertEquals(-1, cert.getBasicConstraints());
            boolean[] usage = cert.getKeyUsage();
            assertTrue(usage[0]);
            assertFalse(usage[5]);
            assertEquals(id.getKind() == KeyKind.RSA_2048 || id.getKind() == KeyKind.RSA_4096, usage[2]);
            List<String> eku = cert.getExtendedKeyUsage();
            assertTrue(eku.contains("1.3.6.1.5.5.7.3.1"));
            assertTrue(eku.contains("1.3.6.1.5.5.7.3.2"));
            assertTrue(cert.getCriticalExtensionOIDs().contains("2.5.29.19"));
            assertTrue(cert.getCriticalExtensionOIDs().contains("2.5.29.15"));
        }
    }

    @Test
    public void testPrivateKeyMatchesCertificate() throws Exception {
        List<Identity> identities = all();
        byte[] message = "round trip".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            Signature signer = Signature.getInstance(id.getKind().getSignatureName());
            signer.initSign(id.getPrivateKey());
            signer.update(message);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance(id.getKind().getSignatureName());
            verifier.initVerify(id.getCertificate().getPublicKey());
            verifier.update(message);
            assertTrue(verifier.verify(sig));
            verifier.initVerify(otherKey(id));
            verifier.update(message);
            boolean accepted;
            try {
                accepted = verifier.verify(sig);
            } catch (java.security.SignatureException e) {
                accepted = false;
            }
            assertFalse(accepted);
        }
    }

    private static PublicKey otherKey(Identity id) throws Exception {
        KeyKind kind = id.getKind();
        if (kind == KeyKind.EC_P256) {
            return TestCertificates.ec384().getCertificate().getPublicKey();
        }
        if (kind == KeyKind.EC_P384) {
            return TestCertificates.ec256().getCertificate().getPublicKey();
        }
        if (kind == KeyKind.RSA_2048) {
            return TestCertificates.rsa4096().getCertificate().getPublicKey();
        }
        return TestCertificates.rsa2048().getCertificate().getPublicKey();
    }

    @Test
    public void testEncodingIsCanonical() throws Exception {
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            byte[] der = id.getDer();
            X509Certificate parsed = parse(der);
            byte[] reencoded = parsed.getEncoded();
            assertArrayEquals(der, reencoded);
            byte[] fromIdentity = id.getCertificate().getEncoded();
            assertArrayEquals(der, fromIdentity);
        }
    }

    @Test
    public void testPemRoundTrip() throws Exception {
        Path dir = folder.getRoot().toPath();
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            Path certFile = TestCertificates.writeCertificatePem(dir, "c" + i + ".pem", id);
            Path keyFile = TestCertificates.writePrivateKeyPem(dir, "k" + i + ".pem", id);
            byte[] certBytes = Files.readAllBytes(certFile);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream in = new ByteArrayInputStream(certBytes);
            X509Certificate back = (X509Certificate) factory.generateCertificate(in);
            assertEquals(id.getCertificate(), back);
            String keyText = new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII);
            assertTrue(keyText.startsWith("-----BEGIN PRIVATE KEY-----\n"));
            assertTrue(keyText.endsWith("-----END PRIVATE KEY-----\n"));
            String body = keyText.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replace("\n", "");
            byte[] pkcs8 = java.util.Base64.getDecoder().decode(body);
            assertArrayEquals(id.getPrivateKey().getEncoded(), pkcs8);
        }
    }

    @Test
    public void testTamperedSignatureFails() throws Exception {
        Identity id = TestCertificates.ec256();
        byte[] der = id.getDer();
        byte[] tampered = der.clone();
        tampered[tampered.length - 1] ^= 0x01;
        X509Certificate cert = parse(tampered);
        try {
            cert.verify(id.getCertificate().getPublicKey());
            fail("expected signature failure");
        } catch (java.security.SignatureException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testTamperedTbsFails() throws Exception {
        Identity id = TestCertificates.ec256();
        byte[] der = id.getDer();
        byte[] tampered = der.clone();
        byte[] marker = "test.gumdrop.local".getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(tampered, marker);
        assertTrue(at > 0);
        tampered[at] = 'X';
        X509Certificate cert = parse(tampered);
        try {
            cert.verify(id.getCertificate().getPublicKey());
            fail("expected signature failure");
        } catch (java.security.SignatureException expected) {
            assertNotNull(expected);
        }
    }

    private static int indexOf(byte[] data, byte[] needle) {
        for (int i = 0; i + needle.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void testWrongIssuerKeyFails() throws Exception {
        Identity id = TestCertificates.ec256();
        PublicKey wrong = TestCertificates.ec384().getCertificate().getPublicKey();
        try {
            id.getCertificate().verify(wrong);
            fail("expected failure");
        } catch (java.security.GeneralSecurityException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testCaIssuedChain() throws Exception {
        Identity ca = TestCertificates.newCa(KeyKind.EC_P256, "Test CA");
        Identity leaf = TestCertificates.newIssued(ca, KeyKind.RSA_2048, "leaf.gumdrop.local");
        X509Certificate caCert = ca.getCertificate();
        assertTrue(caCert.getBasicConstraints() >= 0);
        boolean[] caUsage = caCert.getKeyUsage();
        assertTrue(caUsage[5]);
        assertFalse(caUsage[0]);
        caCert.verify(caCert.getPublicKey());

        X509Certificate leafCert = leaf.getCertificate();
        leafCert.verify(caCert.getPublicKey());
        assertEquals(caCert.getSubjectX500Principal(), leafCert.getIssuerX500Principal());
        assertEquals(2, leaf.getChain().size());
        assertEquals(-1, leafCert.getBasicConstraints());
        try {
            leafCert.verify(leafCert.getPublicKey());
            fail("leaf must not verify with its own key");
        } catch (java.security.GeneralSecurityException expected) {
            assertNotNull(expected);
        }
        byte[] ski = ca.getCertificate().getExtensionValue("2.5.29.14");
        byte[] aki = leafCert.getExtensionValue("2.5.29.35");
        assertNotNull(ski);
        assertNotNull(aki);
        // the CA alone is enough to trust the leaf through PKIX
        X509TrustManager tm = TestCertificates.trustManager(ca.getChain());
        X509Certificate[] presented = leaf.getChain().toArray(new X509Certificate[0]);
        tm.checkServerTrusted(presented, "RSA");
    }

    @Test
    public void testFreshIdentitiesAreDistinct() throws Exception {
        Identity a = TestCertificates.newEc256("a");
        Identity b = TestCertificates.newEc256("a");
        assertFalse(a.getCertificate().equals(b.getCertificate()));
    }

    @Test
    public void testSharedIdentitiesAreCached() throws Exception {
        assertTrue(TestCertificates.ec256() == TestCertificates.ec256());
        assertTrue(TestCertificates.rsa2048() == TestCertificates.rsa2048());
    }

    @Test
    public void testPkcs12RoundTrip() throws Exception {
        Path dir = folder.getRoot().toPath();
        List<Identity> identities = all();
        for (int i = 0; i < identities.size(); i++) {
            Identity id = identities.get(i);
            Path file = TestCertificates.writeKeyStore(dir, "s" + i + ".p12", id, "k", PASSWORD);
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            byte[] bytes = Files.readAllBytes(file);
            loaded.load(new ByteArrayInputStream(bytes), PASSWORD);
            assertEquals(id.getCertificate(), loaded.getCertificate("k"));
            PrivateKey key = (PrivateKey) loaded.getKey("k", PASSWORD);
            assertEquals(id.getPrivateKey(), key);
        }
    }

    @Test
    public void testCredentialsAndTrustManager() throws Exception {
        Identity id = TestCertificates.ec256();
        ServerCredentials creds = id.credentials();
        assertEquals(id.getPrivateKey(), creds.getPrivateKey());
        X509TrustManager tm = id.trustManager();
        X509Certificate[] one = new X509Certificate[] {id.getCertificate()};
        tm.checkServerTrusted(one, "ECDHE_ECDSA");
        Identity stranger = TestCertificates.newEc256("stranger");
        X509Certificate[] bad = new X509Certificate[] {stranger.getCertificate()};
        try {
            tm.checkServerTrusted(bad, "ECDHE_ECDSA");
            fail("expected untrusted");
        } catch (java.security.cert.CertificateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testJsseHandshakeEc256() throws Exception {
        handshake(TestCertificates.ec256());
    }

    @Test
    public void testJsseHandshakeEc384() throws Exception {
        handshake(TestCertificates.ec384());
    }

    @Test
    public void testJsseHandshakeRsa() throws Exception {
        handshake(TestCertificates.rsa2048());
    }

    @Test
    public void testJsseHandshakeCaIssuedChain() throws Exception {
        Identity ca = TestCertificates.newCa(KeyKind.EC_P256, "Handshake CA");
        Identity leaf = TestCertificates.newIssued(ca, KeyKind.EC_P256, TestCertificates.SERVER_NAME);
        handshake(leaf, ca);
    }

    private static void handshake(Identity id) throws Exception {
        handshake(id, id);
    }

    private static void handshake(Identity server, Identity trustRoot) throws Exception {
        KeyStore serverStore = TestCertificates.keyStore(server, "k", PASSWORD);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverStore, PASSWORD);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("root", trustRoot.getCertificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);

        SSLEngine client = clientCtx.createSSLEngine(TestCertificates.SERVER_NAME, 443);
        client.setUseClientMode(true);
        javax.net.ssl.SSLParameters params = client.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSSLParameters(params);
        SSLEngine srv = serverCtx.createSSLEngine();
        srv.setUseClientMode(false);

        ByteBuffer empty = ByteBuffer.allocate(0);
        ByteBuffer c2s = ByteBuffer.allocate(64 * 1024);
        ByteBuffer s2c = ByteBuffer.allocate(64 * 1024);
        ByteBuffer clientApp = ByteBuffer.allocate(64 * 1024);
        ByteBuffer serverApp = ByteBuffer.allocate(64 * 1024);
        client.beginHandshake();
        srv.beginHandshake();
        for (int round = 0; round < 100; round++) {
            boolean progress = false;
            progress |= step(client, empty, c2s, clientApp, s2c);
            progress |= step(srv, empty, s2c, serverApp, c2s);
            if (isDone(client) && isDone(srv)) {
                break;
            }
            if (!progress) {
                break;
            }
        }
        assertTrue("client handshake incomplete", isDone(client));
        assertTrue("server handshake incomplete", isDone(srv));
        String peer = client.getSession().getPeerPrincipal().getName();
        assertEquals("CN=" + TestCertificates.SERVER_NAME, peer);
    }

    private static boolean isDone(SSLEngine engine) {
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
        return status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    /**
     * Advances one engine: produces wrap output into {@code out}, consumes
     * available input from {@code in} (flipped for reading, then compacted).
     */
    private static boolean step(SSLEngine engine, ByteBuffer empty, ByteBuffer out,
            ByteBuffer app, ByteBuffer in) throws Exception {
        boolean progress = false;
        for (int i = 0; i < 20; i++) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                Runnable task = engine.getDelegatedTask();
                while (task != null) {
                    task.run();
                    task = engine.getDelegatedTask();
                }
                progress = true;
            } else if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                SSLEngineResult result = engine.wrap(empty, out);
                assertTrue(result.getStatus() == SSLEngineResult.Status.OK
                        || result.getStatus() == SSLEngineResult.Status.CLOSED);
                progress = true;
            } else if (hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                    || hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) {
                in.flip();
                if (!in.hasRemaining()) {
                    in.compact();
                    break;
                }
                SSLEngineResult result = engine.unwrap(in, app);
                in.compact();
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    break;
                }
                assertTrue(result.getStatus() == SSLEngineResult.Status.OK);
                progress = true;
            } else {
                break;
            }
        }
        return progress;
    }
}
