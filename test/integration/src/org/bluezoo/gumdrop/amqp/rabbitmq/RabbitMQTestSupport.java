/*
 * RabbitMQTestSupport.java
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

package org.bluezoo.gumdrop.amqp.rabbitmq;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connection settings and helpers shared by the {@code amqp.rabbitmq}
 * integration tests, which exercise the real AMQP client
 * ({@code org.bluezoo.gumdrop.amqp.client}) against a real RabbitMQ
 * broker rather than {@code FakeAMQPBroker} -- not run in CI (there is no
 * broker there), only locally against a broker you already have running.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match a RabbitMQ broker
 * started as:
 * <pre>{@code
 * podman run -d --name rabbitmq \
 *     -p 5672:5672 -p 5671:5671 -p 15672:15672 \
 *     -v ~/.hopf-rabbitmq-tls:/etc/rabbitmq/tls:ro \
 *     -v ~/.hopf-rabbitmq-tls/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro \
 *     rabbitmq:4-management
 * }</pre>
 * with {@code rabbitmq.conf} enabling TLS on 5671 with
 * {@code ssl_options.verify = verify_none} (server-side TLS only, no
 * client certificate required).
 *
 * <p>Every test class here probes broker reachability in
 * {@code @BeforeClass} and {@code Assume.assumeTrue}s it, so the whole
 * suite is cleanly <em>skipped</em>, not failed, when the broker isn't
 * running -- see {@link #isPlaintextReachable} /
 * {@link #isTlsReachable}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RabbitMQTestSupport {

    static final String HOST = System.getProperty("rabbitmq.test.host", "localhost");
    static final int PLAINTEXT_PORT =
            Integer.getInteger("rabbitmq.test.port", 5672);
    static final int TLS_PORT =
            Integer.getInteger("rabbitmq.test.tls.port", 5671);
    static final int MANAGEMENT_PORT =
            Integer.getInteger("rabbitmq.test.management.port", 15672);
    static final String VHOST = System.getProperty("rabbitmq.test.vhost", "/");
    static final String USERNAME = System.getProperty("rabbitmq.test.user", "guest");
    static final String PASSWORD = System.getProperty("rabbitmq.test.password", "guest");

    static final Path CA_CERT_FILE = Paths.get(System.getProperty("rabbitmq.test.tls.cafile",
            System.getProperty("user.home") + "/.hopf-rabbitmq-tls/ca-cert.pem"));

    private static final int PROBE_TIMEOUT_MS = 500;

    private RabbitMQTestSupport() {
    }

    /** Skip reason used by every test class's {@code @BeforeClass} probe. */
    static final String NOT_REACHABLE_MESSAGE =
            "no RabbitMQ broker reachable at " + HOST + ":" + PLAINTEXT_PORT
                    + " -- start one locally to run these tests"
                    + " (see RabbitMQTestSupport's class Javadoc for the podman command)";

    static boolean isPlaintextReachable() {
        return isReachable(PLAINTEXT_PORT);
    }

    static boolean isTlsReachable() {
        return isReachable(TLS_PORT) && java.nio.file.Files.isReadable(CA_CERT_FILE);
    }

    private static boolean isReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Management API (issue: needed to force-close a live connection
    // from outside the client under test, to exercise real-broker
    // recovery -- FakeAMQPBroker can just drop its socket, but there is
    // no equivalent hook on a real, already-running RabbitMQ) ──

    private static final Pattern CONNECTION_NAME_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Force-closes every currently open AMQP connection on the broker,
     * via the management HTTP API ({@code DELETE
     * /api/connections/{name}}). Used to simulate an unexpected network
     * drop against a real broker, the same way {@code
     * FakeAMQPBroker.disconnectAll()} does against the fake one.
     *
     * <p>Simple rather than targeted (closes every connection, not just
     * the one under test): fine for a dedicated local test broker with
     * nothing else connected to it, which is the only environment these
     * tests are meant to run in.
     */
    static void forceCloseAllConnections() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newHttpClient();
        String auth = Base64.getEncoder().encodeToString(
                (USERNAME + ":" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // The management plugin's connection list is populated from a
        // periodically-collected stats snapshot, not read live off the
        // connection supervisor -- a connection that has been open and
        // actively used for well under a second (as in these tests, which
        // call this immediately after the initial connect/declare/consume
        // completes) can still be invisible to /api/connections for a
        // few seconds. Poll rather than assume the first listing is
        // authoritative. Deliberately stops at the first non-empty listing
        // (rather than continuing to poll/delete for the whole window):
        // a client with a short reconnect delay (as these tests configure)
        // can already be back up by the next poll tick, and continuing to
        // force-close every connection found during the whole window would
        // just keep killing each reconnect attempt in turn, so the client
        // never gets a stable moment to finish recovering.
        List<String> names = java.util.Collections.emptyList();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (names.isEmpty() && System.currentTimeMillis() < deadline) {
            HttpRequest listRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + HOST + ":" + MANAGEMENT_PORT + "/api/connections"))
                    .header("Authorization", "Basic " + auth)
                    .GET()
                    .build();
            HttpResponse<String> listResponse = http.send(listRequest, HttpResponse.BodyHandlers.ofString());
            if (listResponse.statusCode() != 200) {
                throw new IOException("management API /api/connections returned "
                        + listResponse.statusCode() + ": " + listResponse.body());
            }
            names = extractConnectionNames(listResponse.body());
            if (names.isEmpty()) {
                Thread.sleep(250L);
            }
        }
        if (names.isEmpty()) {
            throw new IOException("management API never reported any open connections"
                    + " to force-close within the poll deadline");
        }

        for (String name : names) {
            // URLEncoder is form ("application/x-www-form-urlencoded")
            // encoding: it encodes a space as '+', which is only valid in a
            // query string, not in a URL *path* segment. Connection names
            // here look like "1.2.3.4:5678 -> 1.2.3.4:5672" -- spaces and
            // all -- so encoding them with URLEncoder and splicing the
            // result into the path leaves literal '+' characters that
            // RabbitMQ's HTTP API does not decode back to spaces, so it
            // 404s on a name that doesn't match any real connection. That
            // 404 was already treated as harmless ("already closed"), so
            // this failed silently: the real, live connection was never
            // actually closed.
            String encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                    .replace("+", "%20");
            HttpRequest deleteRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + HOST + ":" + MANAGEMENT_PORT
                            + "/api/connections/" + encodedName))
                    .header("Authorization", "Basic " + auth)
                    .DELETE()
                    .build();
            // 204 on success; a 404 here just means the connection already
            // closed on its own between listing and deleting -- not an error.
            http.send(deleteRequest, HttpResponse.BodyHandlers.discarding());
        }
    }

    /**
     * Extracts every {@code "name"} field from the management API's
     * JSON connection list. A regex rather than a JSON parser: the
     * gumdrop codebase avoids {@code java.util.regex} in main source
     * (CONTRIBUTING.md), but this is test-only tooling parsing a small,
     * well-known, flat JSON shape -- not worth pulling in Gonzalez for.
     */
    private static List<String> extractConnectionNames(String json) {
        List<String> names = new java.util.ArrayList<String>();
        Matcher matcher = CONNECTION_NAME_PATTERN.matcher(json);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
