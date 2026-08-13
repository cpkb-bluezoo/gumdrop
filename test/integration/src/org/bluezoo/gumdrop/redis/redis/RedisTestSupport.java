/*
 * RedisTestSupport.java
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

package org.bluezoo.gumdrop.redis.redis;

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
 * Connection settings and helpers shared by the {@code redis.redis}
 * integration tests, which exercise the real Redis client ({@code
 * org.bluezoo.gumdrop.redis.client}) against a real Redis instance --
 * not run in CI, only locally against a container you already have
 * running. See {@link org.bluezoo.gumdrop.smtp.postfix.PostfixTestSupport}
 * for the sibling of this class and the shared rationale.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match a Redis instance
 * built and started from {@code test/integration/docker/redis} as:
 * <pre>{@code
 * podman build -t gumdrop-redis-test -f test/integration/docker/redis/Dockerfile \
 *     test/integration/docker/redis
 * podman run -d --name redis-test -p 16379:6379 -p 16380:6380 \
 *     gumdrop-redis-test
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RedisTestSupport {

    static final String HOST = System.getProperty("redis.test.host", "127.0.0.1");
    static final int PORT = Integer.getInteger("redis.test.port", 16379);
    static final int TLS_PORT = Integer.getInteger("redis.test.tls.port", 16380);
    static final String PASSWORD = System.getProperty("redis.test.password", "testpass");

    static final String CONTAINER_NAME = System.getProperty("redis.test.container", "redis-test");
    static final String ENGINE = System.getProperty("redis.test.engine", "podman");

    private static final int PROBE_TIMEOUT_MS = 500;

    private RedisTestSupport() {
    }

    static final String NOT_REACHABLE_MESSAGE =
            "no Redis test instance reachable at " + HOST + ":" + PORT
                    + " -- build and start the container locally to run these tests"
                    + " (see RedisTestSupport's class Javadoc)";

    static boolean isReachable() {
        return isReachable(PORT) && isReachable(TLS_PORT);
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
        String pem = exec("cat", "/tls/cert.pem");
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
