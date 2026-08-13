/*
 * MosquittoClientIntegrationTest.java
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

package org.bluezoo.gumdrop.mqtt.mosquitto;

import org.bluezoo.gumdrop.mqtt.client.MQTTClient;
import org.bluezoo.gumdrop.mqtt.client.MQTTClientCallback;
import org.bluezoo.gumdrop.mqtt.client.MQTTMessageListener;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's MQTT client against a real, locally-running
 * Eclipse Mosquitto instance -- not run in CI, see {@link
 * MosquittoTestSupport}.
 *
 * <p>Same rationale as the Postfix/vsftpd/Dante/OpenLDAP/Redis tests: an
 * independent implementation on the other end of the wire catches bugs a
 * same-lineage fake server can't.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MosquittoClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Before
    public void checkReachable() {
        assumeTrue(MosquittoTestSupport.NOT_REACHABLE_MESSAGE, MosquittoTestSupport.isReachable());
    }

    private MQTTClient newClient(String clientIdSuffix) {
        MQTTClient client = new MQTTClient(MosquittoTestSupport.HOST, MosquittoTestSupport.PORT);
        client.setClientId("gumdrop-test-" + clientIdSuffix + "-" + System.nanoTime());
        client.setCredentials(MosquittoTestSupport.USERNAME, MosquittoTestSupport.PASSWORD);
        return client;
    }

    // ── Two separate connections: one subscribes, the other publishes ──

    @Test
    public void testTwoClientsPubSubRoundTrip() throws Exception {
        String topic = "gumdrop/test/" + System.nanoTime();
        String payload = "hello subscribers";

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();

        MQTTClient subscriber = newClient("sub");
        subscriber.connect(new TestCallback(error, subscribedLatch) {
            @Override
            public void connected(boolean sessionPresent, int returnCode) {
                if (returnCode != 0) {
                    fail(error, subscribedLatch, "CONNACK returnCode=" + returnCode);
                    return;
                }
                subscriber.subscribe(topic, QoS.AT_LEAST_ONCE);
            }

            @Override
            public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
                subscribedLatch.countDown();
            }
        }, (t, content, qos, retain) -> {
            try {
                received.set(new String(content.asByteArray(), StandardCharsets.UTF_8));
            } finally {
                content.release();
            }
            messageLatch.countDown();
        });

        assertTrue("subscribe did not complete within timeout",
                subscribedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }

        CountDownLatch publishedLatch = new CountDownLatch(1);
        MQTTClient publisher = newClient("pub");
        publisher.connect(new TestCallback(error, publishedLatch) {
            @Override
            public void connected(boolean sessionPresent, int returnCode) {
                if (returnCode != 0) {
                    fail(error, publishedLatch, "CONNACK returnCode=" + returnCode);
                    return;
                }
                publisher.publish(topic, payload, QoS.AT_LEAST_ONCE);
            }

            @Override
            public void publishComplete(int packetId) {
                publishedLatch.countDown();
            }
        }, (t, content, qos, retain) -> content.release());

        assertTrue("publish did not complete within timeout",
                publishedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("message not received within timeout",
                messageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals(payload, received.get());
    }

    // ── TLS: connect, subscribe, publish, receive over the encrypted port ──

    @Test
    public void testTlsConnectSubscribePublishRoundTrip() throws Exception {
        X509Certificate serverCert = MosquittoTestSupport.loadServerCertificate();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { pinningTrustManager(serverCert) }, null);

        String topic = "gumdrop/test/tls/" + System.nanoTime();
        String payload = "hello over tls";

        MQTTClient client = new MQTTClient(MosquittoTestSupport.HOST, MosquittoTestSupport.TLS_PORT);
        client.setClientId("gumdrop-test-tls-" + System.nanoTime());
        client.setCredentials(MosquittoTestSupport.USERNAME, MosquittoTestSupport.PASSWORD);
        client.setSecure(true);
        client.setSSLContext(sslContext);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();

        client.connect(new TestCallback(error, doneLatch) {
            @Override
            public void connected(boolean sessionPresent, int returnCode) {
                if (returnCode != 0) {
                    fail(error, doneLatch, "CONNACK returnCode=" + returnCode);
                    return;
                }
                client.subscribe(topic, QoS.AT_LEAST_ONCE);
            }

            @Override
            public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
                client.publish(topic, payload, QoS.AT_LEAST_ONCE);
            }
        }, (t, content, qos, retain) -> {
            try {
                received.set(new String(content.asByteArray(), StandardCharsets.UTF_8));
            } finally {
                content.release();
            }
            doneLatch.countDown();
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw error.get();
        }
        assertEquals(payload, received.get());
    }

    // ── Negative: wrong password ──

    @Test
    public void testWrongPasswordRejected() throws Exception {
        MQTTClient client = new MQTTClient(MosquittoTestSupport.HOST, MosquittoTestSupport.PORT);
        client.setClientId("gumdrop-test-badauth-" + System.nanoTime());
        client.setCredentials(MosquittoTestSupport.USERNAME, "wrong-password");

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Integer> connackCode = new AtomicReference<>();

        client.connect(new MQTTClientCallback() {
            @Override
            public void connected(boolean sessionPresent, int returnCode) {
                connackCode.set(returnCode);
                doneLatch.countDown();
            }

            @Override
            public void connectionLost(Exception cause) {
                // A broker may also reject bad credentials by simply
                // closing the connection rather than sending a non-zero
                // CONNACK; either is an acceptable way to observe rejection.
                doneLatch.countDown();
            }

            @Override
            public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
            }

            @Override
            public void publishComplete(int packetId) {
            }
        }, (t, content, qos, retain) -> content.release());

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (connackCode.get() != null) {
            assertTrue("expected a non-zero (rejection) CONNACK return code, got "
                    + connackCode.get(), connackCode.get() != 0);
        }
    }

    // ── Shared plumbing ──

    private void fail(AtomicReference<Exception> error, CountDownLatch doneLatch, String message) {
        error.set(new RuntimeException(message));
        doneLatch.countDown();
    }

    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("mqtt-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned mqtt-test cert");
    }

    // ── Test callback base class (default: fail on unexpected callback) ──

    private abstract class TestCallback implements MQTTClientCallback {
        final AtomicReference<Exception> error;
        final CountDownLatch latch;

        TestCallback(AtomicReference<Exception> error, CountDownLatch latch) {
            this.error = error;
            this.latch = latch;
        }

        @Override
        public void connectionLost(Exception cause) {
            error.set(cause);
            latch.countDown();
        }

        @Override
        public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
        }

        @Override
        public void publishComplete(int packetId) {
        }
    }
}
