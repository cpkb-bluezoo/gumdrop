/*
 * RabbitMQAuthMechanismIntegrationTest.java
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
import org.bluezoo.gumdrop.amqp.client.RecoveryPolicy;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryListener;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Exercises the real AMQP client's SASL mechanisms (issue #188) against a
 * real RabbitMQ broker, which -- unlike {@code FakeAMQPBroker} -- is a
 * genuine implementation of {@code AMQPLAIN} and of rejecting bad
 * credentials, so this is the only place these paths get tested against
 * an implementation gumdrop didn't write itself. Not run in CI, see
 * {@link RabbitMQTestSupport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQAuthMechanismIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private AMQPClientRecovery client;

    @Before
    public void checkBrokerReachable() {
        Assume.assumeTrue(RabbitMQTestSupport.NOT_REACHABLE_MESSAGE,
                RabbitMQTestSupport.isPlaintextReachable());
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static <T> T await(CountDownLatch latch, AtomicReference<T> value) throws InterruptedException {
        assertTrue("timed out waiting for callback", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return value.get();
    }

    @Test
    public void testPlainMechanismConnects() throws Exception {
        client = new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST)
                .mechanism("PLAIN");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ClientChannel> channelRef = new AtomicReference<>();
        client.connect(connection -> connection.channelOpen(1, channel -> {
            channelRef.set(channel);
            latch.countDown();
        }));

        assertEquals(1, await(latch, channelRef).getChannelId());
    }

    /** RabbitMQ implements AMQPLAIN itself (it's a RabbitMQ extension) -- a real cross-implementation check. */
    @Test
    public void testAmqplainMechanismConnects() throws Exception {
        client = new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST)
                .mechanism("AMQPLAIN");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ClientChannel> channelRef = new AtomicReference<>();
        client.connect(connection -> connection.channelOpen(1, channel -> {
            channelRef.set(channel);
            latch.countDown();
        }));

        assertEquals(1, await(latch, channelRef).getChannelId());
    }

    @Test
    public void testWrongPasswordIsRejectedNotSilentlyAccepted() throws Exception {
        client = new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, "definitely-the-wrong-password")
                .virtualHost(RabbitMQTestSupport.VHOST)
                .recoveryPolicy(new RecoveryPolicy().withMaxAttempts(1));

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch failedLatch = new CountDownLatch(1);
        client.recoveryListener(new RecoveryListener() {
            @Override
            public void onRecoveryFailed(Exception cause) {
                failedLatch.countDown();
            }
        });
        client.connect(connection -> connectedLatch.countDown());

        assertTrue("expected the broker to reject bad credentials rather than hang or silently accept them",
                failedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("must never have reached onFirstConnect with bad credentials", 1, connectedLatch.getCount());
    }

    @Test
    public void testMechanismRabbitDoesNotOfferFailsFastRatherThanHanging() throws Exception {
        // RabbitMQ's default installation doesn't offer GSSAPI or EXTERNAL
        // over a plain, non-mTLS listener -- requesting one must fail
        // fast (mechanism-not-offered) rather than silently falling back
        // to PLAIN or hanging waiting for a challenge that never comes.
        client = new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST)
                .mechanism("EXTERNAL")
                .recoveryPolicy(new RecoveryPolicy().withMaxAttempts(1));

        CountDownLatch failedLatch = new CountDownLatch(1);
        client.recoveryListener(new RecoveryListener() {
            @Override
            public void onRecoveryFailed(Exception cause) {
                failedLatch.countDown();
            }
        });
        client.connect(connection -> fail("should never reach onFirstConnect requesting EXTERNAL over a plain listener"));

        assertTrue("expected recovery to give up once EXTERNAL keeps being reported as unoffered",
                failedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
}
