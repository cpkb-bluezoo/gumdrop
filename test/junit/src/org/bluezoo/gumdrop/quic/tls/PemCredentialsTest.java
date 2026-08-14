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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies {@link PemCredentials} against a real keytool-generated
 * certificate, exported to PEM form via {@code openssl} exactly as an
 * administrator deploying gumdrop would produce cert/key files.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PemCredentialsTest {

    private static Path certsDirectory;
    private static Path certFile;
    private static Path keyFile;

    @BeforeClass
    public static void generatePemFiles() throws Exception {
        certsDirectory = Files.createTempDirectory("pem-credentials-test");
        Path keystorePath = certsDirectory.resolve("server.p12");
        certFile = certsDirectory.resolve("cert.pem");
        keyFile = certsDirectory.resolve("key.pem");

        run(new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=test.gumdrop.local",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit"), "keytool");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nodes", "-nocerts", "-out", keyFile.toString(),
                "-passin", "pass:changeit"), "openssl (key export)");

        run(new ProcessBuilder(
                "openssl", "pkcs12", "-in", keystorePath.toString(),
                "-nokeys", "-out", certFile.toString(),
                "-passin", "pass:changeit"), "openssl (cert export)");
    }

    private static void run(ProcessBuilder pb, String toolName) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail(toolName + " failed to produce test PEM files");
        }
    }

    @AfterClass
    public static void deletePemFiles() throws IOException {
        if (certsDirectory == null) {
            return;
        }
        deleteContentsThenSelf(certsDirectory);
    }

    private static void deleteContentsThenSelf(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(directory);
    }

    @Test
    public void testLoadCertificateChain() throws Exception {
        List<X509Certificate> chain = PemCredentials.loadCertificateChain(certFile);
        assertEquals(1, chain.size());
        assertEquals("CN=test.gumdrop.local", chain.get(0).getSubjectX500Principal().getName());
    }

    @Test
    public void testLoadPrivateKey() throws Exception {
        PrivateKey key = PemCredentials.loadPrivateKey(keyFile);
        assertNotNull(key);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    public void testLoadServerEngineFactory() throws Exception {
        TlsServerEngineFactory factory = PemCredentials.loadServerEngineFactory(certFile, keyFile);
        assertNotNull(factory);
    }

    @Test
    public void testLoadPrivateKeyRejectsNonPemFile() throws Exception {
        Path notPem = certsDirectory.resolve("not-a-key.pem");
        Files.write(notPem, "this is not a PEM file".getBytes());
        try {
            PemCredentials.loadPrivateKey(notPem);
            fail("Expected IOException for a file with no PRIVATE KEY block");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PRIVATE KEY"));
        } finally {
            Files.deleteIfExists(notPem);
        }
    }
}
