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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.quic.tls.PemCredentials;
import org.junit.Assume;

/**
 * The PEM files that integration tests use as their TLS material:
 * {@code key.pem} and {@code cert.pem} (the server identity) and
 * {@code ca.pem} (the trust anchor for clients).
 *
 * <p>They are made by {@code ant tls-certs}, which the integration targets
 * run first (see {@code ant/tls.xml}), into the directory named by the
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
    private static final String DEFAULT_DIRECTORY = "test/integration/certs/pem";

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
}
