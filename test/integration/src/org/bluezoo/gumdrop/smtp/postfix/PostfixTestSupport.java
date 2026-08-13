/*
 * PostfixTestSupport.java
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

package org.bluezoo.gumdrop.smtp.postfix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Connection settings and helpers shared by the {@code smtp.postfix}
 * integration tests, which exercise the real SMTP client
 * ({@code org.bluezoo.gumdrop.smtp.client}) against a real Postfix
 * instance rather than gumdrop's own SMTP server -- not run in CI (there
 * is no broker there), only locally against a container you already have
 * running.
 *
 * <p>Unlike {@code RabbitMQTestSupport}, there is no management API to
 * inspect delivered mail, so this shells out to {@code podman exec} (or
 * {@code docker exec}, see {@link #ENGINE}) to read/clear the mbox file
 * and to fetch the server's TLS certificate -- no volume mount or
 * generated host-side file required, just the container running.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match a Postfix instance
 * built and started from {@code test/integration/docker/postfix} as:
 * <pre>{@code
 * podman build -t gumdrop-postfix-test -f test/integration/docker/postfix/Dockerfile \
 *     test/integration/docker/postfix
 * podman run -d --name postfix-test -p 12525:25 gumdrop-postfix-test
 * }</pre>
 *
 * <p>Every test class here probes reachability in {@code @Before} and
 * {@code Assume.assumeTrue}s it, so the whole suite is cleanly
 * <em>skipped</em>, not failed, when the container isn't running -- see
 * {@link #isReachable}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class PostfixTestSupport {

    static final String HOST = System.getProperty("postfix.test.host", "127.0.0.1");
    static final int PORT = Integer.getInteger("postfix.test.port", 12525);
    static final String MAILBOX_USER = System.getProperty("postfix.test.user", "testuser");
    static final String MAIL_DOMAIN = System.getProperty("postfix.test.domain", "test.gumdrop.local");
    static final String CONTAINER_NAME = System.getProperty("postfix.test.container", "postfix-test");

    /** Container engine command -- podman by default, override for docker setups. */
    static final String ENGINE = System.getProperty("postfix.test.engine", "podman");

    private static final int PROBE_TIMEOUT_MS = 500;

    private PostfixTestSupport() {
    }

    static final String NOT_REACHABLE_MESSAGE =
            "no Postfix test instance reachable at " + HOST + ":" + PORT
                    + " -- build and start the container locally to run these tests"
                    + " (see PostfixTestSupport's class Javadoc)";

    static boolean isReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Empties the mbox spool file so each test starts from a clean slate,
     * without needing per-test unique recipient addresses.
     */
    static void clearMailbox() throws IOException, InterruptedException {
        exec("sh", "-c", "test -f /var/mail/" + MAILBOX_USER + " && : > /var/mail/" + MAILBOX_USER + " || true");
    }

    /**
     * Polls the mbox spool file until it is non-empty (delivery is local
     * and near-instant, but not synchronous with the client's DATA/BDAT
     * completion callback -- postfix's local(8) agent still has to pick
     * the message off its queue) and returns its full contents.
     */
    static String awaitMailbox(long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = exec("cat", "/var/mail/" + MAILBOX_USER);
            if (!content.isEmpty()) {
                return content;
            }
            Thread.sleep(100L);
        }
        return content;
    }

    /**
     * Fetches and parses the server's self-signed TLS certificate
     * directly from the running container -- generated fresh at image
     * build time (see the Dockerfile), so there is no static file to
     * ship or keep in sync.
     */
    static X509Certificate loadServerCertificate() throws IOException, InterruptedException, CertificateException {
        String pem = exec("cat", "/etc/postfix/tls/cert.pem");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            Certificate cert = factory.generateCertificate(in);
            return (X509Certificate) cert;
        }
    }

    private static String exec(String... containerCommand) throws IOException, InterruptedException {
        String[] full = new String[3 + containerCommand.length];
        full[0] = ENGINE;
        full[1] = "exec";
        full[2] = CONTAINER_NAME;
        System.arraycopy(containerCommand, 0, full, 3, containerCommand.length);

        Process process = new ProcessBuilder(full).redirectErrorStream(false).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
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
