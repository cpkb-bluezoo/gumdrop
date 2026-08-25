/*
 * RabbitMQPlaintextIntegrationTest.java
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
import org.bluezoo.gumdrop.amqp.client.BasicProperties;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.RecoveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConfirmSelectHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerExchangeDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueBindHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxCommitHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxRollbackHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxSelectHandler;

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
 * End-to-end tests of the real AMQP client against a real, locally-running
 * RabbitMQ broker over plaintext (port 5672) -- not run in CI, see
 * {@link RabbitMQTestSupport}.
 *
 * <p>Covers the same ground {@code AMQPClientIntegrationTest} covers
 * against {@code FakeAMQPBroker} (declare/bind/publish/consume,
 * publisher confirms) plus transactions, which the fake broker doesn't
 * implement -- these exist to catch anything a from-scratch AMQP 0-9-1
 * implementation gets subtly wrong against a real, spec-compliant peer
 * that a hand-rolled fake broker might silently agree with anyway.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQPlaintextIntegrationTest {

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

    private AMQPClientRecovery newClient() {
        return new AMQPClientRecovery(RabbitMQTestSupport.HOST, RabbitMQTestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQTestSupport.USERNAME, RabbitMQTestSupport.PASSWORD)
                .virtualHost(RabbitMQTestSupport.VHOST);
    }

    private static <T> T await(CountDownLatch latch, AtomicReference<T> value) throws InterruptedException {
        assertTrue("timed out waiting for callback", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return value.get();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    public void testConnectAndOpenChannel() throws Exception {
        client = newClient();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ClientChannel> channelRef = new AtomicReference<>();
        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(ClientChannel channel) {
                        channelRef.set(channel);
                        latch.countDown();
                    }
                });
            }
        });

        ClientChannel channel = await(latch, channelRef);
        assertEquals(1, channel.getChannelId());
    }

    @Test
    public void testDeclareBindPublishConsumeRoundTrip() throws Exception {
        client = newClient();
        final String exchange = unique("gumdrop-test-exchange");
        final String queue = unique("gumdrop-test-queue");

        final CountDownLatch deliveredLatch = new CountDownLatch(1);
        final AtomicReference<String> deliveredBody = new AtomicReference<>();
        final AtomicReference<String> deliveredContentType = new AtomicReference<>();

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.exchangeDeclare(exchange, "direct", false, true, null, new ServerExchangeDeclareHandler() {
                            // durable=true (not false): RabbitMQ 4.x rejects non-durable,
                            // non-exclusive ("transient_nonexcl") queues by default as a
                            // deprecated feature -- every queueDeclare in this file uses
                            // durable=true for that reason, even though these are
                            // short-lived, auto-delete test queues where durability
                            // itself is otherwise irrelevant.
                            @Override
                            public void handleExchangeDeclareOk() {
                                channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                                    @Override
                                    public void handleQueueDeclareOk(String q, long mc, long cc) {
                                        channel.queueBind(queue, exchange, "test-key", null, new ServerQueueBindHandler() {
                                            @Override
                                            public void handleQueueBindOk() {
                                                channel.basicConsume(queue, "", false, false, null,
                                                        new CollectingDeliveryHandler(deliveredBody, deliveredContentType, deliveredLatch),
                                                        new ServerConsumeHandler() {
                                                            @Override
                                                            public void handleConsumeOk(String consumerTag) {
                                                                BasicProperties props = new BasicProperties().withContentType("text/plain");
                                                                PublishBody body = channel.basicPublish(
                                                                        exchange, "test-key", false, props, 11);
                                                                body.writeBody(ByteBuffer.wrap("hello world".getBytes(StandardCharsets.US_ASCII)));
                                                                body.complete();
                                                            }
                                                        });
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertEquals("hello world", await(deliveredLatch, deliveredBody));
        assertEquals("text/plain", deliveredContentType.get());
    }

    @Test
    public void testDefaultExchangeRoutesByQueueName() throws Exception {
        client = newClient();
        final String queue = unique("gumdrop-test-direct-queue");

        final CountDownLatch deliveredLatch = new CountDownLatch(1);
        final AtomicReference<String> deliveredBody = new AtomicReference<>();

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.basicConsume(queue, "", false, false, null,
                                        new CollectingDeliveryHandler(deliveredBody, new AtomicReference<String>(), deliveredLatch),
                                        new ServerConsumeHandler() {
                                            @Override
                                            public void handleConsumeOk(String consumerTag) {
                                                PublishBody body = channel.basicPublish("", queue, false, null, 3);
                                                body.writeBody(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
                                                body.complete();
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });

        assertEquals("abc", await(deliveredLatch, deliveredBody));
    }

    @Test
    public void testRejectedMessageIsRedeliveredOnRequeue() throws Exception {
        client = newClient();
        final String queue = unique("gumdrop-test-requeue-queue");

        final CountDownLatch redeliveredLatch = new CountDownLatch(1);
        final AtomicReference<Boolean> wasRedelivered = new AtomicReference<>(Boolean.FALSE);

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.basicConsume(queue, "", false, false, null,
                                        new DeliveryHandler() {
                                            private boolean firstDeliverySeen;

                                            @Override
                                            public void onDeliveryStart(String consumerTag, long deliveryTag,
                                                    boolean redelivered, String exchange, String routingKey) {
                                                if (!firstDeliverySeen) {
                                                    firstDeliverySeen = true;
                                                    // Reject without acking; requeue=true asks the
                                                    // broker to redeliver rather than drop/DLQ it.
                                                    channel.basicReject(deliveryTag, true);
                                                } else {
                                                    wasRedelivered.set(redelivered);
                                                    channel.basicAck(deliveryTag, false);
                                                    redeliveredLatch.countDown();
                                                }
                                            }

                                            @Override
                                            public void onDeliveryProperties(BasicProperties properties, long bodySize) {
                                            }

                                            @Override
                                            public void onDeliveryBodyChunk(ByteBuffer chunk) {
                                            }

                                            @Override
                                            public void onDeliveryComplete() {
                                            }
                                        },
                                        new ServerConsumeHandler() {
                                            @Override
                                            public void handleConsumeOk(String consumerTag) {
                                                PublishBody body = channel.basicPublish("", queue, false, null, 3);
                                                body.writeBody(ByteBuffer.wrap("xyz".getBytes(StandardCharsets.US_ASCII)));
                                                body.complete();
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });

        assertTrue("expected the rejected message to be redelivered",
                redeliveredLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("second delivery should be flagged redelivered=true", wasRedelivered.get());
    }

    @Test
    public void testPublisherConfirmsAckedByBroker() throws Exception {
        client = newClient();
        final String queue = unique("gumdrop-test-confirm-queue");

        final CountDownLatch confirmLatch = new CountDownLatch(1);
        final AtomicReference<Long> ackedSeq = new AtomicReference<>();

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.confirmSelect(new ServerConfirmSelectHandler() {
                                    @Override
                                    public void handleConfirmSelectOk() {
                                        channel.setConfirmListener(new ConfirmListener() {
                                            @Override
                                            public void onAck(long sequenceNumber, boolean multiple) {
                                                ackedSeq.set(sequenceNumber);
                                                confirmLatch.countDown();
                                            }

                                            @Override
                                            public void onNack(long sequenceNumber, boolean multiple) {
                                            }
                                        });
                                        PublishBody body = channel.basicPublish("", queue, false, null, 0);
                                        assertEquals(1L, body.getSequenceNumber());
                                        body.complete();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });

        assertEquals(Long.valueOf(1L), await(confirmLatch, ackedSeq));
    }

    @Test
    public void testTransactionCommitDeliversMessage() throws Exception {
        client = newClient();
        final String queue = unique("gumdrop-test-tx-commit-queue");

        final CountDownLatch deliveredLatch = new CountDownLatch(1);
        final AtomicReference<String> deliveredBody = new AtomicReference<>();

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.basicConsume(queue, "", false, false, null,
                                        new CollectingDeliveryHandler(deliveredBody, new AtomicReference<String>(), deliveredLatch),
                                        new ServerConsumeHandler() {
                                            @Override
                                            public void handleConsumeOk(String consumerTag) {
                                                channel.txSelect(new ServerTxSelectHandler() {
                                                    @Override
                                                    public void handleTxSelectOk() {
                                                        PublishBody body = channel.basicPublish("", queue, false, null, 9);
                                                        body.writeBody(ByteBuffer.wrap("committed".getBytes(StandardCharsets.US_ASCII)));
                                                        body.complete();
                                                        channel.txCommit(new ServerTxCommitHandler() {
                                                            @Override public void handleTxCommitOk() { }
                                                        });
                                                    }
                                                });
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });

        assertEquals("committed", await(deliveredLatch, deliveredBody));
    }

    @Test
    public void testTransactionRollbackNeverDeliversMessage() throws Exception {
        client = newClient();
        final String queue = unique("gumdrop-test-tx-rollback-queue");

        // No message is ever expected: publish inside the transaction,
        // roll back, then publish a distinct sentinel outside any
        // transaction and confirm *that* is the only thing delivered.
        final CountDownLatch deliveredLatch = new CountDownLatch(1);
        final AtomicReference<String> deliveredBody = new AtomicReference<>();

        client.connect(new RecoveryHandler() {
            @Override
            public void onFirstConnect(ClientConnection connection) {
                connection.channelOpen(1, new ServerChannelOpenHandler() {
                    @Override
                    public void handleChannelOpenOk(final ClientChannel channel) {
                        channel.queueDeclare(queue, true, false, true, null, new ServerQueueDeclareHandler() {
                            @Override
                            public void handleQueueDeclareOk(String q, long mc, long cc) {
                                channel.basicConsume(queue, "", false, false, null,
                                        new CollectingDeliveryHandler(deliveredBody, new AtomicReference<String>(), deliveredLatch),
                                        new ServerConsumeHandler() {
                                            @Override
                                            public void handleConsumeOk(String consumerTag) {
                                                channel.txSelect(new ServerTxSelectHandler() {
                                                    @Override
                                                    public void handleTxSelectOk() {
                                                        PublishBody rolledBack = channel.basicPublish("", queue, false, null, 11);
                                                        rolledBack.writeBody(ByteBuffer.wrap("rolled-back".getBytes(StandardCharsets.US_ASCII)));
                                                        rolledBack.complete();
                                                        channel.txRollback(new ServerTxRollbackHandler() {
                                                            @Override
                                                            public void handleTxRollbackOk() {
                                                                // tx.rollback discards the pending publish but does not
                                                                // exit transactional mode -- this sentinel publish is
                                                                // itself still inside a transaction and needs its own
                                                                // commit to actually be delivered.
                                                                PublishBody sentinel = channel.basicPublish("", queue, false, null, 9);
                                                                sentinel.writeBody(ByteBuffer.wrap("sentinel-".getBytes(StandardCharsets.US_ASCII)));
                                                                sentinel.complete();
                                                                channel.txCommit(new ServerTxCommitHandler() {
                                                                    @Override public void handleTxCommitOk() { }
                                                                });
                                                            }
                                                        });
                                                    }
                                                });
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });

        assertEquals("only the post-rollback sentinel should have been delivered",
                "sentinel-", await(deliveredLatch, deliveredBody));
    }

    /** Collects a delivery's content-type and body into the given references, then counts down. */
    private static final class CollectingDeliveryHandler implements DeliveryHandler {
        private final StringBuilder body = new StringBuilder();
        private final AtomicReference<String> bodyResult;
        private final AtomicReference<String> contentTypeResult;
        private final CountDownLatch latch;
        private BasicProperties props;

        CollectingDeliveryHandler(AtomicReference<String> bodyResult,
                AtomicReference<String> contentTypeResult, CountDownLatch latch) {
            this.bodyResult = bodyResult;
            this.contentTypeResult = contentTypeResult;
            this.latch = latch;
        }

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
            if (props != null) {
                contentTypeResult.set(props.getContentType());
            }
            bodyResult.set(body.toString());
            latch.countDown();
        }
    }
}
