/*
 * RecoverableChannelImpl.java
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.amqp.client.handler.ChannelClosedListener;
import org.bluezoo.gumdrop.amqp.client.handler.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.handler.ConfirmListener;
import org.bluezoo.gumdrop.amqp.client.handler.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.handler.FlowListener;
import org.bluezoo.gumdrop.amqp.client.handler.PublishBody;
import org.bluezoo.gumdrop.amqp.client.handler.ServerCancelHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelCloseHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConfirmSelectHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerExchangeDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerFlowHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueBindHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerQueueDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxCommitHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxRollbackHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerTxSelectHandler;

/**
 * A {@link ClientChannel} whose identity survives reconnects.
 *
 * <p>Every {@code exchangeDeclare}/{@code queueDeclare}/{@code queueBind}/
 * {@code basicConsume} call is recorded — keyed by the resource it
 * declares, not appended to an ever-growing log — as well as forwarded
 * to the live underlying channel, so that after a reconnect {@link
 * #rebind} can replay the current topology against the new one before
 * the application resumes using it — see {@link
 * org.bluezoo.gumdrop.amqp.client.AMQPClientRecovery}.
 *
 * <p>Keying by resource identity (not a plain append-only list) keeps
 * memory bounded by the number of <em>distinct</em> exchanges/queues/
 * bindings/consumers the application has ever created on this channel,
 * not by how many times it called these methods — redeclaring the same
 * exchange or queue repeatedly (a legitimate, idiomatic AMQP pattern,
 * since {@code declare} is itself idempotent) costs nothing extra here
 * either, exactly mirroring the broker's own idempotent-declare
 * semantics rather than a naive call-by-call recording that would grow
 * without bound over a long-lived connection.
 *
 * <p>Publish, ack/nack/reject, and flow are <strong>not</strong>
 * recorded — those are per-message or transient and replaying them
 * after a reconnect would be meaningless or wrong; they simply delegate
 * to whatever channel is currently live and throw if none is (the
 * application is expected to handle publish failures during an outage
 * itself, e.g. via publisher confirms).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RecoverableChannelImpl implements ClientChannel {

    private static final Logger LOGGER = Logger.getLogger(RecoverableChannelImpl.class.getName());

    private interface ReplayOp {
        void replay(ClientChannel liveChannel);
    }

    private final int channelId;
    private volatile ClientChannel live;

    // Keyed by resource identity so redeclaring the same resource (a
    // normal, idempotent AMQP pattern) doesn't grow these without bound —
    // see the class-level note. LinkedHashMap to replay in first-declared
    // order (matters for the exchange -> queue -> bind -> consume
    // dependency chain when several distinct resources are involved).
    private final Map<String, ReplayOp> exchangeDeclares = new LinkedHashMap<String, ReplayOp>();
    private final Map<String, ReplayOp> queueDeclares = new LinkedHashMap<String, ReplayOp>();
    private final Map<String, ReplayOp> queueBinds = new LinkedHashMap<String, ReplayOp>();
    private final Map<String, ReplayOp> consumers = new LinkedHashMap<String, ReplayOp>();
    private volatile ReplayOp confirmSelectOp;

    /** Synthetic keys for anonymous (server-assigned-name) queues/consumers, so distinct anonymous
     * declarations don't collide under the same key — see {@link #anonymousKey}. */
    private final AtomicLong anonymousCounter = new AtomicLong();
    /**
     * consumer tag actually assigned by the server -> the {@code
     * consumers} key it's recorded under, for {@link #basicCancel}.
     * Reconnecting typically assigns a new tag (a fresh consumer on a
     * fresh channel), so the current tag for a key changes over time;
     * {@link #recordActiveConsumerTag} evicts the old tag->key entry
     * each time so this stays bounded by the number of currently active
     * consumers rather than growing by one stale entry per reconnect.
     */
    private final Map<String, String> activeConsumerKeys = new ConcurrentHashMap<String, String>();
    private final Map<String, String> currentTagForKey = new ConcurrentHashMap<String, String>();

    private void recordActiveConsumerTag(String key, String tag) {
        String oldTag = currentTagForKey.put(key, tag);
        if (oldTag != null) {
            activeConsumerKeys.remove(oldTag);
        }
        activeConsumerKeys.put(tag, key);
    }

    private ChannelClosedListener closeListener;
    private FlowListener flowListener;
    private ConfirmListener confirmListener;
    /** Notified once this channel is done for good (closed, whether by the
     * application or unsolicited) so the owning connection can stop
     * tracking and replaying it — see {@link RecoverableConnectionImpl}. */
    private final Runnable onClosed;

    /**
     * Always installed on whichever channel is currently live — not just
     * when the application registers its own {@link ChannelClosedListener}
     * — so an <em>unsolicited</em> close (a channel-level protocol error;
     * the connection itself stays up) is never missed. Without this, {@link
     * #live} would keep pointing at a channel the broker already closed,
     * and this channel would keep getting wrongly replayed on the next
     * reconnect even though the broker will most likely reject it the
     * same way again.
     */
    private final ChannelClosedListener internalCloseListener;

    RecoverableChannelImpl(int channelId, ClientChannel live, Runnable onClosed) {
        this.channelId = channelId;
        this.live = live;
        this.onClosed = onClosed;
        this.internalCloseListener = (replyCode, replyText) -> {
            this.live = null;
            clearReplayState();
            if (this.onClosed != null) {
                this.onClosed.run();
            }
            if (this.closeListener != null) {
                this.closeListener.onChannelClosed(replyCode, replyText);
            }
        };
        live.setCloseListener(internalCloseListener);
    }

    private void clearReplayState() {
        exchangeDeclares.clear();
        queueDeclares.clear();
        queueBinds.clear();
        consumers.clear();
        activeConsumerKeys.clear();
        currentTagForKey.clear();
        confirmSelectOp = null;
    }

    private String anonymousKey() {
        return " anon-" + anonymousCounter.incrementAndGet();
    }

    /**
     * Called after a reconnect once the new connection's channel with
     * this same ID has been opened: replays the current topology against
     * it (exchanges, then queues, then bindings, then confirm mode, then
     * consumers — respecting AMQP's own dependency order), then makes it
     * the live channel for subsequent calls.
     */
    void rebind(ClientChannel newLive) {
        replayAll(exchangeDeclares, newLive);
        replayAll(queueDeclares, newLive);
        replayAll(queueBinds, newLive);
        ReplayOp confirmOp = confirmSelectOp;
        if (confirmOp != null) {
            safeReplay(confirmOp, newLive);
        }
        replayAll(consumers, newLive);
        newLive.setCloseListener(internalCloseListener);
        if (flowListener != null) {
            newLive.setFlowListener(flowListener);
        }
        if (confirmListener != null) {
            newLive.setConfirmListener(confirmListener);
        }
        this.live = newLive;
    }

    private void replayAll(Map<String, ReplayOp> ops, ClientChannel newLive) {
        for (ReplayOp op : ops.values()) {
            safeReplay(op, newLive);
        }
    }

    private void safeReplay(ReplayOp op, ClientChannel newLive) {
        try {
            op.replay(newLive);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to replay topology on channel " + channelId, e);
        }
    }

    /** Called when the whole connection drops; {@link #rebind} follows once reconnected. */
    void markDisconnected() {
        this.live = null;
    }

    private ClientChannel requireLive() {
        ClientChannel l = live;
        if (l == null) {
            throw new IllegalStateException(
                    "Channel " + channelId + " is disconnected and awaiting reconnect");
        }
        return l;
    }

    @Override
    public int getChannelId() {
        return channelId;
    }

    @Override
    public void setCloseListener(ChannelClosedListener listener) {
        // Recorded for forwarding by internalCloseListener, which stays
        // installed on the live channel regardless — see its javadoc.
        this.closeListener = listener;
    }

    @Override
    public void close(int replyCode, String replyText, ServerChannelCloseHandler handler) {
        // An application-initiated close is intentional: stop tracking
        // this channel for recovery even if a reconnect happens to be
        // racing with it. internalCloseListener does the same cleanup
        // for an unsolicited close; do it here too since a clean,
        // application-requested close doesn't necessarily trigger
        // channel.close from the broker's side in every implementation.
        clearReplayState();
        if (onClosed != null) {
            onClosed.run();
        }
        ClientChannel l = requireLive();
        live = null; // this wrapper is done; further calls should fail fast, like a closed stream.
        l.close(replyCode, replyText, handler);
    }

    @Override
    public void exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete,
            FieldTable arguments, ServerExchangeDeclareHandler handler) {
        exchangeDeclares.put(exchange, new ReplayOp() {
            @Override
            public void replay(ClientChannel liveChannel) {
                liveChannel.exchangeDeclare(exchange, type, durable, autoDelete, arguments, () -> { });
            }
        });
        requireLive().exchangeDeclare(exchange, type, durable, autoDelete, arguments, handler);
    }

    @Override
    public void queueDeclare(String queue, boolean durable, boolean exclusive, boolean autoDelete,
            FieldTable arguments, ServerQueueDeclareHandler handler) {
        String key = queue.isEmpty() ? anonymousKey() : queue;
        queueDeclares.put(key, new ReplayOp() {
            @Override
            public void replay(ClientChannel liveChannel) {
                liveChannel.queueDeclare(queue, durable, exclusive, autoDelete, arguments,
                        (q, msgCount, consumerCount) -> { });
            }
        });
        requireLive().queueDeclare(queue, durable, exclusive, autoDelete, arguments, handler);
    }

    @Override
    public void queueBind(String queue, String exchange, String routingKey, FieldTable arguments,
            ServerQueueBindHandler handler) {
        String key = queue + ' ' + exchange + ' ' + routingKey;
        queueBinds.put(key, new ReplayOp() {
            @Override
            public void replay(ClientChannel liveChannel) {
                liveChannel.queueBind(queue, exchange, routingKey, arguments, () -> { });
            }
        });
        requireLive().queueBind(queue, exchange, routingKey, arguments, handler);
    }

    @Override
    public PublishBody basicPublish(String exchange, String routingKey, boolean mandatory,
            BasicProperties properties, long bodySize) {
        return requireLive().basicPublish(exchange, routingKey, mandatory, properties, bodySize);
    }

    @Override
    public void basicConsume(String queue, String consumerTag, boolean noAck, boolean exclusive,
            FieldTable arguments, DeliveryHandler deliveryHandler, ServerConsumeHandler handler) {
        String key = consumerTag.isEmpty() ? anonymousKey() : consumerTag;
        consumers.put(key, new ReplayOp() {
            @Override
            public void replay(ClientChannel liveChannel) {
                // Replay with the originally-requested tag (possibly "" for
                // server-assigned); if the server assigns a different tag
                // than last time, deliveries still route correctly since
                // AMQPClientProtocolHandler keys consumers by whatever tag
                // basic.consume-ok actually returns, not the requested one.
                liveChannel.basicConsume(queue, consumerTag, noAck, exclusive, arguments,
                        deliveryHandler, tag -> recordActiveConsumerTag(key, tag));
            }
        });
        requireLive().basicConsume(queue, consumerTag, noAck, exclusive, arguments, deliveryHandler,
                tag -> {
                    recordActiveConsumerTag(key, tag);
                    handler.handleConsumeOk(tag);
                });
    }

    @Override
    public void basicCancel(String consumerTag, ServerCancelHandler handler) {
        String key = activeConsumerKeys.remove(consumerTag);
        if (key != null) {
            consumers.remove(key);
            currentTagForKey.remove(key);
        }
        requireLive().basicCancel(consumerTag, handler);
    }

    @Override
    public void basicAck(long deliveryTag, boolean multiple) {
        requireLive().basicAck(deliveryTag, multiple);
    }

    @Override
    public void basicNack(long deliveryTag, boolean multiple, boolean requeue) {
        requireLive().basicNack(deliveryTag, multiple, requeue);
    }

    @Override
    public void basicReject(long deliveryTag, boolean requeue) {
        requireLive().basicReject(deliveryTag, requeue);
    }

    @Override
    public void txSelect(ServerTxSelectHandler handler) {
        requireLive().txSelect(handler);
    }

    @Override
    public void txCommit(ServerTxCommitHandler handler) {
        requireLive().txCommit(handler);
    }

    @Override
    public void txRollback(ServerTxRollbackHandler handler) {
        requireLive().txRollback(handler);
    }

    @Override
    public void setFlowListener(FlowListener listener) {
        this.flowListener = listener;
        ClientChannel l = live;
        if (l != null) {
            l.setFlowListener(listener);
        }
    }

    @Override
    public void flow(boolean active, ServerFlowHandler handler) {
        requireLive().flow(active, handler);
    }

    @Override
    public void confirmSelect(ServerConfirmSelectHandler handler) {
        // A single flag, not a per-call recording: a freshly reopened
        // channel after a reconnect starts with confirms disabled again,
        // so this must be reissued to keep behaving as confirm-mode after
        // recovery — but calling confirmSelect more than once only needs
        // to be replayed once. Note that publish sequence numbers
        // necessarily restart from 1 on the new channel — any publish
        // confirms still outstanding at the moment of disconnect are
        // simply unknown/lost, which is exactly the scenario publisher
        // confirms exist to let the application detect (e.g. by tracking
        // its own outstanding-publish set).
        confirmSelectOp = new ReplayOp() {
            @Override
            public void replay(ClientChannel liveChannel) {
                liveChannel.confirmSelect(() -> { });
            }
        };
        requireLive().confirmSelect(handler);
    }

    @Override
    public void setConfirmListener(ConfirmListener listener) {
        this.confirmListener = listener;
        ClientChannel l = live;
        if (l != null) {
            l.setConfirmListener(listener);
        }
    }
}
