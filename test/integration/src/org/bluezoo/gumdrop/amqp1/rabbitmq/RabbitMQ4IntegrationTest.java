/*
 * RabbitMQ4IntegrationTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.amqp1.client.Amqp1ClientRecovery;
import org.bluezoo.gumdrop.amqp1.client.Amqp1IncomingDelivery;
import org.bluezoo.gumdrop.amqp1.client.Amqp1OutgoingDelivery;
import org.bluezoo.gumdrop.amqp1.client.Amqp1Receiver;
import org.bluezoo.gumdrop.amqp1.client.Amqp1ReceiverHandler;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoverableSession;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoveryHandler;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoveryListener;
import org.bluezoo.gumdrop.amqp1.client.Amqp1RecoveryPolicy;
import org.bluezoo.gumdrop.amqp1.client.Amqp1Sender;
import org.bluezoo.gumdrop.amqp1.client.Amqp1SenderHandler;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end tests of the AMQP 1.0 client against a real, locally-running
 * RabbitMQ 4 broker over plaintext (port 5672) -- not run in CI, see
 * {@link RabbitMQ4TestSupport}.
 *
 * <p>These exist to catch anything a from-scratch AMQP 1.0 implementation
 * gets subtly wrong against a real, independent implementation that the
 * in-process {@code FakeAmqp1Broker} (built from the same codec, so it can
 * share the client's misreadings of the specification) would silently agree
 * with: the SASL handshake, addressing, credit and delivery-count
 * arithmetic, settlement, and framing of a large message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RabbitMQ4IntegrationTest {

    private static final long TIMEOUT_SECONDS = 15;

    private Gumdrop gumdrop;
    private Amqp1ClientRecovery client;
    private String queue;
    private String containerId;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(RabbitMQ4TestSupport.NOT_REACHABLE_MESSAGE,
                RabbitMQ4TestSupport.isPlaintextReachable());
        gumdrop = Gumdrop.boot();
        queue = "gumdrop-amqp1-test-" + UUID.randomUUID();
        containerId = "gumdrop-amqp1-test-client-" + UUID.randomUUID();
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

    private Amqp1ClientRecovery newClient() {
        return new Amqp1ClientRecovery(RabbitMQ4TestSupport.HOST, RabbitMQ4TestSupport.PLAINTEXT_PORT)
                .credentials(RabbitMQ4TestSupport.USERNAME, RabbitMQ4TestSupport.PASSWORD)
                .hostname(RabbitMQ4TestSupport.HOST)
                .containerId(containerId)
                .recoveryPolicy(new Amqp1RecoveryPolicy().withInitialDelayMs(100).withMaxDelayMs(500));
    }

    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue("timed out waiting for " + what, latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + (i >>> 8));
        }
        return b;
    }

    /** Sends one small message from the test thread, by way of the client's event loop. */
    private void send(final Sending sending, final byte[] tag, final MessageProperties props,
            final Map<Object, Object> appProps, final String body) throws Exception {
        RabbitMQ4TestSupport.onLoop(client, new Runnable() {
            @Override
            public void run() {
                sending.sender.send(tag, props, appProps, ByteBuffer.wrap(bytes(body)));
            }
        });
    }

    /** Connects, attaching a receiver (given credit on every attachment) and a sender to the test queue. */
    private void connectBoth(final Receiving receiving, final Sending sending) throws Exception {
        final String address = RabbitMQ4TestSupport.queueAddress(queue);
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachReceiver("gumdrop-in", address, receiving);
                session.attachSender("gumdrop-out", address, sending);
            }
        });
    }

    @Test
    public void testPublishAndConsumeRoundTrip() throws Exception {
        client = newClient();
        Receiving receiving = new Receiving(10);
        Sending sending = new Sending();
        connectBoth(receiving, sending);

        await(sending.credit, "link credit from RabbitMQ");
        MessageProperties props = new MessageProperties();
        props.setSubject("greeting");
        props.setContentType("text/plain");
        Map<Object, Object> appProps = new LinkedHashMap<Object, Object>();
        appProps.put("colour", "red");
        send(sending, bytes("t-1"), props, appProps, "hello, RabbitMQ");

        await(receiving.messages, "the message");
        assertEquals("hello, RabbitMQ", new String(receiving.body(0), StandardCharsets.UTF_8));
        assertEquals("greeting", receiving.subjects.get(0));
        assertEquals("text/plain", receiving.contentTypes.get(0));
        assertEquals("red", receiving.appProperties.get(0).get("colour"));

        await(sending.outcome, "the outcome");
        assertNotNull(sending.lastState);
        assertEquals(DeliveryState.Type.ACCEPTED, sending.lastState.getType());
    }

    @Test
    public void testSeveralMessagesUseAndReplenishCredit() throws Exception {
        client = newClient();
        Receiving receiving = new Receiving(5, 25); // credit for 5 at a time, 25 messages
        final Sending sending = new Sending();
        connectBoth(receiving, sending);
        await(sending.credit, "link credit");
        int sent = 0;
        while (sent < 25) {
            final int n = sent;
            final boolean[] sentOne = new boolean[1];
            RabbitMQ4TestSupport.onLoop(client, new Runnable() {
                @Override
                public void run() {
                    if (sending.sender.getLinkCredit() > 0) {
                        sending.sender.send(bytes("t-" + n), null, null,
                                ByteBuffer.wrap(bytes("m" + n)));
                        sentOne[0] = true;
                    }
                }
            });
            if (sentOne[0]) {
                sent++;
            } else {
                Thread.sleep(10); // no credit yet: wait for the broker's flow
            }
        }
        await(receiving.messages, "all 25 messages");
        for (int i = 0; i < 25; i++) {
            assertEquals("m" + i, new String(receiving.body(i), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testLargeMessageRoundTrip() throws Exception {
        client = newClient();
        Receiving receiving = new Receiving(5);
        final Sending sending = new Sending();
        connectBoth(receiving, sending);
        await(sending.credit, "link credit");

        final byte[] body = pattern(2 * 1024 * 1024);
        RabbitMQ4TestSupport.onLoop(client, new Runnable() {
            @Override
            public void run() {
                Amqp1OutgoingDelivery d = sending.sender.startDelivery(bytes("big"),
                        new MessageHeader(), null, null, false);
                for (int off = 0; off < body.length; off += 65536) {
                    d.write(ByteBuffer.wrap(body, off, Math.min(65536, body.length - off)));
                }
                d.finish();
            }
        });

        await(receiving.messages, "the large message");
        assertArrayEquals(body, receiving.body(0));
        assertTrue("streamed to the application in chunks: " + receiving.chunks.get(),
                receiving.chunks.get() > 1);
    }

    @Test
    public void testAttachingToAMissingQueueIsRefused() throws Exception {
        client = newClient();
        final Sending sending = new Sending();
        final String address = RabbitMQ4TestSupport.queueAddress("gumdrop-no-such-queue-" + UUID.randomUUID());
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("gumdrop-out", address, sending);
            }
        });
        await(sending.detached, "RabbitMQ to refuse the link");
        assertNotNull("RabbitMQ explains a refusal", sending.detachError);
        assertTrue("closed, so not retried", sending.detachClosed);
        assertNull(sending.sender);
    }

    @Test
    public void testWrongPasswordEndsRecovery() throws Exception {
        final CountDownLatch failed = new CountDownLatch(1);
        client = newClient().credentials(RabbitMQ4TestSupport.USERNAME, "definitely-not-the-password")
                .recoveryListener(new Amqp1RecoveryListener() {
                    @Override
                    public void onRecoveryFailed(Exception cause) {
                        failed.countDown();
                    }
                });
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                throw new AssertionError("must not connect with a wrong password");
            }
        });
        await(failed, "recovery to be abandoned");
    }

    @Test
    public void testLinksRecoverAfterTheBrokerClosesTheConnection() throws Exception {
        final CountDownLatch recovered = new CountDownLatch(1);
        client = newClient().recoveryListener(new Amqp1RecoveryListener() {
            @Override
            public void onRecovered() {
                recovered.countDown();
            }
        });
        Receiving receiving = new Receiving(10);
        Sending sending = new Sending();
        connectBoth(receiving, sending);
        await(sending.credit, "initial credit");

        sending.resetCredit();
        RabbitMQ4TestSupport.forceCloseConnection(containerId);
        await(recovered, "recovery");
        await(sending.credit, "credit after recovery");

        send(sending, bytes("t-2"), null, null, "after recovery");
        await(receiving.messages, "a message after recovery");
        assertEquals("after recovery", new String(receiving.body(0), StandardCharsets.UTF_8));
        assertEquals(2, sending.attached.get());
    }

    // ── handlers ──

    static final class Sending implements Amqp1SenderHandler {
        volatile Amqp1Sender sender;
        volatile CountDownLatch credit = new CountDownLatch(1);
        final CountDownLatch outcome = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(1);
        volatile DeliveryState lastState;
        volatile Amqp1Error detachError;
        volatile boolean detachClosed;
        final AtomicInteger attached = new AtomicInteger();

        void resetCredit() {
            credit = new CountDownLatch(1);
        }

        @Override
        public void handleAttached(Amqp1Sender s, Attach peerAttach) {
            sender = s;
            attached.incrementAndGet();
        }

        @Override
        public void handleCredit(Amqp1Sender s) {
            credit.countDown();
        }

        @Override
        public void handleWritable(Amqp1OutgoingDelivery delivery) {
        }

        @Override
        public void handleOutcome(Amqp1OutgoingDelivery delivery, DeliveryState state,
                boolean settled) {
            lastState = state;
            outcome.countDown();
            if (!settled) {
                delivery.settle();
            }
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            detachError = error;
            detachClosed = closed;
            detached.countDown();
        }
    }

    static final class Receiving implements Amqp1ReceiverHandler {
        private final long credit;
        final CountDownLatch messages;
        final AtomicInteger chunks = new AtomicInteger();
        final List<String> subjects = new CopyOnWriteArrayList<String>();
        final List<String> contentTypes = new CopyOnWriteArrayList<String>();
        final List<Map<Object, Object>> appProperties = new CopyOnWriteArrayList<Map<Object, Object>>();
        private final List<byte[]> bodies = new CopyOnWriteArrayList<byte[]>();
        private ByteArrayOutputStream current;
        private Amqp1Receiver receiver;

        Receiving(long credit) {
            this(credit, 1);
        }

        Receiving(long credit, int expectedMessages) {
            this.credit = credit;
            this.messages = new CountDownLatch(expectedMessages);
        }

        byte[] body(int index) {
            return bodies.get(index);
        }

        @Override
        public void handleAttached(Amqp1Receiver r, Attach peerAttach) {
            receiver = r;
            r.addCredit(credit); // on every attachment, including after recovery
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery delivery) {
            current = new ByteArrayOutputStream();
            delivery.accept();
            // keep the window full: one more credit per message taken
            if (receiver != null && receiver.getLinkCredit() < credit) {
                receiver.addCredit(1);
            }
        }

        @Override
        public void handleAborted(Amqp1IncomingDelivery delivery) {
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
        }

        @Override
        public void header(MessageHeader header) {
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> annotations) {
        }

        @Override
        public void messageAnnotations(Map<Object, Object> annotations) {
        }

        @Override
        public void properties(MessageProperties properties) {
            subjects.add(properties.getSubject());
            contentTypes.add(properties.getContentType());
        }

        @Override
        public void applicationProperties(Map<Object, Object> properties) {
            appProperties.add(properties);
        }

        @Override
        public void startData(long length) {
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
            chunks.incrementAndGet();
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            current.write(b, 0, b.length);
        }

        @Override
        public void endData() {
        }

        @Override
        public void amqpSequence(List<Object> row) {
        }

        @Override
        public void amqpValue(Object value) {
        }

        @Override
        public void footer(Map<Object, Object> footer) {
        }

        @Override
        public void endMessage() {
            bodies.add(current.toByteArray());
            messages.countDown();
        }

        @Override
        public void messageError(String message) {
            throw new AssertionError("malformed message: " + message);
        }
    }
}
