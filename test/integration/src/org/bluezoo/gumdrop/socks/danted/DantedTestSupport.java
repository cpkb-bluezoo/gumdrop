/*
 * DantedTestSupport.java
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

package org.bluezoo.gumdrop.socks.danted;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Connection settings and helpers shared by the {@code socks.danted}
 * integration tests, which exercise the real SOCKS client
 * ({@code org.bluezoo.gumdrop.socks.client}) against a real Dante
 * ({@code danted}) SOCKS5 proxy -- not run in CI, only locally against
 * containers you already have running. See {@link
 * org.bluezoo.gumdrop.smtp.postfix.PostfixTestSupport} for the sibling
 * of this class and the shared rationale.
 *
 * <p>Unlike the Postfix/vsftpd tests, the point under test here is the
 * SOCKS tunnel itself, not the protocol running over it -- so this
 * tunnels a real SMTP transaction through the proxy to the same Postfix
 * container the {@code smtp.postfix} tests use, and confirms delivery
 * the same way ({@code podman exec ... cat /var/mail/testuser}). A
 * message that only arrives via the tunnel (the destination host,
 * {@code postfix-test}, is a container-network name that does not
 * resolve from this JVM directly, only from inside the proxy container)
 * is solid evidence the CONNECT actually worked, not just that the
 * handshake bytes looked right.
 *
 * <p>Two separate proxy instances are used because Dante selects an
 * auth method by priority among whatever the client offers, and
 * gumdrop's {@code SOCKSClientHandler} always offers "none" alongside
 * "username" when credentials are configured -- a single proxy
 * offering both would never actually exercise the username/password
 * (RFC 1929) wire path. See {@code sockd-auth.conf}'s comment.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match containers built and
 * started from {@code test/integration/docker/socks} and {@code
 * test/integration/docker/postfix}, all joined to the same podman
 * network, as:
 * <pre>{@code
 * podman network create gumdrop-test-net
 * podman network connect gumdrop-test-net postfix-test
 *
 * podman build -t gumdrop-socks-test -f test/integration/docker/socks/Dockerfile \
 *     test/integration/docker/socks
 * podman run -d --name socks-test --network gumdrop-test-net -p 11080:1080 \
 *     gumdrop-socks-test
 * podman run -d --name socks-auth-test --network gumdrop-test-net -p 11081:1080 \
 *     -e SOCKS_MODE=auth gumdrop-socks-test
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DantedTestSupport {

    static final String PROXY_HOST = System.getProperty("socks.test.host", "127.0.0.1");
    static final int PROXY_PORT = Integer.getInteger("socks.test.port", 11080);
    static final int PROXY_AUTH_PORT = Integer.getInteger("socks.test.auth.port", 11081);

    static final String AUTH_USERNAME = System.getProperty("socks.test.user", "socksuser");
    static final String AUTH_PASSWORD = System.getProperty("socks.test.password", "sockspass");

    /** Destination reachable only via the proxy's podman-network DNS, not from this JVM directly. */
    static final String DEST_HOST = System.getProperty("socks.test.dest.host", "postfix-test");
    static final int DEST_PORT = Integer.getInteger("socks.test.dest.port", 25);

    static final String MAILBOX_USER = System.getProperty("socks.test.dest.mailboxuser", "testuser");
    static final String MAIL_DOMAIN = System.getProperty("socks.test.dest.domain", "test.gumdrop.local");
    static final String DEST_CONTAINER = System.getProperty("socks.test.dest.container", "postfix-test");

    static final String ENGINE = System.getProperty("socks.test.engine", "podman");

    private static final int PROBE_TIMEOUT_MS = 500;

    private DantedTestSupport() {
    }

    static final String NOT_REACHABLE_MESSAGE =
            "no SOCKS test proxies reachable at " + PROXY_HOST + ":" + PROXY_PORT
                    + "/" + PROXY_AUTH_PORT
                    + " -- build and start the containers locally to run these tests"
                    + " (see DantedTestSupport's class Javadoc)";

    /**
     * Checks both proxy instances only -- not the destination Postfix
     * container, which the tests never connect to directly (only the
     * proxy does, over the podman network). If Postfix isn't up, the
     * CONNECT itself fails with a clear error instead of a silent skip.
     */
    static boolean isReachable() {
        return isReachable(PROXY_PORT) && isReachable(PROXY_AUTH_PORT);
    }

    private static boolean isReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(PROXY_HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void clearMailbox() throws IOException, InterruptedException {
        exec("sh", "-c", "test -f /var/mail/" + MAILBOX_USER + " && : > /var/mail/" + MAILBOX_USER + " || true");
    }

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

    private static String exec(String... containerCommand) throws IOException, InterruptedException {
        String[] full = new String[3 + containerCommand.length];
        full[0] = ENGINE;
        full[1] = "exec";
        full[2] = DEST_CONTAINER;
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
