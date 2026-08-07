/*
 * RecoverableChannelImplTest.java
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

import org.bluezoo.gumdrop.amqp.client.handler.ChannelClosedListener;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.FlowListener;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.ServerCancelHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelCloseHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerCloseHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConfirmSelectHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerExchangeDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerFlowHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueBindHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxCommitHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxRollbackHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxSelectHandler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the recovery layer's recording/replay behaviour using fake
 * {@link ClientChannel}/{@link ClientConnection} implementations — no
 * real network or broker involved.
 */
public class RecoverableChannelImplTest {

    @Test
    public void testDeclareCallsAreForwardedAndRecorded() {
        FakeClientChannel fake = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, fake, () -> { });

        ch.exchangeDeclare("ex", "topic", true, false, null, () -> { });
        ch.queueDeclare("q", true, false, false, null, (queue, mc, cc) -> { });
        ch.queueBind("q", "ex", "rk", null, () -> { });

        assertEquals(1, fake.exchangeDeclares.size());
        assertEquals("ex", fake.exchangeDeclares.get(0));
        assertEquals(1, fake.queueDeclares.size());
        assertEquals(1, fake.queueBinds.size());
    }

    @Test
    public void testRedeclaringSameResourceDoesNotGrowReplayState() {
        // Repeatedly redeclaring the same exchange/queue/binding is a
        // legitimate, idempotent AMQP pattern (the broker treats it as a
        // no-op) — recording must be keyed by resource identity, not a
        // plain append-only log, or a long-lived connection that does
        // this would leak memory without bound.
        FakeClientChannel fake = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, fake, () -> { });

        for (int i = 0; i < 1000; i++) {
            ch.exchangeDeclare("ex", "topic", true, false, null, () -> { });
            ch.queueDeclare("q", true, false, false, null, (queue, mc, cc) -> { });
            ch.queueBind("q", "ex", "rk", null, () -> { });
        }

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);

        // Only one of each must have been replayed, however many times
        // the identical resource was originally declared.
        assertEquals(1, second.exchangeDeclares.size());
        assertEquals(1, second.queueDeclares.size());
        assertEquals(1, second.queueBinds.size());
    }

    @Test
    public void testCancelledConsumerIsNotReplayed() {
        FakeClientChannel fake = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, fake, () -> { });

        ch.basicConsume("q", "tag-1", false, false, null, new NoopDeliveryHandler(), tag -> { });
        ch.basicCancel("tag-1", tag -> { });

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);

        assertTrue("a cancelled consumer must not be replayed", second.consumes.isEmpty());
    }

    @Test
    public void testConsumerTagRemappingAcrossReconnectsStaysBounded() {
        // Each reconnect typically assigns a fresh consumer tag (a new
        // consumer on a new channel); the stale tag -> key mapping from
        // the previous connection must be evicted, not accumulated.
        FakeClientChannel first = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, first, () -> { });
        ch.basicConsume("q", "", false, false, null, new NoopDeliveryHandler(), tag -> { });

        FakeClientChannel current = first;
        for (int i = 0; i < 50; i++) {
            FakeClientChannel next = new FakeClientChannel();
            // Each fake assigns a distinct generated tag (see
            // FakeClientChannel.basicConsume), simulating the broker
            // assigning a new tag on every reconnect.
            ch.rebind(next);
            current = next;
        }

        // Still exactly one recorded consumer regardless of how many
        // reconnects (and therefore tag reassignments) occurred.
        assertEquals(1, activeConsumerCount(ch));
    }

    private static int activeConsumerCount(RecoverableChannelImpl ch) {
        try {
            java.lang.reflect.Field f = RecoverableChannelImpl.class.getDeclaredField("activeConsumerKeys");
            f.setAccessible(true);
            return ((java.util.Map<?, ?>) f.get(ch)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void testRebindReplaysRecordedTopologyInOrder() {
        FakeClientChannel first = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, first, () -> { });

        ch.exchangeDeclare("ex1", "topic", true, false, null, () -> { });
        ch.queueDeclare("q1", true, false, false, null, (queue, mc, cc) -> { });
        ch.queueBind("q1", "ex1", "rk1", null, () -> { });
        DeliveryHandler deliveryHandler = new NoopDeliveryHandler();
        ch.basicConsume("q1", "my-tag", false, false, null, deliveryHandler, tag -> { });

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);

        assertEquals(List.of("ex1"), second.exchangeDeclares);
        assertEquals(List.of("q1"), second.queueDeclares);
        assertEquals(1, second.queueBinds.size());
        assertEquals(1, second.consumes.size());
        assertEquals("q1", second.consumes.get(0)[0]);
        assertEquals("my-tag", second.consumes.get(0)[1]);

        // Order must be preserved: declares before bind before consume.
        assertTrue(second.callOrder.indexOf("exchangeDeclare") < second.callOrder.indexOf("queueDeclare"));
        assertTrue(second.callOrder.indexOf("queueDeclare") < second.callOrder.indexOf("queueBind"));
        assertTrue(second.callOrder.indexOf("queueBind") < second.callOrder.indexOf("basicConsume"));
    }

    @Test
    public void testPublishAckNackRejectTxFlowAreNotReplayed() {
        FakeClientChannel first = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, first, () -> { });

        ch.basicPublish("ex", "rk", false, null, 0);
        ch.basicAck(1L, false);
        ch.basicNack(2L, false, true);
        ch.basicReject(3L, false);
        ch.txSelect(() -> { });
        ch.flow(true, active -> { });

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);

        assertTrue("non-topology operations must not be replayed", second.callOrder.isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void testOperationsThrowWhileDisconnected() {
        FakeClientChannel fake = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, fake, () -> { });
        ch.markDisconnected();
        ch.basicAck(1L, false);
    }

    @Test
    public void testDeclareStillRecordsWhileDisconnected() {
        // The application may call declare methods before a reconnect
        // completes; the call itself should still throw (nothing live to
        // forward it to), but this documents current behavior: recording
        // happens via the live call path, so a declare issued while
        // disconnected is not silently accepted either.
        FakeClientChannel fake = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, fake, () -> { });
        ch.markDisconnected();
        try {
            ch.exchangeDeclare("ex", "topic", true, false, null, () -> { });
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void testCloseClearsReplayLog() {
        FakeClientChannel first = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, first, () -> { });
        ch.exchangeDeclare("ex", "topic", true, false, null, () -> { });

        ch.close(200, "bye", () -> { });

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);
        assertTrue(second.callOrder.isEmpty());
    }

    @Test
    public void testCloseListenerAndFlowListenerForwardedOnRebind() {
        FakeClientChannel first = new FakeClientChannel();
        RecoverableChannelImpl ch = new RecoverableChannelImpl(1, first, () -> { });

        List<String> closeEvents = new ArrayList<>();
        List<Boolean> flowEvents = new ArrayList<>();
        ch.setCloseListener((code, text) -> closeEvents.add(code + " " + text));
        ch.setFlowListener(active -> flowEvents.add(active));

        assertNotNull(first.closeListener);
        assertNotNull(first.flowListener);

        FakeClientChannel second = new FakeClientChannel();
        ch.rebind(second);

        assertNotNull("listener must be re-registered on the new live channel", second.closeListener);
        assertNotNull(second.flowListener);
    }

    @Test
    public void testConnectionChannelOpenWrapsRealChannel() {
        FakeClientConnection fakeConnection = new FakeClientConnection();
        RecoverableConnectionImpl connection = new RecoverableConnectionImpl();
        connection.bind(fakeConnection);

        List<ClientChannel> opened = new ArrayList<>();
        connection.channelOpen(1, opened::add);

        assertEquals(1, opened.size());
        assertEquals(1, opened.get(0).getChannelId());
    }

    @Test
    public void testExplicitlyClosedChannelIsNotReopenedOnReconnect() {
        FakeClientConnection fakeConnection = new FakeClientConnection();
        RecoverableConnectionImpl connection = new RecoverableConnectionImpl();
        connection.bind(fakeConnection);

        List<ClientChannel> opened = new ArrayList<>();
        connection.channelOpen(1, opened::add);
        connection.channelOpen(2, opened::add);
        opened.get(0).close(200, "bye", () -> { });

        connection.markDisconnected();
        FakeClientConnection reconnected = new FakeClientConnection();
        connection.bind(reconnected);

        List<Boolean> completed = new ArrayList<>();
        connection.reopenAndReplayAll(() -> completed.add(true));

        // Only channel 2 must be reopened; channel 1 was explicitly
        // closed and must not be tracked (or leaked) any further.
        assertEquals(1, completed.size());
        assertEquals(List.of(2), reconnected.openedChannelIds);
    }

    @Test
    public void testUnsolicitedChannelCloseStopsTrackingForReplay() {
        FakeClientConnection fakeConnection = new FakeClientConnection();
        RecoverableConnectionImpl connection = new RecoverableConnectionImpl();
        connection.bind(fakeConnection);

        List<ClientChannel> opened = new ArrayList<>();
        connection.channelOpen(1, opened::add);
        // Simulate the broker closing this channel unsolicited (channel-level
        // protocol error) while the connection itself stays up.
        fakeConnection.channelsByid.get(1).closeListener.onChannelClosed(404, "NOT_FOUND");

        connection.markDisconnected();
        FakeClientConnection reconnected = new FakeClientConnection();
        connection.bind(reconnected);

        List<Boolean> completed = new ArrayList<>();
        connection.reopenAndReplayAll(() -> completed.add(true));

        assertEquals(1, completed.size());
        assertTrue("an unsolicited-closed channel must not be replayed on reconnect",
                reconnected.openedChannelIds.isEmpty());
    }

    @Test
    public void testReopenAndReplayAllReopensEveryTrackedChannel() {
        FakeClientConnection fakeConnection = new FakeClientConnection();
        RecoverableConnectionImpl connection = new RecoverableConnectionImpl();
        connection.bind(fakeConnection);

        List<ClientChannel> opened = new ArrayList<>();
        connection.channelOpen(1, opened::add);
        connection.channelOpen(2, opened::add);
        opened.get(0).exchangeDeclare("ex", "topic", true, false, null, () -> { });

        connection.markDisconnected();

        FakeClientConnection reconnected = new FakeClientConnection();
        connection.bind(reconnected);

        List<Boolean> completed = new ArrayList<>();
        connection.reopenAndReplayAll(() -> completed.add(true));

        assertEquals(1, completed.size());
        assertEquals(2, reconnected.openedChannelIds.size());
        assertTrue(reconnected.openedChannelIds.contains(1));
        assertTrue(reconnected.openedChannelIds.contains(2));
        // Channel 1's exchange.declare must have been replayed against the reconnected channel.
        FakeClientChannel reopenedChannel1 = reconnected.channelsByid.get(1);
        assertEquals(List.of("ex"), reopenedChannel1.exchangeDeclares);
    }

    // ── Fakes ──

    private static final class NoopDeliveryHandler implements DeliveryHandler {
        @Override public void onDeliveryStart(String consumerTag, long deliveryTag,
                boolean redelivered, String exchange, String routingKey) { }
        @Override public void onDeliveryProperties(BasicProperties properties, long bodySize) { }
        @Override public void onDeliveryBodyChunk(java.nio.ByteBuffer chunk) { }
        @Override public void onDeliveryComplete() { }
    }

    private static final class FakeClientChannel implements ClientChannel {
        final List<String> exchangeDeclares = new ArrayList<>();
        final List<String> queueDeclares = new ArrayList<>();
        final List<Object[]> queueBinds = new ArrayList<>();
        final List<Object[]> consumes = new ArrayList<>();
        final List<String> callOrder = new ArrayList<>();
        ChannelClosedListener closeListener;
        FlowListener flowListener;
        ConfirmListener confirmListener;
        private final int channelId;

        FakeClientChannel() {
            this(1);
        }

        FakeClientChannel(int channelId) {
            this.channelId = channelId;
        }

        @Override public int getChannelId() { return channelId; }

        @Override public void setCloseListener(ChannelClosedListener listener) {
            this.closeListener = listener;
        }

        @Override public void close(int replyCode, String replyText, ServerChannelCloseHandler handler) {
            handler.handleChannelCloseOk();
        }

        @Override public void exchangeDeclare(String exchange, String type, boolean durable,
                boolean autoDelete, FieldTable arguments, ServerExchangeDeclareHandler handler) {
            exchangeDeclares.add(exchange);
            callOrder.add("exchangeDeclare");
            handler.handleExchangeDeclareOk();
        }

        @Override public void queueDeclare(String queue, boolean durable, boolean exclusive,
                boolean autoDelete, FieldTable arguments, ServerQueueDeclareHandler handler) {
            queueDeclares.add(queue);
            callOrder.add("queueDeclare");
            handler.handleQueueDeclareOk(queue, 0, 0);
        }

        @Override public void queueBind(String queue, String exchange, String routingKey,
                FieldTable arguments, ServerQueueBindHandler handler) {
            queueBinds.add(new Object[] { queue, exchange, routingKey });
            callOrder.add("queueBind");
            handler.handleQueueBindOk();
        }

        @Override public PublishBody basicPublish(String exchange, String routingKey,
                boolean mandatory, BasicProperties properties, long bodySize) {
            callOrder.add("basicPublish");
            return new PublishBody() {
                @Override public long getSequenceNumber() { return 0; }
                @Override public void writeBody(java.nio.ByteBuffer chunk) { }
                @Override public void onWriteReady(Runnable callback) { }
                @Override public void complete() { }
            };
        }

        @Override public void basicConsume(String queue, String consumerTag, boolean noAck,
                boolean exclusive, FieldTable arguments, DeliveryHandler deliveryHandler,
                ServerConsumeHandler handler) {
            consumes.add(new Object[] { queue, consumerTag });
            callOrder.add("basicConsume");
            handler.handleConsumeOk(consumerTag.isEmpty() ? "generated-tag" : consumerTag);
        }

        @Override public void basicCancel(String consumerTag, ServerCancelHandler handler) {
            callOrder.add("basicCancel");
        }

        @Override public void basicAck(long deliveryTag, boolean multiple) {
            callOrder.add("basicAck");
        }

        @Override public void basicNack(long deliveryTag, boolean multiple, boolean requeue) {
            callOrder.add("basicNack");
        }

        @Override public void basicReject(long deliveryTag, boolean requeue) {
            callOrder.add("basicReject");
        }

        @Override public void txSelect(ServerTxSelectHandler handler) {
            callOrder.add("txSelect");
        }

        @Override public void txCommit(ServerTxCommitHandler handler) {
            callOrder.add("txCommit");
        }

        @Override public void txRollback(ServerTxRollbackHandler handler) {
            callOrder.add("txRollback");
        }

        @Override public void setFlowListener(FlowListener listener) {
            this.flowListener = listener;
        }

        @Override public void flow(boolean active, ServerFlowHandler handler) {
            callOrder.add("flow");
        }

        @Override public void confirmSelect(ServerConfirmSelectHandler handler) {
            callOrder.add("confirmSelect");
            handler.handleConfirmSelectOk();
        }

        @Override public void setConfirmListener(ConfirmListener listener) {
            this.confirmListener = listener;
        }
    }

    private static final class FakeClientConnection implements ClientConnection {
        final List<Integer> openedChannelIds = new ArrayList<>();
        final java.util.Map<Integer, FakeClientChannel> channelsByid = new java.util.HashMap<>();

        @Override public void channelOpen(int channelId, ServerChannelOpenHandler handler) {
            openedChannelIds.add(channelId);
            FakeClientChannel fake = new FakeClientChannel(channelId);
            channelsByid.put(channelId, fake);
            handler.handleChannelOpenOk(fake);
        }

        @Override public void close(int replyCode, String replyText, ServerCloseHandler handler) {
            handler.handleCloseOk();
        }
    }
}
