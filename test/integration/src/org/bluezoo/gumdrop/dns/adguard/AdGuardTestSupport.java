/*
 * AdGuardTestSupport.java
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

package org.bluezoo.gumdrop.dns.adguard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Connection settings and helpers shared by the {@code dns.adguard}
 * integration tests, which exercise the real DNS client ({@code
 * org.bluezoo.gumdrop.dns.client}) against a real AdGuard Home instance
 * -- not run in CI, only locally against a container you already have
 * running. See {@link org.bluezoo.gumdrop.smtp.postfix.PostfixTestSupport}
 * for the sibling of this class and the shared rationale.
 *
 * <p>AdGuard Home was chosen specifically because it supports every
 * wire format gumdrop's DNS client does -- plain UDP/TCP, DoT, DoH, and
 * DoQ -- from a single instance, so the whole transport matrix is
 * tested against one real, independent, free-software (GPL-3.0)
 * implementation rather than needing several different servers or
 * relying on live public resolvers (which would add external-network
 * flakiness/rate-limiting for no real gain in "independence" -- AdGuard
 * Home already is a wholly separate implementation from gumdrop).
 *
 * <p>The test domain {@code resolvethis.gumdrop.test} is a static
 * rewrite configured in AdGuardHome.yaml resolving to 203.0.113.55 (an
 * RFC 5737 documentation address, never real/routable) -- tests assert
 * that exact answer rather than depending on real internet name
 * resolution.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match an instance built and
 * started from {@code test/integration/docker/dns} as:
 * <pre>{@code
 * podman build -t gumdrop-dns-test -f test/integration/docker/dns/Dockerfile \
 *     test/integration/docker/dns
 * podman run -d --name dns-test \
 *     -p 15353:53/udp -p 15353:53/tcp \
 *     -p 18443:443/tcp -p 18853:853/tcp -p 18853:853/udp \
 *     gumdrop-dns-test
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AdGuardTestSupport {

    static final String HOST = System.getProperty("dns.test.host", "127.0.0.1");
    static final int PLAIN_PORT = Integer.getInteger("dns.test.plain.port", 15353);
    static final int DOH_PORT = Integer.getInteger("dns.test.doh.port", 18443);
    static final int DOT_DOQ_PORT = Integer.getInteger("dns.test.dot.doq.port", 18853);

    static final String TEST_HOSTNAME = "resolvethis.gumdrop.test";
    static final String TEST_ANSWER = "203.0.113.55";

    static final String CONTAINER_NAME = System.getProperty("dns.test.container", "dns-test");
    static final String ENGINE = System.getProperty("dns.test.engine", "podman");

    private static final int PROBE_TIMEOUT_MS = 500;

    private AdGuardTestSupport() {
    }

    static final String NOT_REACHABLE_MESSAGE =
            "no AdGuard Home test instance reachable at " + HOST + ":" + PLAIN_PORT
                    + " -- build and start the container locally to run these tests"
                    + " (see AdGuardTestSupport's class Javadoc)";

    static boolean isReachable() {
        return isReachable(PLAIN_PORT) && isReachable(DOH_PORT) && isReachable(DOT_DOQ_PORT);
    }

    private static boolean isReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Fetches and parses the server's self-signed TLS certificate
     * directly from the running container -- generated fresh at image
     * build time (see the Dockerfile), so there is no static file to
     * ship or keep in sync. See the identical rationale in {@code
     * PostfixTestSupport#loadServerCertificate}.
     */
    static X509Certificate loadServerCertificate() throws IOException, InterruptedException, CertificateException {
        String pem = exec("cat", "/opt/adguardhome/certs/cert.pem");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (java.io.InputStream in =
                new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            Certificate cert = factory.generateCertificate(in);
            return (X509Certificate) cert;
        }
    }

    /**
     * Writes the server's certificate to a temporary PEM file, for use
     * with {@code DoQClientTransport#setCaFile}, which takes a file
     * path (the QUIC transport's TLS trust configuration is native/
     * quiche-backed, not a Java {@code X509TrustManager}).
     */
    static Path writeServerCertificatePemFile() throws IOException, InterruptedException {
        String pem = exec("cat", "/opt/adguardhome/certs/cert.pem");
        Path file = Files.createTempFile("gumdrop-dns-test-cert", ".pem");
        file.toFile().deleteOnExit();
        Files.writeString(file, pem);
        return file;
    }

    /**
     * Computes the RFC 7469-style SPKI SHA-256 pin (colon-separated
     * lowercase hex) of the server's certificate, for use with {@code
     * TCPDNSClientTransport#setPinnedSPKIFingerprints}.
     */
    static String computeSpkiSha256Pin(X509Certificate cert) throws Exception {
        byte[] spki = cert.getPublicKey().getEncoded();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
        StringBuilder sb = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", digest[i] & 0xff));
        }
        return sb.toString();
    }

    private static String exec(String... containerCommand) throws IOException, InterruptedException {
        String[] full = new String[3 + containerCommand.length];
        full[0] = ENGINE;
        full[1] = "exec";
        full[2] = CONTAINER_NAME;
        System.arraycopy(containerCommand, 0, full, 3, containerCommand.length);

        Process process = new ProcessBuilder(full).redirectErrorStream(false).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.io.InputStream in = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(ENGINE + " exec " + String.join(" ", containerCommand)
                    + " exited " + exitCode);
        }
        return out.toString("UTF-8");
    }
}
