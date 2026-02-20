/*
 * RedisPubSubExample.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.client.IntegerResultHandler;
import org.bluezoo.gumdrop.redis.client.MessageHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;

import java.nio.charset.StandardCharsets;

/**
 * Example demonstrating Redis Pub/Sub with Gumdrop.
 *
 * <p>This example creates two Redis connections:
 * <ul>
 *   <li>A subscriber that listens for messages on channels</li>
 *   <li>A publisher that sends messages</li>
 * </ul>
 *
 * <p>Before running, ensure a Redis server is available on localhost:6379.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisPubSubExample {

    private static RedisSession publisherSession;
    private static RedisSession subscriberSession;
    private static int messagesReceived = 0;
    private static final int MESSAGES_TO_SEND = 5;
    private static String redisHost;
    private static int redisPort;

    public static void main(String[] args) throws Exception {
        System.out.println("Gumdrop Redis Pub/Sub Example");
        System.out.println("==============================");
        System.out.println();

        // Parse command line arguments
        redisHost = "localhost";
        redisPort = 6379;
        
        if (args.length >= 1) {
            redisHost = args[0];
        }
        if (args.length >= 2) {
            redisPort = Integer.parseInt(args[1]);
        }

        System.out.println("Connecting to Redis at " + redisHost + ":" + redisPort);
        System.out.println();

        // Create subscriber connection first - no Gumdrop setup needed!
        RedisClient subscriberClient = new RedisClient(redisHost, redisPort);
        subscriberClient.connect(new SubscriberHandler());
    }

    /**
     * Handler for the subscriber connection.
     */
    static class SubscriberHandler implements RedisConnectionReady {

        @Override
        public void handleReady(final RedisSession session) {
            System.out.println("[Subscriber] Connected, subscribing to channels...");
            subscriberSession = session;

            // Subscribe to channels with message handler
            session.subscribe(new MessageHandler() {
                @Override
                public void handleMessage(String channel, byte[] message) {
                    String text = new String(message, StandardCharsets.UTF_8);
                    System.out.println("[Subscriber] Message on '" + channel + "': " + text);
                    messagesReceived++;

                    if (messagesReceived >= MESSAGES_TO_SEND) {
                        System.out.println();
                        System.out.println("Received all messages! Shutting down...");
                        session.close();
                        if (publisherSession != null) {
                            publisherSession.quit();
                        }
                        // Infrastructure will auto-shutdown when all connections close
                    }
                }

                @Override
                public void handlePatternMessage(String pattern, String channel, byte[] message) {
                    String text = new String(message, StandardCharsets.UTF_8);
                    System.out.println("[Subscriber] Pattern '" + pattern + "' matched '" + 
                                       channel + "': " + text);
                }

                @Override
                public void handleSubscribed(String channel, int subscriptionCount) {
                    System.out.println("[Subscriber] Subscribed to '" + channel + 
                                       "' (total: " + subscriptionCount + ")");
                    
                    // If this is the last subscription, start publisher
                    if (subscriptionCount == 2) {
                        startPublisher();
                    }
                }

                @Override
                public void handlePatternSubscribed(String pattern, int subscriptionCount) {
                    System.out.println("[Subscriber] Pattern subscribed '" + pattern + 
                                       "' (total: " + subscriptionCount + ")");
                }

                @Override
                public void handleUnsubscribed(String channel, int subscriptionCount) {
                    System.out.println("[Subscriber] Unsubscribed from '" + channel + 
                                       "' (remaining: " + subscriptionCount + ")");
                }

                @Override
                public void handlePatternUnsubscribed(String pattern, int subscriptionCount) {
                    System.out.println("[Subscriber] Pattern unsubscribed '" + pattern + 
                                       "' (remaining: " + subscriptionCount + ")");
                }

                @Override
                public void handleError(String error) {
                    System.err.println("[Subscriber] Error: " + error);
                }
            }, "events", "notifications");
        }

        private void startPublisher() {
            System.out.println();
            System.out.println("[Publisher] Starting publisher connection...");
            
            try {
                RedisClient publisherClient = new RedisClient(redisHost, redisPort);
                publisherClient.connect(new PublisherHandler());
            } catch (Exception e) {
                System.err.println("Failed to create publisher: " + e.getMessage());
            }
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            System.out.println("[Subscriber] Connected");
        }

        @Override
        public void onDisconnected() {
            System.out.println("[Subscriber] Disconnected");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            System.out.println("[Subscriber TLS] " + info.getProtocol());
        }

        @Override
        public void onError(Exception e) {
            System.err.println("[Subscriber Error] " + e.getMessage());
        }
    }

    /**
     * Handler for the publisher connection.
     */
    static class PublisherHandler implements RedisConnectionReady {
        private int messageNumber = 0;

        @Override
        public void handleReady(final RedisSession session) {
            System.out.println("[Publisher] Connected, will send " + MESSAGES_TO_SEND + " messages...");
            System.out.println();
            publisherSession = session;
            
            // Send messages
            sendNextMessage(session);
        }

        private void sendNextMessage(final RedisSession session) {
            if (messageNumber >= MESSAGES_TO_SEND) {
                return;
            }

            final int msgNum = ++messageNumber;
            
            // Alternate between channels
            final String channel = (msgNum % 2 == 0) ? "notifications" : "events";
            String message = "Message #" + msgNum + " at " + System.currentTimeMillis();

            session.publish(channel, message, new IntegerResultHandler() {
                @Override
                public void handleResult(long subscriberCount, RedisSession s) {
                    System.out.println("[Publisher] Sent to '" + channel + 
                                       "' (reached " + subscriberCount + " subscriber(s))");
                    
                    // Send next message immediately (pipelining)
                    if (msgNum < MESSAGES_TO_SEND) {
                        sendNextMessage(s);
                    }
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[Publisher] Error: " + error);
                }
            });
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            System.out.println("[Publisher] Connected");
        }

        @Override
        public void onDisconnected() {
            System.out.println("[Publisher] Disconnected");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            System.out.println("[Publisher TLS] " + info.getProtocol());
        }

        @Override
        public void onError(Exception e) {
            System.err.println("[Publisher Error] " + e.getMessage());
        }
    }

}
