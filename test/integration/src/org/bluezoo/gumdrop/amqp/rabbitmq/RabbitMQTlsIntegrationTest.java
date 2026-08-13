/*
 * RabbitMQTlsIntegrationTest.java
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

import org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

/**
 * End-to-end test of the real AMQP client's implicit-TLS support
 * (AMQPS, port 5671) against a real, locally-running RabbitMQ broker --
 * not run in CI, see {@link RabbitMQTestSupport}.
 *
 * <p>The test broker's TLS listener is configured with {@code
 * ssl_options.verify = verify_none} / {@code fail_if_no_peer_cert =
 * false} -- server-side TLS only, no client certificate required -- so
 * this only exercises the client's handshake and certificate
 * verification against the broker's server certificate, via a real
 * {@link TrustManagerFactory} built from the test CA (not an
 * accept-all {@code TrustManager}, which would defeat the point of
 * testing verification at all).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQTlsIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private AMQPClientRecovery client;

    @Before
    public void checkBrokerReachable() {
        Assume.assumeTrue("no TLS-enabled RabbitMQ broker reachable at "
                        + RabbitMQTestSupport.HOST + ":" + RabbitMQTestSupport.TLS_PORT
                        + ", or CA cert file " + RabbitMQTestSupport.CA_CERT_FILE + " unreadable"
                        + " -- see RabbitMQTestSupport's class Javadoc",
                RabbitMQTestSupport.isTlsReachable());
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Builds a real (non-accept-all) {@link X509TrustManager} that trusts
     * exactly the test broker's CA, by loading it into a fresh in-memory
     * truststore and running it through the platform's default {@link
     * TrustManagerFactory} algorithm -- the same approach {@code
     * DTLSSessionTest} uses, for the same reason (an accept-all trust
     * manager is a real anti-pattern CodeQL correctly flags, even in test
     * code, and a proper truststore is barely more code).
     */
    private static X509TrustManager loadCaTrustManager() throws IOException, CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        Certificate caCert;
        try (InputStream in = Files.newInputStream(RabbitMQTestSupport.CA_CERT_FILE)) {
            caCert = certFactory.generateCertificate(in);
        }

        try {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            trustStore.setCertificateEntry("rabbitmq-test-ca", caCert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
            throw new IllegalStateException("no X509TrustManager produced for the test CA truststore");
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("failed to build trust manager from " + RabbitMQTestSupport.CA_CERT_FILE, e);
        }
    }

    private AMQPClientRecovery newTlsClient() throws IOException, CertificateException {
        return new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.TLS_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST)
                .setSecure(true)
                .setTrustManager(loadCaTrustManager());
    }

    private static <T> T await(CountDownLatch latch, AtomicReference<T> value) throws InterruptedException {
        assertTrue("timed out waiting for callback", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return value.get();
    }

    @Test
    public void testTlsHandshakeAndChannelOpen() throws Exception {
        client = newTlsClient();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ClientChannel> channelRef = new AtomicReference<>();
        client.connect(connection -> connection.channelOpen(1, channel -> {
            channelRef.set(channel);
            latch.countDown();
        }));

        ClientChannel channel = await(latch, channelRef);
        assertEquals(1, channel.getChannelId());
    }

    @Test
    public void testPublishConsumeRoundTripOverTls() throws Exception {
        client = newTlsClient();
        String queue = "gumdrop-tls-test-" + UUID.randomUUID();

        CountDownLatch deliveredLatch = new CountDownLatch(1);
        AtomicReference<String> deliveredBody = new AtomicReference<>();

        client.connect(connection -> connection.channelOpen(1, channel -> {
            // durable=true: RabbitMQ 4.x rejects non-durable, non-exclusive
            // "transient_nonexcl" queues by default -- see the equivalent
            // comment in RabbitMQPlaintextIntegrationTest.
            channel.queueDeclare(queue, true, false, true, null, (q, mc, cc) -> {
                channel.basicConsume(queue, "", false, false, null,
                        new DeliveryHandler() {
                            private final StringBuilder body = new StringBuilder();

                            @Override
                            public void onDeliveryStart(String consumerTag, long deliveryTag,
                                    boolean redelivered, String exchange, String routingKey) {
                            }

                            @Override
                            public void onDeliveryProperties(org.bluezoo.gumdrop.amqp.client.BasicProperties properties,
                                    long bodySize) {
                            }

                            @Override
                            public void onDeliveryBodyChunk(ByteBuffer chunk) {
                                byte[] b = new byte[chunk.remaining()];
                                chunk.get(b);
                                body.append(new String(b, StandardCharsets.US_ASCII));
                            }

                            @Override
                            public void onDeliveryComplete() {
                                deliveredBody.set(body.toString());
                                deliveredLatch.countDown();
                            }
                        },
                        consumerTag -> {
                            PublishBody body = channel.basicPublish("", queue, false, null, 14);
                            body.writeBody(ByteBuffer.wrap("hello over tls".getBytes(StandardCharsets.US_ASCII)));
                            body.complete();
                        });
            });
        }));

        assertEquals("hello over tls", await(deliveredLatch, deliveredBody));
    }
}
