/*
 * RabbitMQ4TestSupport.java
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

package org.bluezoo.gumdrop.amqp1.rabbitmq;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Connection settings and helpers shared by the {@code amqp1.rabbitmq}
 * integration tests, which exercise the AMQP 1.0 client
 * ({@code org.bluezoo.gumdrop.amqp1.client}) against a real RabbitMQ 4
 * broker, whose native AMQP 1.0 listener shares port 5672 (5671 for TLS)
 * with the AMQP 0-9-1 one. These are not run in CI (there is no broker
 * there), only locally against a broker you already have running, and are
 * skipped, not failed, when none is reachable.
 *
 * <p>All settings are overridable via the same system properties the
 * 0-9-1 tests use ({@code rabbitmq.test.*}), so one broker serves both;
 * the defaults match a RabbitMQ 4 broker started as:
 * <pre>{@code
 * podman run -d --name rabbitmq \
 *     -p 5672:5672 -p 5671:5671 -p 15672:15672 \
 *     -v ~/.hopf-rabbitmq-tls:/etc/rabbitmq/tls:ro \
 *     -v ~/.hopf-rabbitmq-tls/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro \
 *     rabbitmq:4-management
 * }</pre>
 * with {@code rabbitmq.conf} enabling TLS on 5671 with
 * {@code ssl_options.verify = verify_none} (server-side TLS only).
 *
 * <p><b>Addresses.</b> Node addresses are broker-specific. RabbitMQ 4 uses
 * "v2" addresses: a queue is {@code /queues/<name>}, with the name
 * percent-encoded. A queue must exist before a link can attach to it, so
 * the tests declare their queues through the management HTTP API and delete
 * them afterwards; see {@link #declareQueue} and {@link #queueAddress}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RabbitMQ4TestSupport {

    static final String HOST = System.getProperty("rabbitmq.test.host", "localhost");
    static final int PLAINTEXT_PORT = Integer.getInteger("rabbitmq.test.port", 5672);
    static final int TLS_PORT = Integer.getInteger("rabbitmq.test.tls.port", 5671);
    static final int MANAGEMENT_PORT = Integer.getInteger("rabbitmq.test.management.port", 15672);
    static final String VHOST = System.getProperty("rabbitmq.test.vhost", "/");
    static final String USERNAME = System.getProperty("rabbitmq.test.user", "guest");
    static final String PASSWORD = System.getProperty("rabbitmq.test.password", "guest");

    static final Path CA_CERT_FILE = Paths.get(System.getProperty("rabbitmq.test.tls.cafile",
            System.getProperty("user.home") + "/.hopf-rabbitmq-tls/ca-cert.pem"));

    private static final int PROBE_TIMEOUT_MS = 500;

    /** Skip reason used by every test class's probe. */
    static final String NOT_REACHABLE_MESSAGE =
            "no RabbitMQ 4 broker reachable at " + HOST + ":" + PLAINTEXT_PORT
                    + " (or its management API on " + MANAGEMENT_PORT + ")"
                    + " -- start one locally to run these tests"
                    + " (see RabbitMQ4TestSupport's class Javadoc for the podman command)";

    private RabbitMQ4TestSupport() {
    }

    /** True if the broker and its management API are both reachable. */
    static boolean isPlaintextReachable() {
        return isReachable(PLAINTEXT_PORT) && isReachable(MANAGEMENT_PORT);
    }

    static boolean isTlsReachable() {
        return isPlaintextReachable() && isReachable(TLS_PORT) && Files.isReadable(CA_CERT_FILE);
    }

    private static boolean isReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── threading ──

    /**
     * Runs {@code task} on the client's event loop and waits for it. The
     * client's links are not thread-safe, so a test thread must not call them
     * directly; this is the supported way to do so from another thread.
     */
    static void onLoop(org.bluezoo.gumdrop.amqp1.client.Amqp1ClientRecovery client,
            final Runnable task) throws InterruptedException {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        client.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        if (!done.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting for a task to run on the event loop");
        }
        if (failure.get() != null) {
            throw new AssertionError("task failed on the event loop: " + failure.get(), failure.get());
        }
    }

    // ── addresses and queues ──

    /** The RabbitMQ 4 AMQP 1.0 (v2) address of a queue. */
    static String queueAddress(String queueName) throws IOException {
        return "/queues/" + URLEncoder.encode(queueName, "UTF-8").replace("+", "%20");
    }

    /**
     * An HTTP/1.1 client: the default one first offers an HTTP/2 upgrade,
     * which the management listener drops part way through a request that
     * has a body.
     */
    private static HttpClient http() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private static String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private static String queueUrl(String queueName) throws IOException {
        String vhost = URLEncoder.encode(VHOST, "UTF-8").replace("+", "%20");
        String name = URLEncoder.encode(queueName, "UTF-8").replace("+", "%20");
        return "http://" + HOST + ":" + MANAGEMENT_PORT + "/api/queues/" + vhost + "/" + name;
    }

    /**
     * Declares a durable classic queue through the management API. (RabbitMQ
     * 4 no longer permits transient non-exclusive queues by default.) The
     * tests delete their queues afterwards.
     */
    static void declareQueue(String queueName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(queueUrl(queueName)))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"durable\":true,\"auto_delete\":false}"))
                .build();
        HttpResponse<String> response = http()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 204) {
            throw new IOException("declaring queue " + queueName + " returned "
                    + response.statusCode() + ": " + response.body());
        }
    }

    /** Deletes a queue; a missing queue is not an error. */
    static void deleteQueue(String queueName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(queueUrl(queueName)))
                .header("Authorization", authHeader())
                .DELETE()
                .build();
        http().send(request, HttpResponse.BodyHandlers.discarding());
    }

    // ── management API: force-closing a live connection ──

    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Force-closes the broker's connection from the client with the given
     * container id, via the management API, to simulate an unexpected network
     * drop against a real broker.
     *
     * <p>The connection is found by container id rather than by taking
     * whatever the listing shows: the management plugin lists connections
     * from a periodically collected snapshot, so a connection only a moment
     * old can be missing for a few seconds, while entries for connections
     * that have already closed can linger. Closing "the first connection
     * listed" could therefore close a stale entry and leave the live one
     * untouched. This polls until the wanted connection appears.
     *
     * @param containerId the container id the client announced in its
     *      {@code open}
     */
    static void forceCloseConnection(String containerId) throws IOException, InterruptedException {
        HttpClient http = http();
        String wanted = "\"container_id\":\"" + containerId + "\"";
        String name = null;
        long deadline = System.currentTimeMillis() + 15_000L;
        while (name == null && System.currentTimeMillis() < deadline) {
            HttpRequest list = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + HOST + ":" + MANAGEMENT_PORT + "/api/connections"))
                    .header("Authorization", authHeader())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(list, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("management API /api/connections returned "
                        + response.statusCode() + ": " + response.body());
            }
            for (String connection : topLevelObjects(response.body())) {
                if (connection.replace(" ", "").contains(wanted)) {
                    Matcher matcher = NAME_PATTERN.matcher(connection);
                    if (matcher.find()) {
                        name = matcher.group(1);
                    }
                }
            }
            if (name == null) {
                Thread.sleep(250L);
            }
        }
        if (name == null) {
            throw new IOException("the management API never listed a connection from container "
                    + containerId);
        }
        // A path segment, not a query string: a space must be %20, not '+'
        String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        HttpRequest delete = HttpRequest.newBuilder()
                .uri(URI.create("http://" + HOST + ":" + MANAGEMENT_PORT
                        + "/api/connections/" + encoded))
                .header("Authorization", authHeader())
                .DELETE()
                .build();
        HttpResponse<Void> result = http.send(delete, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() != 204) {
            throw new IOException("closing connection " + name + " returned " + result.statusCode());
        }
    }

    /**
     * Splits a JSON array of objects into its top-level objects, so that a
     * field can be matched against the object it belongs to. A brace counter
     * rather than a JSON parser: this is test-only tooling over the
     * management API's small, well-known output, whose strings here never
     * contain braces.
     */
    private static List<String> topLevelObjects(String json) {
        List<String> objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    // ── TLS ──

    /**
     * A real (not accept-all) trust manager that trusts exactly the test
     * broker's CA, built from a fresh in-memory truststore.
     */
    static X509TrustManager loadCaTrustManager() throws IOException {
        try {
            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            Certificate ca;
            try (InputStream in = Files.newInputStream(CA_CERT_FILE)) {
                ca = certificates.generateCertificate(in);
            }
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            trustStore.setCertificateEntry("rabbitmq-test-ca", ca);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
            throw new IllegalStateException("no X509TrustManager for the test CA truststore");
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("failed to build a trust manager from " + CA_CERT_FILE, e);
        }
    }
}
