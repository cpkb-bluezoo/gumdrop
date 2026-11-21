/*
 * MessageHandler.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

/**
 * Handler for Redis Pub/Sub deliveries (RESP spec - "Pub/Sub").
 *
 * <p>When a client subscribes to channels, it enters Pub/Sub mode and can
 * only execute Pub/Sub commands. Messages then arrive asynchronously. The
 * body of a message is a payload, whose size is the publisher's choice, so
 * it is delivered as chunks and never held by the library:
 * {@link #messageBegin}, any number of {@link #messageContent} calls in order,
 * then {@link #messageEnd()}. The channel and pattern that name the message
 * are identifiers and arrive whole in the {@link MessageHeader}, up to the
 * connection's maximum field length; a message whose channel or pattern is
 * longer is dropped and reported to {@link #handleError}, and the messages
 * after it are not disturbed.
 *
 * <p>A handler steers each message with the
 * {@link org.bluezoo.gumdrop.redis.codec.ReplyFlow} it returns: skip drops
 * the rest of that message only, and pause stops reading on the connection
 * until {@link ReplyStream#resume()}. A subscriber that needs a whole
 * message accumulates it itself, where the limit and the cost are visible.
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * session.subscribe(new MessageHandler() {
 *     private int size;
 *
 *     public ReplyFlow messageBegin(MessageHeader header, long length, ReplyStream stream) {
 *         size = 0;
 *         return length > LIMIT ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
 *     }
 *     public ReplyFlow messageContent(ByteBuffer chunk) {
 *         size += chunk.remaining();
 *         chunk.position(chunk.limit());
 *         return ReplyFlow.CONTINUE;
 *     }
 *     public void messageEnd() {
 *         System.out.println("a message of " + size + " bytes");
 *     }
 *     // ...
 * }, "news");
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MessageHandler {

    /**
     * A message begins. Called for a message on a subscribed channel, a
     * channel that matched a subscribed pattern, or a subscribed shard
     * channel, as the header says.
     *
     * @param header the channel, and the pattern if there was one
     * @param length the length of the body in bytes, or -1 if the server did
     * not say
     * @param stream a handle to resume a paused connection, or to give up
     * the message
     * @return continue, pause, or skip the rest of this message
     */
    ReplyFlow messageBegin(MessageHeader header, long length, ReplyStream stream);

    /**
     * The next bytes of the message body, never empty. The buffer is valid
     * only during the call: copy what is wanted afterwards.
     *
     * @param chunk the bytes, from its position to its limit
     * @return what to do next
     */
    ReplyFlow messageContent(ByteBuffer chunk);

    /**
     * The message is complete.
     */
    void messageEnd();

    /**
     * Called when a subscription is confirmed.
     * @param channel the channel subscribed to
     * @param subscriptionCount the total number of subscriptions
     */
    void handleSubscribed(String channel, int subscriptionCount);

    /**
     * Called when a pattern subscription is confirmed.
     * @param pattern the pattern subscribed to
     * @param subscriptionCount the total number of subscriptions
     */
    void handlePatternSubscribed(String pattern, int subscriptionCount);

    /**
     * Called when an unsubscription is confirmed.
     * @param channel the channel unsubscribed from
     * @param subscriptionCount the remaining subscription count
     */
    void handleUnsubscribed(String channel, int subscriptionCount);

    /**
     * Called when a pattern unsubscription is confirmed.
     * @param pattern the pattern unsubscribed from
     * @param subscriptionCount the remaining subscription count
     */
    void handlePatternUnsubscribed(String pattern, int subscriptionCount);

    /**
     * Called when a shard channel subscription is confirmed.
     * @param channel the shard channel subscribed to
     * @param subscriptionCount the number of shard channels subscribed to
     */
    default void handleShardSubscribed(String channel, int subscriptionCount) {
    }

    /**
     * Called when a shard channel is unsubscribed from. Besides confirming
     * an {@code SUNSUBSCRIBE}, the server does this on its own when the
     * channel's slot moves to another node.
     * @param channel the shard channel
     * @param subscriptionCount the number of shard channels still subscribed to
     */
    default void handleShardUnsubscribed(String channel, int subscriptionCount) {
    }

    /**
     * Called when an error occurs in Pub/Sub mode, including a message
     * dropped because its channel or pattern was too long.
     * @param error the error message
     */
    void handleError(String error);

}
