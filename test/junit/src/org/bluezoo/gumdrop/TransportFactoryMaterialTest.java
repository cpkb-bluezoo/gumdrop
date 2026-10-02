/*
 * TransportFactoryMaterialTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Before;
import org.junit.Test;

/**
 * Success paths of {@link TcpTransportFactory#start()} and
 * {@link UdpTransportFactory#start()} with real key material read from an
 * in-memory file system: PEM certificate and key, PKCS12 key stores with and
 * without SNI, trust stores and pinned fingerprints, and the cipher suite and
 * group lists.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TransportFactoryMaterialTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String PW = "changeit";

    private Path root;
    private TestCertificates.Identity identity;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem fs = MemoryFileSystem.create();
        root = fs.getPath("/");
        identity = TestCertificates.newEc256("material-test");
    }

    private Path writeText(String name, String text) throws IOException {
        Path p = root.resolve(name);
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private Path writeStore(String name, String alias) throws Exception {
        KeyStore ks = TestCertificates.keyStore(identity, alias, PASSWORD);
        Path p = root.resolve(name);
        OutputStream out = Files.newOutputStream(p);
        try {
            ks.store(out, PASSWORD);
        } finally {
            out.close();
        }
        return p;
    }

    private Path writeTrustStore(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", identity.getCertificate());
        Path p = root.resolve(name);
        OutputStream out = Files.newOutputStream(p);
        try {
            ks.store(out, PASSWORD);
        } finally {
            out.close();
        }
        return p;
    }

    private Path pemCert() throws Exception {
        String pem = TestCertificates.certificatesPem(identity.getChain());
        return writeText("server.crt", pem);
    }

    private Path pemKey() throws Exception {
        String pem = TestCertificates.privateKeyPem(identity.getPrivateKey());
        return writeText("server.key", pem);
    }

    private static Map<String, String> sni() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("localhost", "srv");
        return m;
    }

    @Test
    public void tcpLoadsCredentialsFromPemFiles() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setCertFile(pemCert());
        f.setKeyFile(pemKey());
        f.setSecure(true);
        f.start();
        assertNotNull(f.getServerCredentials());
        assertNull(f.getServerCredentialsResolver());
    }

    @Test
    public void tcpLoadsCredentialsFromKeystore() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setKeystoreFile(writeStore("s.p12", "srv"));
        f.setKeystorePass(PW);
        f.setKeystoreFormat(KeystoreFormat.PKCS12);
        f.start();
        assertNotNull(f.getServerCredentials());
        assertNull(f.getServerCredentialsResolver());
    }

    @Test
    public void tcpWithSniBuildsAResolverInsteadOfFixedCredentials() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setKeystoreFile(writeStore("sni.p12", "srv"));
        f.setKeystorePass(PW);
        f.setKeystoreFormat(KeystoreFormat.PKCS12);
        f.setSniHostnames(sni());
        f.setSniDefaultAlias("srv");
        f.start();
        assertNull(f.getServerCredentials());
        assertNotNull(f.getServerCredentialsResolver());
    }

    @Test
    public void tcpExplicitCredentialsAreNotReplacedByFiles() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setServerCredentials(identity.credentials());
        f.setCertFile(pemCert());
        f.setKeyFile(pemKey());
        f.start();
        assertEquals(identity.credentials().getClass(), f.getServerCredentials().getClass());
    }

    @Test
    public void tcpTruststoreAndPinBuildTheEffectiveTrustManager() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setTruststoreFile(writeTrustStore("t.p12"));
        f.setTruststorePass(PW);
        f.setPinnedCertFingerprint("SHA-256:00");
        f.start();
        assertNotNull(f.buildClientConfig("localhost"));
    }

    @Test
    public void tcpTruststoreWithoutPinUsesTheStoreDirectly() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setTruststoreFile(writeTrustStore("t2.p12"));
        f.setTruststorePass(PW);
        f.setTlsVersion(TlsVersion.TLS_1_3);
        f.start();
        assertNotNull(f.buildClientConfig("localhost"));
    }

    @Test
    public void tcpWrongKeystorePasswordFailsStart() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.setKeystoreFile(writeStore("bad.p12", "srv"));
        f.setKeystorePass("wrong");
        try {
            f.start();
            org.junit.Assert.fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void tcpCipherSuiteAndGroupListsSkipBlanksAndUnknownNames() {
        TlsVersion[] versions = new TlsVersion[] {
            TlsVersion.NEGOTIATE, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            TcpTransportFactory f = new TcpTransportFactory();
            f.setTlsVersion(versions[i]);
            f.setTrustManager(TestCertificates.trustAll());
            f.setCipherSuites(":TLS_AES_256_GCM_SHA384: :NOPE:TLS_CHACHA20_POLY1305_SHA256");
            f.setNamedGroups("secp384r1: :nope:X25519");
            f.start();
            assertNotNull(f.buildClientConfig("localhost"));
        }
        TcpTransportFactory empty = new TcpTransportFactory();
        empty.setCipherSuites("");
        empty.setNamedGroups("");
        empty.setTrustManager(TestCertificates.trustAll());
        empty.start();
        TcpTransportFactory onlyBlank = new TcpTransportFactory();
        onlyBlank.setCipherSuites(" : ");
        onlyBlank.setNamedGroups(" : ");
        onlyBlank.setTlsVersion(TlsVersion.TLS_1_3);
        onlyBlank.setTrustManager(TestCertificates.trustAll());
        onlyBlank.start();
    }

    @Test
    public void udpLoadsCredentialsFromPemAndBuildsServerConfigs() throws Exception {
        DtlsVersion[] versions = new DtlsVersion[] {
            DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            UdpTransportFactory f = new UdpTransportFactory();
            f.setSecure(true);
            f.setDtlsVersion(versions[i]);
            f.setCertFile(pemCert());
            f.setKeyFile(pemKey());
            f.setNamedGroups("secp256r1");
            f.start();
            if (versions[i] == DtlsVersion.DTLS_1_3) {
                assertNotNull(f.getSharedServerConfig13());
                assertNull(f.getSharedServerConfig());
            } else if (versions[i] == DtlsVersion.DTLS_1_2) {
                assertNotNull(f.getSharedServerConfig());
                assertNull(f.getSharedServerConfig13());
            } else {
                assertNotNull(f.getSharedServerConfig());
                assertNotNull(f.getSharedServerConfig13());
            }
        }
    }

    @Test
    public void udpLoadsCredentialsFromKeystoreWithAndWithoutSni() throws Exception {
        UdpTransportFactory plain = new UdpTransportFactory();
        plain.setSecure(true);
        plain.setKeystoreFile(writeStore("u.p12", "srv"));
        plain.setKeystorePass(PW);
        plain.setKeystoreFormat(KeystoreFormat.PKCS12);
        plain.start();
        assertNotNull(plain.getSharedServerConfig());

        UdpTransportFactory sniFactory = new UdpTransportFactory();
        sniFactory.setSecure(true);
        sniFactory.setKeystoreFile(writeStore("usni.p12", "srv"));
        sniFactory.setKeystorePass(PW);
        sniFactory.setKeystoreFormat(KeystoreFormat.PKCS12);
        sniFactory.setSniHostnames(sni());
        sniFactory.setSniDefaultAlias("srv");
        sniFactory.start();
        assertTrue(sniFactory.isSNIEnabled());
        assertNotNull(sniFactory.getSharedServerConfig());
    }

    @Test
    public void udpTruststoreAndPinBuildClientConfigs() throws Exception {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setTruststoreFile(writeTrustStore("ut.p12"));
        f.setTruststorePass(PW);
        f.setPinnedCertFingerprint("SHA-256:00");
        f.start();
        assertNotNull(f.buildClientConfig12("localhost"));
        assertNotNull(f.buildClientConfig13("localhost"));

        UdpTransportFactory unpinned = new UdpTransportFactory();
        unpinned.setTruststoreFile(writeTrustStore("ut2.p12"));
        unpinned.setTruststorePass(PW);
        unpinned.setClientCredentials(identity.credentials());
        unpinned.setDtlsVersion(DtlsVersion.NEGOTIATE);
        unpinned.start();
        assertNotNull(unpinned.buildClientConfig12("localhost"));
        assertNotNull(unpinned.buildClientConfig13("localhost"));
    }

    @Test
    public void udpDtls12IgnoresNamedGroupsAndListsSkipUnknownNames() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setDtlsVersion(DtlsVersion.DTLS_1_2);
        f.setTrustManager(TestCertificates.trustAll());
        f.setNamedGroups("secp256r1: :nope");
        f.setCipherSuites(":TLS_AES_128_GCM_SHA256: :NOPE");
        f.start();
        UdpTransportFactory blank = new UdpTransportFactory();
        blank.setTrustManager(TestCertificates.trustAll());
        blank.setNamedGroups(" ");
        blank.setCipherSuites(" : ");
        blank.start();
        assertFalse(blank.isSNIEnabled());
    }

    @Test
    public void clientConfigFallsBackToTheJvmTrustStore() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.start();
        assertNotNull(f.buildClientConfig("localhost"));
    }
}
