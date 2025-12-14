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

/**
 * Handler for Redis Pub/Sub messages.
 *
 * <p>When a client subscribes to channels, it enters Pub/Sub mode and
 * can only execute Pub/Sub commands. Messages arrive asynchronously
 * through this handler.
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * session.subscribe("news", new MessageHandler() {
 *     public void handleMessage(String channel, byte[] message) {
 *         String text = new String(message, StandardCharsets.UTF_8);
 *         System.out.println("Received on " + channel + ": " + text);
 *     }
 *
 *     public void handlePatternMessage(String pattern, String channel, byte[] message) {
 *         // For psubscribe patterns
 *     }
 *
 *     public void handleSubscribed(String channel, int subscriptionCount) {
 *         System.out.println("Subscribed to " + channel);
 *     }
 *
 *     public void handleUnsubscribed(String channel, int subscriptionCount) {
 *         System.out.println("Unsubscribed from " + channel);
 *     }
 *
 *     public void handleError(String error) {
 *         System.err.println("Pub/Sub error: " + error);
 *     }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MessageHandler {

    /**
     * Called when a message is received on a subscribed channel.
     *
     * @param channel the channel the message was published to
     * @param message the message content
     */
    void handleMessage(String channel, byte[] message);

    /**
     * Called when a message matches a pattern subscription.
     *
     * <p>This is called for channels matching patterns subscribed via
     * {@code PSUBSCRIBE}.
     *
     * @param pattern the pattern that matched
     * @param channel the actual channel the message was published to
     * @param message the message content
     */
    void handlePatternMessage(String pattern, String channel, byte[] message);

    /**
     * Called when a subscription is confirmed.
     *
     * @param channel the channel subscribed to
     * @param subscriptionCount the total number of subscriptions
     */
    void handleSubscribed(String channel, int subscriptionCount);

    /**
     * Called when a pattern subscription is confirmed.
     *
     * @param pattern the pattern subscribed to
     * @param subscriptionCount the total number of subscriptions
     */
    void handlePatternSubscribed(String pattern, int subscriptionCount);

    /**
     * Called when an unsubscription is confirmed.
     *
     * @param channel the channel unsubscribed from
     * @param subscriptionCount the remaining subscription count
     */
    void handleUnsubscribed(String channel, int subscriptionCount);

    /**
     * Called when a pattern unsubscription is confirmed.
     *
     * @param pattern the pattern unsubscribed from
     * @param subscriptionCount the remaining subscription count
     */
    void handlePatternUnsubscribed(String pattern, int subscriptionCount);

    /**
     * Called when an error occurs in Pub/Sub mode.
     *
     * @param error the error message
     */
    void handleError(String error);

}

