/*
 * Amqp1ClientIntegrationTest.java
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

package org.bluezoo.gumdrop.amqp1.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end tests of {@link Amqp1ClientRecovery} (and so of
 * {@link Amqp1ClientProtocolHandler}, the session and link classes and the
 * codec) against {@link FakeAmqp1Broker} over a real loopback socket:
 * SASL, publishing and consuming with credit and settlement, streaming a
 * large message in both directions, and reconnecting with the links
 * attached again.
 *
 * <p>Real sockets and background threads make these tests asynchronous:
 * each waits on a latch with a generous timeout rather than asserting
 * immediately.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1ClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private FakeAmqp1Broker broker;
    private Amqp1ClientRecovery client;
    private Gumdrop gumdrop;

    @Before
    public void setUp() throws IOException {
        broker = new FakeAmqp1Broker();
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
    }

    @After
    public void tearDown() throws InterruptedException {
        if (client != null) {
            client.close();
        }
        if (broker != null) {
            broker.close();
        }
        gumdrop.shutdown();
        gumdrop.join();
    }

    private Amqp1ClientRecovery newClient() {
        return new Amqp1ClientRecovery("localhost", broker.getPort())
                .recoveryPolicy(new Amqp1RecoveryPolicy().withInitialDelayMs(50).withMaxDelayMs(200));
    }

    /**
     * Runs {@code task} on the client's event loop and waits for it: the
     * client's links are not thread-safe, so the test thread goes through
     * {@link Amqp1ClientRecovery#execute} rather than calling them directly.
     */
    private void onLoop(final Runnable task) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
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
        await(done, "a task to run on the event loop");
        if (failure.get() != null) {
            throw new AssertionError("task failed on the event loop: " + failure.get(), failure.get());
        }
    }

    private void send(final Amqp1Sender sender, final byte[] tag, final MessageProperties props,
            final String body) throws InterruptedException {
        onLoop(new Runnable() {
            @Override
            public void run() {
                sender.send(tag, props, null, ByteBuffer.wrap(bytes(body)));
            }
        });
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

    // ── tests ──

    @Test
    public void testPublishAndConsumeRoundTrip() throws Exception {
        client = newClient();
        final Receiving receiving = new Receiving(10);
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachReceiver("in", "queue://orders", receiving);
                session.attachSender("out", "queue://orders", sending);
            }
        });

        await(sending.credit, "link credit");
        MessageProperties props = new MessageProperties();
        props.setSubject("greeting");
        send(sending.sender, bytes("t-1"), props, "hello, broker");

        await(receiving.messages, "the message");
        assertEquals("hello, broker", new String(receiving.body(0), StandardCharsets.UTF_8));
        assertEquals("greeting", receiving.subjects.get(0));
        await(sending.outcome, "the outcome");
        assertEquals(DeliveryState.Type.ACCEPTED, sending.lastState.getType());
        assertTrue(sending.lastSettled);
        assertEquals(1, broker.messagesReceived());
    }

    @Test
    public void testReceiverGetsMessagesQueuedBeforeItAttached() throws Exception {
        broker.enqueue("queue://backlog", bytes("one"));
        broker.enqueue("queue://backlog", bytes("two"));
        client = newClient();
        final Receiving receiving = new Receiving(10, 2);
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachReceiver("in", "queue://backlog", receiving);
            }
        });
        await(receiving.messages, "both messages");
        assertEquals("one", new String(receiving.body(0), StandardCharsets.UTF_8));
        assertEquals("two", new String(receiving.body(1), StandardCharsets.UTF_8));
    }

    @Test
    public void testLargeMessageIsStreamedBothWays() throws Exception {
        client = newClient();
        final Receiving receiving = new Receiving(5);
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachReceiver("in", "queue://big", receiving);
                session.attachSender("out", "queue://big", sending);
            }
        });
        await(sending.credit, "link credit");

        final byte[] body = pattern(3 * 1024 * 1024);
        onLoop(new Runnable() {
            @Override
            public void run() {
                Amqp1OutgoingDelivery d = sending.sender.startDelivery(bytes("big"), null, null,
                        null, false);
                for (int off = 0; off < body.length; off += 65536) {
                    d.write(ByteBuffer.wrap(body, off, Math.min(65536, body.length - off)));
                }
                d.finish();
            }
        });

        await(receiving.messages, "the large message");
        assertArrayEquals(body, receiving.body(0));
        assertTrue("delivered to the application in many chunks, not one: "
                + receiving.chunks.get(), receiving.chunks.get() > 20);
        await(sending.outcome, "the outcome");
        assertEquals(DeliveryState.Type.ACCEPTED, sending.lastState.getType());
    }

    @Test
    public void testAnonymousAuthentication() throws Exception {
        client = newClient();
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("out", "queue://q", sending);
            }
        });
        await(sending.credit, "link credit");
    }

    @Test
    public void testPlainAuthenticationWithRequiredCredentials() throws Exception {
        broker.requireCredentials("appuser", "s3cret");
        client = newClient().credentials("appuser", "s3cret");
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("out", "queue://q", sending);
            }
        });
        await(sending.credit, "link credit");
    }

    @Test
    public void testRejectedCredentialsEndRecoveryWithoutRetrying() throws Exception {
        broker.requireCredentials("appuser", "s3cret");
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicInteger reconnects = new AtomicInteger();
        client = newClient().credentials("appuser", "wrong")
                .recoveryListener(new Amqp1RecoveryListener() {
                    @Override
                    public void onReconnecting(int attempt, long delayMs) {
                        reconnects.incrementAndGet();
                    }

                    @Override
                    public void onRecoveryFailed(Exception cause) {
                        failed.countDown();
                    }
                });
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                throw new AssertionError("must not connect with bad credentials");
            }
        });
        await(failed, "recovery to be abandoned");
        Thread.sleep(400); // a retry would be scheduled well within this
        assertEquals(1, broker.connectionsAccepted());
        assertEquals(0, reconnects.get());
    }

    @Test
    public void testRefusedLinkIsReportedAndNotRetried() throws Exception {
        broker.addMissingAddress("queue://missing");
        client = newClient();
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("out", "queue://missing", sending);
            }
        });
        await(sending.detached, "the detach");
        assertEquals(Amqp1Error.NOT_FOUND, sending.detachError.getCondition());
        assertTrue("the broker closed it", sending.detachClosed);
        assertNull(sending.sender);
        Thread.sleep(400);
        assertEquals("a link the broker refused is not attached again", 1, broker.attachesReceived());
        assertEquals(1, broker.connectionsAccepted());
    }

    @Test
    public void testIdleTimeOutKeepsTheConnectionAlive() throws Exception {
        broker.setIdleTimeOut(300);
        client = newClient();
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("out", "queue://q", sending);
            }
        });
        await(sending.credit, "link credit");
        Thread.sleep(1200);
        assertTrue("heartbeats were sent: " + broker.heartbeatsReceived(),
                broker.heartbeatsReceived() >= 3);
        assertEquals("the connection was never lost", 1, broker.connectionsAccepted());
    }

    // ── recovery ──

    @Test
    public void testLinksAreAttachedAgainAfterTheConnectionDrops() throws Exception {
        final CountDownLatch recovered = new CountDownLatch(1);
        final AtomicInteger lost = new AtomicInteger();
        client = newClient().recoveryListener(new Amqp1RecoveryListener() {
            @Override
            public void onConnectionLost(Exception cause) {
                lost.incrementAndGet();
            }

            @Override
            public void onRecovered() {
                recovered.countDown();
            }
        });
        final Receiving receiving = new Receiving(10);
        final Sending sending = new Sending();
        final AtomicInteger firstConnects = new AtomicInteger();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                firstConnects.incrementAndGet();
                session.attachReceiver("in", "queue://durable", receiving);
                session.attachSender("out", "queue://durable", sending);
            }
        });
        await(sending.credit, "initial credit");
        assertEquals(1, receiving.attached.get());
        assertEquals(1, sending.attached.get());

        sending.resetCredit();
        broker.dropConnections();
        await(recovered, "recovery");
        await(sending.credit, "credit after recovery");

        assertEquals("onFirstConnect is not repeated", 1, firstConnects.get());
        assertTrue(lost.get() >= 1);
        assertEquals("2 connections in total", 2, broker.connectionsAccepted());
        assertEquals("the sender was attached again", 2, sending.attached.get());
        assertTrue("the loss was reported as a detach, not a close", sending.detachedOpen.get() >= 1);
        assertEquals(0, sending.detachedClosed.get());
        // the receiver is attached again and must be given credit again
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (receiving.attached.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, receiving.attached.get());

        // and both still work, through the same objects the application holds
        send(sending.sender, bytes("t-2"), null, "after recovery");
        await(receiving.messages, "a message after recovery");
        assertEquals("after recovery", new String(receiving.body(0), StandardCharsets.UTF_8));
    }

    @Test
    public void testStableLinkObjectWorksAcrossRecovery() throws Exception {
        final CountDownLatch recovered = new CountDownLatch(1);
        client = newClient().recoveryListener(new Amqp1RecoveryListener() {
            @Override
            public void onRecovered() {
                recovered.countDown();
            }
        });
        final Sending sending = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("out", "queue://q", sending);
            }
        });
        await(sending.credit, "initial credit");
        Amqp1Sender held = sending.sender; // kept from before the loss

        sending.resetCredit();
        broker.dropConnections();
        await(recovered, "recovery");
        await(sending.credit, "credit after recovery");

        send(held, bytes("t"), null, "via the old reference");
        await(sending.outcome, "the outcome");
        assertEquals(DeliveryState.Type.ACCEPTED, sending.lastState.getType());
    }

    @Test
    public void testRecoveryGivesUpAfterMaxAttempts() throws Exception {
        int port = broker.getPort();
        broker.close(); // nothing is listening on the port any more
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicInteger attempts = new AtomicInteger();
        client = new Amqp1ClientRecovery("localhost", port)
                .recoveryPolicy(new Amqp1RecoveryPolicy().withInitialDelayMs(30).withMaxAttempts(3))
                .recoveryListener(new Amqp1RecoveryListener() {
                    @Override
                    public void onReconnecting(int attempt, long delayMs) {
                        attempts.incrementAndGet();
                    }

                    @Override
                    public void onRecoveryFailed(Exception cause) {
                        failed.countDown();
                    }
                });
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                throw new AssertionError("nothing to connect to");
            }
        });
        await(failed, "recovery to be abandoned");
        assertEquals(3, attempts.get());
        broker = null;
    }

    @Test
    public void testClosedLinkIsNotRecovered() throws Exception {
        final CountDownLatch recovered = new CountDownLatch(1);
        client = newClient().recoveryListener(new Amqp1RecoveryListener() {
            @Override
            public void onRecovered() {
                recovered.countDown();
            }
        });
        final Sending kept = new Sending();
        final Sending closedByApp = new Sending();
        client.connect(gumdrop, new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                session.attachSender("kept", "queue://a", kept);
                session.attachSender("closed", "queue://b", closedByApp);
            }
        });
        await(kept.credit, "credit");
        await(closedByApp.credit, "credit");
        onLoop(new Runnable() {
            @Override
            public void run() {
                closedByApp.sender.detach(null, true);
            }
        });
        await(closedByApp.detached, "the close to complete");
        assertEquals(1, closedByApp.detachedClosed.get());

        kept.resetCredit();
        broker.dropConnections();
        await(recovered, "recovery");
        await(kept.credit, "credit after recovery");
        Thread.sleep(300);
        assertEquals("only the link still wanted came back", 1, closedByApp.attached.get());
        assertEquals(2, kept.attached.get());
    }

    // ── handlers ──

    private static final class Sending implements Amqp1SenderHandler {
        volatile Amqp1Sender sender;
        volatile CountDownLatch credit = new CountDownLatch(1);
        volatile CountDownLatch outcome = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(1);
        volatile DeliveryState lastState;
        volatile boolean lastSettled;
        volatile Amqp1Error detachError;
        volatile boolean detachClosed;
        final AtomicInteger attached = new AtomicInteger();
        final AtomicInteger detachedOpen = new AtomicInteger();
        final AtomicInteger detachedClosed = new AtomicInteger();

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
            lastSettled = settled;
            outcome.countDown();
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            detachError = error;
            detachClosed = closed;
            if (closed) {
                detachedClosed.incrementAndGet();
            } else {
                detachedOpen.incrementAndGet();
            }
            detached.countDown();
        }
    }

    private static final class Receiving implements Amqp1ReceiverHandler {
        private final long credit;
        final CountDownLatch messages;
        final AtomicInteger attached = new AtomicInteger();
        final AtomicInteger chunks = new AtomicInteger();
        final List<String> subjects = new CopyOnWriteArrayList<String>();
        private final List<byte[]> bodies = new CopyOnWriteArrayList<byte[]>();
        private ByteArrayOutputStream current;

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
        public void handleAttached(Amqp1Receiver receiver, Attach peerAttach) {
            attached.incrementAndGet();
            receiver.addCredit(credit); // on every attachment, including after recovery
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery delivery) {
            current = new ByteArrayOutputStream();
            delivery.accept();
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
        }

        @Override
        public void applicationProperties(Map<Object, Object> properties) {
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
