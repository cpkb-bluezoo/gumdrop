/*
 * RedisServerIntegrationExample.java
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
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.redis.client.ArrayResultHandler;
import org.bluezoo.gumdrop.redis.client.BooleanResultHandler;
import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.IntegerResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.codec.RESPValue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Example demonstrating Redis client integration within a Gumdrop server.
 *
 * <p>This example shows:
 * <ul>
 *   <li>SelectorLoop affinity - Redis client uses the same event loop as server</li>
 *   <li>Per-SelectorLoop connection pooling</li>
 *   <li>Rate limiting using Redis INCR and EXPIRE</li>
 *   <li>Session caching using Redis hashes</li>
 * </ul>
 *
 * <p>The example creates a simple TCP server on port 9000 that accepts
 * connections and uses Redis for rate limiting and session storage.
 *
 * <p>Before running, ensure a Redis server is available on localhost:6379.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisServerIntegrationExample {

    // Redis connection pool per SelectorLoop
    private static final Map<SelectorLoop, RedisSession> redisPool = 
        new ConcurrentHashMap<SelectorLoop, RedisSession>();
    
    private static String redisHost = "localhost";
    private static int redisPort = 6379;
    private static final int MAX_REQUESTS_PER_MINUTE = 10;

    public static void main(String[] args) throws Exception {
        System.out.println("Gumdrop Redis Server Integration Example");
        System.out.println("=========================================");
        System.out.println();
        
        // Parse command line arguments
        int serverPort = 9000;
        
        if (args.length >= 1) {
            serverPort = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            redisHost = args[1];
        }
        if (args.length >= 3) {
            redisPort = Integer.parseInt(args[2]);
        }

        System.out.println("Starting server on port " + serverPort);
        System.out.println("Using Redis at " + redisHost + ":" + redisPort);
        System.out.println();
        System.out.println("Test with: telnet localhost " + serverPort);
        System.out.println("Type messages and press Enter");
        System.out.println("Rate limit: " + MAX_REQUESTS_PER_MINUTE + " requests/minute per IP");
        System.out.println();

        // Get Gumdrop instance (singleton)
        Gumdrop gumdrop = Gumdrop.getInstance();

        // Add our example server
        gumdrop.addListener(new ExampleServer(serverPort));

        // Start Gumdrop
        gumdrop.start();

        // Wait for shutdown (Ctrl+C)
        gumdrop.join();
    }

    /**
     * Simple TCP server for the example.
     */
    static class ExampleServer extends TCPListener {
        private final int port;

        ExampleServer(int port) {
            this.port = port;
        }

        @Override
        protected int getPort() {
            return port;
        }

        @Override
        protected String getDescription() {
            return "RedisExample-" + port;
        }

        @Override
        protected ProtocolHandler createHandler() {
            return new ExampleHandler();
        }
    }

    /**
     * Endpoint handler that uses Redis for rate limiting and sessions.
     */
    static class ExampleHandler implements ProtocolHandler {
        private Endpoint endpoint;
        private RedisSession redis;
        private String clientIP;
        private int requestCount = 0;

        @Override
        public void connected(Endpoint ep) {
            this.endpoint = ep;
            
            // Get client IP for rate limiting
            Object addr = ep.getRemoteAddress();
            if (addr instanceof InetSocketAddress) {
                this.clientIP = ((InetSocketAddress) addr).getAddress().getHostAddress();
            } else {
                this.clientIP = "unknown";
            }

            System.out.println("[" + clientIP + "] Connected");
            
            // Get or create Redis connection for this SelectorLoop
            getRedisSession(new RedisSessionCallback() {
                @Override
                public void onSession(RedisSession session) {
                    redis = session;
                    sendMessage("Welcome! Type messages and press Enter.\r\n" +
                               "Rate limit: " + MAX_REQUESTS_PER_MINUTE + 
                               " requests/minute\r\n\r\n");
                }

                @Override
                public void onError(Exception e) {
                    System.err.println("[" + clientIP + "] Redis unavailable: " + e.getMessage());
                    sendMessage("Warning: Rate limiting disabled (Redis unavailable)\r\n\r\n");
                }
            });
        }

        @Override
        public void receive(ByteBuffer buffer) {
            // Read line from buffer
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String input = new String(data, StandardCharsets.UTF_8).trim();
            
            if (input.isEmpty()) {
                return;
            }

            System.out.println("[" + clientIP + "] Received: " + input);
            requestCount++;

            if (redis == null) {
                // Redis not available, process without rate limiting
                processRequest(input);
                return;
            }

            // Check rate limit in Redis
            checkRateLimit(input);
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // TLS established
        }

        @Override
        public void error(Exception cause) {
            System.err.println("[" + clientIP + "] Error: " + cause.getMessage());
        }

        @Override
        public void disconnected() {
            System.out.println("[" + clientIP + "] Disconnected");
        }

        /**
         * Checks rate limit using Redis INCR and EXPIRE.
         */
        private void checkRateLimit(final String input) {
            final String rateLimitKey = "ratelimit:" + clientIP;

            redis.incr(rateLimitKey, new IntegerResultHandler() {
                @Override
                public void handleResult(long count, RedisSession session) {
                    if (count == 1) {
                        // First request - set 60 second expiry
                        session.expire(rateLimitKey, 60, new BooleanResultHandler() {
                            @Override
                            public void handleResult(boolean result, RedisSession s) {
                                checkAndProcess(count, input);
                            }

                            @Override
                            public void handleError(String error, RedisSession s) {
                                checkAndProcess(count, input);
                            }
                        });
                    } else {
                        checkAndProcess(count, input);
                    }
                }

                @Override
                public void handleError(String error, RedisSession session) {
                    System.err.println("[" + clientIP + "] Rate limit error: " + error);
                    processRequest(input);
                }
            });
        }

        /**
         * Checks count against limit and processes if allowed.
         */
        private void checkAndProcess(long count, String input) {
            if (count > MAX_REQUESTS_PER_MINUTE) {
                sendMessage("Rate limit exceeded! Try again later.\r\n");
                System.out.println("[" + clientIP + "] Rate limited (count: " + count + ")");
            } else {
                // Store request in session history
                storeInSession(input);
                processRequest(input);
            }
        }

        /**
         * Stores request in Redis session history.
         */
        private void storeInSession(final String input) {
            String sessionKey = "session:" + clientIP;
            String field = "request_" + requestCount;
            
            redis.hset(sessionKey, field, input, new IntegerResultHandler() {
                @Override
                public void handleResult(long result, RedisSession session) {
                    // Set session expiry to 5 minutes
                    session.expire("session:" + clientIP, 300, new BooleanResultHandler() {
                        @Override
                        public void handleResult(boolean r, RedisSession s) { }

                        @Override
                        public void handleError(String e, RedisSession s) { }
                    });
                }

                @Override
                public void handleError(String error, RedisSession session) {
                    System.err.println("[" + clientIP + "] Session store error: " + error);
                }
            });
        }

        /**
         * Processes the request.
         */
        private void processRequest(String input) {
            // Handle special commands
            if ("quit".equalsIgnoreCase(input)) {
                sendMessage("Goodbye!\r\n");
                endpoint.close();
                return;
            }
            
            if ("stats".equalsIgnoreCase(input)) {
                showStats();
                return;
            }
            
            if ("history".equalsIgnoreCase(input)) {
                showHistory();
                return;
            }

            // Echo the input with count
            sendMessage("Echo #" + requestCount + ": " + input + "\r\n");
        }

        /**
         * Shows request statistics from Redis.
         */
        private void showStats() {
            if (redis == null) {
                sendMessage("Stats unavailable (Redis not connected)\r\n");
                return;
            }

            final String rateLimitKey = "ratelimit:" + clientIP;
            
            redis.get(rateLimitKey, new BulkResultHandler() {
                @Override
                public void handleResult(byte[] value, RedisSession session) {
                    final int count = Integer.parseInt(new String(value, StandardCharsets.UTF_8));
                    
                    session.ttl(rateLimitKey, new IntegerResultHandler() {
                        @Override
                        public void handleResult(long ttl, RedisSession s) {
                            sendMessage("Stats for " + clientIP + ":\r\n" +
                                       "  Requests this minute: " + count + 
                                       "/" + MAX_REQUESTS_PER_MINUTE + "\r\n" +
                                       "  Counter resets in: " + ttl + " seconds\r\n" +
                                       "  Session requests: " + requestCount + "\r\n");
                        }

                        @Override
                        public void handleError(String error, RedisSession s) {
                            sendMessage("Stats: " + count + "/" + MAX_REQUESTS_PER_MINUTE + 
                                       " requests this minute\r\n");
                        }
                    });
                }

                @Override
                public void handleNull(RedisSession session) {
                    sendMessage("Stats: No requests recorded yet\r\n");
                }

                @Override
                public void handleError(String error, RedisSession session) {
                    sendMessage("Stats unavailable: " + error + "\r\n");
                }
            });
        }

        /**
         * Shows session history from Redis.
         */
        private void showHistory() {
            if (redis == null) {
                sendMessage("History unavailable (Redis not connected)\r\n");
                return;
            }

            redis.hgetall("session:" + clientIP, new ArrayResultHandler() {
                @Override
                public void handleResult(List<RESPValue> array, RedisSession session) {
                    if (array.isEmpty()) {
                        sendMessage("No history recorded\r\n");
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("Session history:\r\n");
                    for (int i = 0; i < array.size(); i += 2) {
                        String field = array.get(i).asString();
                        String value = array.get(i + 1).asString();
                        sb.append("  ").append(field).append(": ").append(value).append("\r\n");
                    }
                    sendMessage(sb.toString());
                }

                @Override
                public void handleNull(RedisSession session) {
                    sendMessage("No history recorded\r\n");
                }

                @Override
                public void handleError(String error, RedisSession session) {
                    sendMessage("History unavailable: " + error + "\r\n");
                }
            });
        }

        /**
         * Sends a message to the client.
         */
        private void sendMessage(String message) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            endpoint.send(buffer);
        }

        /**
         * Gets or creates a Redis session for the current SelectorLoop.
         * 
         * <p>This demonstrates SelectorLoop affinity - the Redis connection
         * uses the same SelectorLoop as this server connection, avoiding
         * thread context switches.
         */
        private void getRedisSession(final RedisSessionCallback callback) {
            SelectorLoop loop = endpoint.getSelectorLoop();
            RedisSession existing = redisPool.get(loop);
            if (existing != null) {
                callback.onSession(existing);
                return;
            }

            // Create new Redis connection with SelectorLoop affinity
            try {
                RedisClient client = new RedisClient(loop, redisHost, redisPort);
                
                client.connect(new RedisConnectionReady() {
                    @Override
                    public void handleReady(RedisSession session) {
                        redisPool.put(endpoint.getSelectorLoop(), session);
                        System.out.println("[Redis] Connected for SelectorLoop");
                        callback.onSession(session);
                    }

                    @Override
                    public void onConnected(Endpoint ep) {
                        // TCP connected
                    }

                    @Override
                    public void onDisconnected() {
                        redisPool.remove(endpoint.getSelectorLoop());
                        System.out.println("[Redis] Disconnected for SelectorLoop");
                    }

                    @Override
                    public void onSecurityEstablished(SecurityInfo info) {
                        // TLS established
                    }

                    @Override
                    public void onError(Exception e) {
                        callback.onError(e);
                    }
                });
            } catch (Exception e) {
                callback.onError(e);
            }
        }
    }

    /**
     * Callback for Redis session acquisition.
     */
    interface RedisSessionCallback {
        void onSession(RedisSession session);
        void onError(Exception e);
    }

}
