/*
 * TestTlsFiles.java
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.quic.tls.PemCredentials;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.Assume;

/**
 * Shared TLS fixtures under {@code etc/tls/} (see {@code ant tls-certs}).
 *
 * <p><strong>Roles on loopback tests.</strong> One-way TLS (typical HTTPS) uses
 * two different kinds of material, not two copies of the server certificate:
 * <ul>
 *   <li><strong>Server identity</strong> — {@code cert.pem} and {@code key.pem}
 *       (what the listener presents).</li>
 *   <li><strong>Client trust</strong> — {@code ca.pem} (what the client uses to
 *       validate the server). The client does not use {@code key.pem}.</li>
 * </ul>
 * Mutual TLS adds a separate <strong>client identity</strong> (its own cert and
 * key, issued by the same or another CA). Reusing the server's private key on
 * the client side is wrong even when verification is disabled.
 *
 * <p>They are made by {@code ant tls-certs}, which the integration targets
 * run first (see {@code ant/tls.xml}), into {@code etc/tls/} by default
 * (the same files as {@code ant tls-certs}), or the directory named by the
 * {@code gumdrop.test.tls.dir} system property. They need mkcert or openssl,
 * so a machine that has neither does not have them; tests that use this class
 * call {@link #assumeAvailable()} and are skipped there instead of failing.
 *
 * <p>The certificate is valid for {@code localhost}, {@code 127.0.0.1},
 * {@code ::1} and {@code test.gumdrop.local}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TestTlsFiles {

    /** A name the certificate is valid for, besides localhost. */
    public static final String SERVER_NAME = "test.gumdrop.local";

    private static final String DIRECTORY_PROPERTY = "gumdrop.test.tls.dir";
    /** Matches {@code tls.keystore.pass} in ant/tls.xml. */
    public static final String KEYSTORE_PASSWORD = "changeit";

    private static final String DEFAULT_DIRECTORY = "etc/tls";

    private TestTlsFiles() {
    }

    /** The directory holding the files. */
    public static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY));
    }

    public static Path keyFile() {
        return directory().resolve("key.pem");
    }

    public static Path certFile() {
        return directory().resolve("cert.pem");
    }

    public static Path caFile() {
        return directory().resolve("ca.pem");
    }

    public static Path keystoreFile() {
        return directory().resolve("keystore.p12");
    }

    public static Path truststoreFile() {
        return directory().resolve("truststore.p12");
    }

    public static boolean keystoreAvailable() {
        return Files.isRegularFile(keystoreFile());
    }

    public static void assumeKeystoreAvailable() {
        Assume.assumeTrue("PKCS#12 keystore not found at " + keystoreFile()
                + " (run \"ant tls-keystore\" after tls-certs)", keystoreAvailable());
    }

    /** Server identity for listeners: PEM from {@code cert.pem} / {@code key.pem}. */
    public static TlsConfig serverTlsConfig() {
        return TlsConfig.pem(certFile(), keyFile());
    }

    /** PKCS#12 built from the same PEM material by {@code ant tls-keystore}. */
    public static TlsConfig serverKeystoreTlsConfig() {
        return TlsConfig.keystore(keystoreFile(), KEYSTORE_PASSWORD);
    }

    /** Returns true if all three files exist. */
    public static boolean available() {
        return Files.isRegularFile(keyFile())
                && Files.isRegularFile(certFile())
                && Files.isRegularFile(caFile());
    }

    /**
     * Skips the calling test (or test class, from a {@code @BeforeClass}
     * method) unless the files exist.
     */
    public static void assumeAvailable() {
        Assume.assumeTrue("TLS files not found in " + directory()
                + " (run \"ant tls-certs\"; needs mkcert or openssl)", available());
    }

    /** The server certificate chain from {@code cert.pem}. */
    public static List<X509Certificate> certificateChain()
            throws IOException, GeneralSecurityException {
        return PemCredentials.loadCertificateChain(certFile());
    }

    /** The server private key from {@code key.pem}. */
    public static PrivateKey privateKey() throws IOException, GeneralSecurityException {
        return PemCredentials.loadPrivateKey(keyFile());
    }

    /** A trust manager that trusts the CA in {@code ca.pem}, and nothing else. */
    public static X509TrustManager trustManager()
            throws IOException, GeneralSecurityException {
        return PemCredentials.loadTrustManager(caFile());
    }

    /**
     * Builds a temporary JKS server keystore from the PEM identity (for tests
     * that must exercise {@code keystore-format="JKS"} loading).
     */
    public static Path writeTemporaryJksServerKeystore() throws IOException, GeneralSecurityException {
        PrivateKey key = privateKey();
        List<X509Certificate> chain = certificateChain();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        char[] pass = KEYSTORE_PASSWORD.toCharArray();
        ks.setKeyEntry("server", key, pass, chain.toArray(new X509Certificate[0]));
        Path tmp = Files.createTempFile("gumdrop-test-server-", ".jks");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ks.store(out, pass);
        }
        return tmp;
    }
}
