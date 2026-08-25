/*
 * RabbitMQRecoveryIntegrationTest.java
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
import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryListener;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueDeclareHandler;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Exercises {@link AMQPClientRecovery}'s reconnect and topology-replay
 * logic against an unexpected disconnect from a real RabbitMQ broker --
 * not run in CI, see {@link RabbitMQTestSupport}.
 *
 * <p>{@code AMQPClientIntegrationTest} already covers this against
 * {@code FakeAMQPBroker}, which can just drop its socket on command.
 * There is no equivalent hook on an already-running real broker, so this
 * uses RabbitMQ's management HTTP API ({@code DELETE
 * /api/connections/{name}}, via {@link RabbitMQTestSupport#forceCloseAllConnections})
 * to force-close the live AMQP connection out from under the client --
 * closer to what an actual network blip or broker-side connection churn
 * looks like than a client-side socket drop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQRecoveryIntegrationTest {

    // Generous: forceCloseAllConnections() itself polls the management
    // API's stats snapshot for up to 10s before this budget even starts
    // counting down on the recovery wait itself (see its own comment).
    private static final long TIMEOUT_SECONDS = 20;

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

    @Test
    public void testForcedDisconnectTriggersReconnectAndTopologyReplay() throws Exception {
        String queue = "gumdrop-recovery-test-" + UUID.randomUUID();
        client = new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST)
                .recoveryPolicy(new RecoveryPolicy().withInitialDelayMs(200L).withMaxDelayMs(1000L));

        CountDownLatch firstConsumeOk = new CountDownLatch(1);
        AtomicReference<ClientChannel> channelRef = new AtomicReference<>();

        // Registered once, up front, and never cancelled: AMQPClientRecovery
        // auto-replays it against the reconnected channel (see
        // RecoverableChannelImpl.rebind()), so re-registering a *second*
        // consumer on the same queue post-recovery (as an earlier version
        // of this test did) would leave two consumers competing for
        // deliveries via RabbitMQ's round-robin dispatch -- and since the
        // original had no real delivery handler, whichever message it
        // happened to win would simply vanish, making the assertion below
        // flaky-to-permanently-hang depending on dispatch order. Reusing
        // this single, already-replayed consumer for the post-recovery
        // assertion is both simpler and avoids that race entirely.
        CountDownLatch deliveredLatch = new CountDownLatch(1);
        AtomicReference<String> deliveredBody = new AtomicReference<>();
        DeliveryHandler deliveryHandler = new DeliveryHandler() {
            private final StringBuilder collected = new StringBuilder();

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
                collected.append(new String(b, StandardCharsets.US_ASCII));
            }

            @Override
            public void onDeliveryComplete() {
                deliveredBody.set(collected.toString());
                deliveredLatch.countDown();
            }
        };

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channelRef.set(channel);
                        // durable=true, not auto-delete: the connection that declared
                        // it is about to be forcibly severed, and a non-durable
                        // auto-delete queue disappears the moment its declaring
                        // connection goes away -- which would make this a test of
                        // queue.declare on reconnect, not of whether the *original*
                        // queue and consumer are still usable afterwards. (durable
                        // is also required here regardless: RabbitMQ 4.x rejects
                        // non-durable, non-exclusive "transient_nonexcl" queues by
                        // default -- see the equivalent comment in
                        // RabbitMQPlaintextIntegrationTest.)
                        channel.queueDeclare(queue, true, false, false, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.basicConsume(queue, "", false, false, null,
                                        deliveryHandler,
                                        new ServerConsumeHandler() {
                                            @Override
                                            public void handleConsumeOk(String consumerTag) {
                                                firstConsumeOk.countDown();
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("initial connect/declare/consume did not complete in time",
                firstConsumeOk.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CountDownLatch recoveredLatch = new CountDownLatch(1);
        client.recoveryListener(new RecoveryListener() {
            @Override
            public void onRecovered() {
                recoveredLatch.countDown();
            }
        });

        // Sever the connection from the broker side via the management API
        // -- the client has no warning and did not initiate this.
        RabbitMQTestSupport.forceCloseAllConnections();

        assertTrue("client did not recover within timeout after a forced disconnect",
                recoveredLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // After recovery, the queue was redeclared and the consumer
        // re-registered automatically against the new connection. The
        // application's original ClientChannel reference must be live
        // again with no further action -- publish through it and confirm
        // the message genuinely round-trips through the real broker via
        // the same (auto-replayed) consumer, not just that publish()
        // didn't throw and not just that the client-side state machine
        // thinks it reconnected.
        ClientChannel channel = channelRef.get();
        PublishBody body = channel.basicPublish("", queue, false, null, 13);
        body.writeBody(ByteBuffer.wrap("post-recovery".getBytes(StandardCharsets.US_ASCII)));
        body.complete(); // must not throw IllegalStateException (channel must be live again)

        assertTrue("post-recovery publish/consume did not complete in time",
                deliveredLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("post-recovery", deliveredBody.get());
    }
}
