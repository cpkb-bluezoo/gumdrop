/*
 * AMQPClientIntegrationTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * End-to-end tests of {@link AMQPClientRecovery} (and transitively
 * {@link AMQPClientProtocolHandler}) against {@link FakeAMQPBroker} over a
 * real loopback socket — exercising connect, channel open, exchange/queue
 * declare, bind, publish, consume, ack, publisher confirms, and a forced
 * disconnect-then-recover scenario, per issue #154's "no real broker in
 * the test environment" requirement.
 *
 * <p>Real sockets and a background thread mean these tests are
 * necessarily async; each waits on a {@link CountDownLatch} with a
 * generous timeout rather than asserting immediately.
 */
public class AMQPClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private FakeAMQPBroker broker;
    private AMQPClientRecovery client;

    @Before
    public void setUp() throws IOException {
        broker = new FakeAMQPBroker();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    private static <T> T await(CountDownLatch latch, AtomicReference<T> value) throws InterruptedException {
        assertTrue("timed out waiting for callback", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return value.get();
    }

    @Test
    public void testConnectAndOpenChannel() throws Exception {
        client = new AMQPClientRecovery("localhost", broker.getPort()).credentials("guest", "guest");

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
    public void testDeclareBindPublishConsumeRoundTrip() throws Exception {
        client = new AMQPClientRecovery("localhost", broker.getPort()).credentials("guest", "guest");

        CountDownLatch deliveredLatch = new CountDownLatch(1);
        AtomicReference<String> deliveredBody = new AtomicReference<>();
        AtomicReference<String> deliveredContentType = new AtomicReference<>();

        client.connect(connection -> connection.channelOpen(1, channel -> {
            channel.exchangeDeclare("test-exchange", "direct", false, false, null, () -> {
                channel.queueDeclare("test-queue", false, false, false, null, (queue, mc, cc) -> {
                    channel.queueBind("test-queue", "test-exchange", "test-key", null, () -> {
                        channel.basicConsume("test-queue", "", false, false, null,
                                new DeliveryHandler() {
                                    private final StringBuilder body = new StringBuilder();
                                    private BasicProperties props;

                                    @Override
                                    public void onDeliveryStart(String consumerTag, long deliveryTag,
                                            boolean redelivered, String exchange, String routingKey) {
                                    }

                                    @Override
                                    public void onDeliveryProperties(BasicProperties properties, long bodySize) {
                                        this.props = properties;
                                    }

                                    @Override
                                    public void onDeliveryBodyChunk(ByteBuffer chunk) {
                                        byte[] b = new byte[chunk.remaining()];
                                        chunk.get(b);
                                        body.append(new String(b, StandardCharsets.US_ASCII));
                                    }

                                    @Override
                                    public void onDeliveryComplete() {
                                        deliveredContentType.set(props.getContentType());
                                        deliveredBody.set(body.toString());
                                        deliveredLatch.countDown();
                                    }
                                },
                                consumerTag -> {
                                    // Now that a consumer is registered, publish.
                                    BasicProperties props = new BasicProperties().withContentType("text/plain");
                                    PublishBody publishBody = channel.basicPublish(
                                            "test-exchange", "test-key", false, props, 11);
                                    publishBody.writeBody(
                                            ByteBuffer.wrap("hello world".getBytes(StandardCharsets.US_ASCII)));
                                    publishBody.complete();
                                });
                    });
                });
            });
        }));

        assertEquals("hello world", await(deliveredLatch, deliveredBody));
        assertEquals("text/plain", deliveredContentType.get());
    }

    @Test
    public void testDefaultExchangeRoutesByQueueName() throws Exception {
        client = new AMQPClientRecovery("localhost", broker.getPort()).credentials("guest", "guest");

        CountDownLatch deliveredLatch = new CountDownLatch(1);
        AtomicReference<String> deliveredBody = new AtomicReference<>();

        client.connect(connection -> connection.channelOpen(1, channel -> {
            channel.queueDeclare("direct-queue", false, false, false, null, (queue, mc, cc) -> {
                channel.basicConsume("direct-queue", "", false, false, null,
                        new SimpleDeliveryHandler(deliveredBody, deliveredLatch),
                        consumerTag -> {
                            // Default exchange ("") routes directly to the queue named by the routing key.
                            PublishBody body = channel.basicPublish("", "direct-queue", false, null, 3);
                            body.writeBody(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
                            body.complete();
                        });
            });
        }));

        assertEquals("abc", await(deliveredLatch, deliveredBody));
    }

    @Test
    public void testPublisherConfirmsAckedByBroker() throws Exception {
        client = new AMQPClientRecovery("localhost", broker.getPort()).credentials("guest", "guest");

        CountDownLatch confirmLatch = new CountDownLatch(1);
        AtomicReference<Long> ackedSeq = new AtomicReference<>();

        client.connect(connection -> connection.channelOpen(1, channel -> {
            channel.confirmSelect(() -> {
                channel.setConfirmListener(new org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener() {
                    @Override
                    public void onAck(long sequenceNumber, boolean multiple) {
                        ackedSeq.set(sequenceNumber);
                        confirmLatch.countDown();
                    }

                    @Override
                    public void onNack(long sequenceNumber, boolean multiple) {
                    }
                });
                PublishBody body = channel.basicPublish("", "some-queue", false, null, 0);
                assertEquals(1L, body.getSequenceNumber());
                body.complete();
            });
        }));

        assertEquals(Long.valueOf(1L), await(confirmLatch, ackedSeq));
    }

    @Test
    public void testForcedDisconnectTriggersReconnectAndTopologyReplay() throws Exception {
        client = new AMQPClientRecovery("localhost", broker.getPort())
                .credentials("guest", "guest")
                .recoveryPolicy(new RecoveryPolicy().withInitialDelayMs(200L).withMaxDelayMs(500L));

        CountDownLatch firstConsumeOk = new CountDownLatch(1);
        AtomicReference<ClientChannel> channelRef = new AtomicReference<>();

        client.connect(connection -> connection.channelOpen(1, channel -> {
            channelRef.set(channel);
            channel.queueDeclare("recovery-queue", false, false, false, null, (queue, mc, cc) -> {
                channel.basicConsume("recovery-queue", "", false, false, null,
                        new NoopDeliveryHandler(),
                        consumerTag -> firstConsumeOk.countDown());
            });
        }));

        assertTrue(firstConsumeOk.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, broker.connectionCount());

        List<Boolean> recovered = new CopyOnWriteArrayList<>();
        CountDownLatch recoveredLatch = new CountDownLatch(1);
        client.recoveryListener(new RecoveryListener() {
            @Override
            public void onRecovered() {
                recovered.add(true);
                recoveredLatch.countDown();
            }
        });

        // Simulate a network drop.
        broker.disconnectAll();

        assertTrue("client did not recover within timeout",
                recoveredLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, recovered.size());

        // After recovery, the queue was redeclared and the consumer
        // re-registered automatically. The application's original
        // ClientChannel reference is live again without any further
        // action — publish through it and confirm it doesn't throw
        // (which it would if the channel were still considered
        // disconnected, per RecoverableChannelImpl.requireLive()).
        ClientChannel channel = channelRef.get();
        PublishBody body = channel.basicPublish("", "recovery-queue", false, null, 5);
        body.writeBody(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
        body.complete(); // must not throw IllegalStateException (channel must be live again)
    }

    private static final class SimpleDeliveryHandler implements DeliveryHandler {
        private final StringBuilder body = new StringBuilder();
        private final AtomicReference<String> result;
        private final CountDownLatch latch;

        SimpleDeliveryHandler(AtomicReference<String> result, CountDownLatch latch) {
            this.result = result;
            this.latch = latch;
        }

        @Override
        public void onDeliveryStart(String consumerTag, long deliveryTag, boolean redelivered,
                String exchange, String routingKey) {
        }

        @Override
        public void onDeliveryProperties(BasicProperties properties, long bodySize) {
        }

        @Override
        public void onDeliveryBodyChunk(ByteBuffer chunk) {
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            body.append(new String(b, StandardCharsets.US_ASCII));
        }

        @Override
        public void onDeliveryComplete() {
            result.set(body.toString());
            latch.countDown();
        }
    }

    private static final class NoopDeliveryHandler implements DeliveryHandler {
        @Override public void onDeliveryStart(String consumerTag, long deliveryTag,
                boolean redelivered, String exchange, String routingKey) { }
        @Override public void onDeliveryProperties(BasicProperties properties, long bodySize) { }
        @Override public void onDeliveryBodyChunk(ByteBuffer chunk) { }
        @Override public void onDeliveryComplete() { }
    }
}
