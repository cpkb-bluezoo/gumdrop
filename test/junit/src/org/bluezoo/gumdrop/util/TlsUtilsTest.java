/*
 * TlsUtilsTest.java
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link TlsUtils} keystore loading, caching and
 * credential extraction using in-memory generated key material.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsUtilsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String PW = "changeit";

    private Path root;

    @Before
    public void setUp() {
        MemoryFileSystem fs = MemoryFileSystem.create();
        root = fs.getPath("/");
    }

    private Path store(String name, String alias) throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("tls-utils-" + name);
        KeyStore ks = TestCertificates.keyStore(id, alias, PASSWORD);
        Path file = root.resolve(name);
        OutputStream out = Files.newOutputStream(file);
        try {
            ks.store(out, PASSWORD);
        } finally {
            out.close();
        }
        return file;
    }

    @Test
    public void loadKeyStoreCachesByPathAndMtime() throws Exception {
        Path p = store("cache.p12", "one");
        KeyStore a = TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
        KeyStore b = TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
        assertSame(a, b);
        assertTrue(a.containsAlias("one"));
    }

    @Test
    public void loadKeyStoreReloadsWhenFileModified() throws Exception {
        Path p = store("reload.p12", "one");
        KeyStore a = TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
        Files.setLastModifiedTime(p, FileTime.fromMillis(1000000000000L));
        KeyStore b = TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
        assertNotSame(a, b);
    }

    @Test
    public void reloadEvictsDerivedManagerCaches() throws Exception {
        Path p = store("evict.p12", "one");
        KeyManager[] k1 = TlsUtils.loadKeyManagers(p, PW, KeystoreFormat.PKCS12);
        TrustManager[] t1 = TlsUtils.loadTrustManagers(p, PW, KeystoreFormat.PKCS12);
        KeyManager[] k1b = TlsUtils.loadKeyManagers(p, PW, KeystoreFormat.PKCS12);
        TrustManager[] t1b = TlsUtils.loadTrustManagers(p, PW, KeystoreFormat.PKCS12);
        assertSame(k1, k1b);
        assertSame(t1, t1b);
        assertNotNull(k1);
        assertTrue(k1.length > 0);
        assertTrue(t1.length > 0);
        Files.setLastModifiedTime(p, FileTime.fromMillis(1100000000000L));
        KeyStore reloaded = TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
        assertNotNull(reloaded);
        KeyManager[] k2 = TlsUtils.loadKeyManagers(p, PW, KeystoreFormat.PKCS12);
        TrustManager[] t2 = TlsUtils.loadTrustManagers(p, PW, KeystoreFormat.PKCS12);
        assertNotSame(k1, k2);
        assertNotSame(t1, t2);
    }

    @Test
    public void loadServerCredentialsFirstKeyEntry() throws Exception {
        Path p = store("creds.p12", "first");
        ServerCredentials c = TlsUtils.loadServerCredentials(p, PW, KeystoreFormat.PKCS12);
        assertNotNull(c);
    }

    @Test
    public void loadServerCredentialsByAlias() throws Exception {
        Path p = store("alias.p12", "named");
        ServerCredentials c = TlsUtils.loadServerCredentials(p, PW, KeystoreFormat.PKCS12, "named");
        assertNotNull(c);
    }

    @Test
    public void unknownAliasHasNoChain() throws Exception {
        Path p = store("missing.p12", "named");
        try {
            TlsUtils.loadServerCredentials(p, PW, KeystoreFormat.PKCS12, "other");
            fail("expected GeneralSecurityException");
        } catch (GeneralSecurityException e) {
            String m = e.getMessage();
            assertTrue(m.contains("other"));
        }
    }

    @Test
    public void emptyKeyStoreHasNoPrivateKeyEntry() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        try {
            TlsUtils.loadServerCredentials(ks, PW, null);
            fail("expected GeneralSecurityException");
        } catch (GeneralSecurityException e) {
            assertEquals("No private key entry found in keystore", e.getMessage());
        }
    }

    @Test
    public void trustOnlyKeyStoreSkipsCertificateEntries() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("trust-only");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", id.getCertificate());
        try {
            TlsUtils.loadServerCredentials(ks, PW, null);
            fail("expected GeneralSecurityException");
        } catch (GeneralSecurityException e) {
            assertTrue(e.getMessage().startsWith("No private key"));
        }
    }

    @Test
    public void firstPrivateKeyAliasSkipsCertificateEntries() throws Exception {
        TestCertificates.Identity id = TestCertificates.newEc256("mixed");
        KeyStore ks = TestCertificates.keyStore(id, "key", PASSWORD);
        ks.setCertificateEntry("a-cert-first", id.getCertificate());
        ServerCredentials c = TlsUtils.loadServerCredentials(ks, PW, null);
        assertNotNull(c);
    }

    @Test
    public void missingFileIsIoError() throws Exception {
        Path p = root.resolve("absent.p12");
        try {
            TlsUtils.loadKeyStore(p, PW, KeystoreFormat.PKCS12);
            fail("expected IOException");
        } catch (NoSuchFileException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void wrongPasswordFails() throws Exception {
        Path p = store("badpw.p12", "one");
        try {
            TlsUtils.loadKeyStore(p, "wrong", KeystoreFormat.PKCS12);
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }
}
