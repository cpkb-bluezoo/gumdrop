/*
 * RabbitMQ4TlsIntegrationTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.amqp1.client.Amqp1ClientRecovery;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoverableSession;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoveryHandler;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test of the AMQP 1.0 client's implicit TLS ({@code amqps},
 * port 5671) against a real, locally-running RabbitMQ 4 broker -- not run
 * in CI, see {@link RabbitMQ4TestSupport}.
 *
 * <p>The test broker's TLS listener needs no client certificate, so this
 * exercises the handshake and verification of the broker's certificate
 * with a trust manager built from the test CA (not an accept-all one, which
 * would defeat the point of testing verification), followed by SASL and a
 * message round trip inside the TLS session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQ4TlsIntegrationTest {

    private static final long TIMEOUT_SECONDS = 15;

    private Gumdrop gumdrop;
    private Amqp1ClientRecovery client;
    private String queue;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("no TLS-enabled RabbitMQ 4 broker reachable at "
                        + RabbitMQ4TestSupport.HOST + ":" + RabbitMQ4TestSupport.TLS_PORT
                        + ", or CA cert file " + RabbitMQ4TestSupport.CA_CERT_FILE + " unreadable"
                        + " -- see RabbitMQ4TestSupport's class Javadoc",
                RabbitMQ4TestSupport.isTlsReachable());
        gumdrop = Gumdrop.boot();
        queue = "gumdrop-amqp1-tls-test-" + UUID.randomUUID();
        RabbitMQ4TestSupport.declareQueue(queue);
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (queue != null) {
            RabbitMQ4TestSupport.deleteQueue(queue);
        }
        if (gumdrop != null && gumdrop.isStarted()) {
            // Wait for the shutdown to finish: the next test boots its own
            // runtime, and a client stops recovering while one is draining
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void testMessageRoundTripOverTls() throws Exception {
        client = new Amqp1ClientRecovery(RabbitMQ4TestSupport.HOST, RabbitMQ4TestSupport.TLS_PORT)
                .credentials(RabbitMQ4TestSupport.USERNAME, RabbitMQ4TestSupport.PASSWORD)
                .hostname(RabbitMQ4TestSupport.HOST)
                .setSecure(true)
                .setTrustManager(RabbitMQ4TestSupport.loadCaTrustManager());
        final RabbitMQ4IntegrationTest.Receiving receiving = new RabbitMQ4IntegrationTest.Receiving(5);
        final RabbitMQ4IntegrationTest.Sending sending = new RabbitMQ4IntegrationTest.Sending();
        final String address = RabbitMQ4TestSupport.queueAddress(queue);
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachReceiver("gumdrop-in", address, receiving);
                session.attachSender("gumdrop-out", address, sending);
            }
        });

        assertTrue("timed out waiting for credit over TLS",
                sending.credit.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        RabbitMQ4TestSupport.onLoop(client, new Runnable() {
            @Override
            public void run() {
                sending.sender.send("t".getBytes(StandardCharsets.UTF_8), null, null,
                        ByteBuffer.wrap("over TLS".getBytes(StandardCharsets.UTF_8)));
            }
        });
        assertTrue("timed out waiting for the message",
                receiving.messages.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("over TLS", new String(receiving.body(0), StandardCharsets.UTF_8));
        assertTrue(sending.outcome.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(DeliveryState.Type.ACCEPTED, sending.lastState.getType());
    }
}
