/*
 * RedisClientExample.java
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
import org.bluezoo.gumdrop.redis.client.ArrayResultHandler;
import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.IntegerResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.StringResultHandler;
import org.bluezoo.gumdrop.redis.codec.RESPValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Example demonstrating basic Redis client usage with Gumdrop.
 *
 * <p>This example shows the simplified standalone API where no Gumdrop
 * setup is needed - infrastructure is managed automatically.
 *
 * <p>Features demonstrated:
 * <ul>
 *   <li>Connecting to a Redis server</li>
 *   <li>Executing string commands (SET, GET, INCR)</li>
 *   <li>Using hash commands (HSET, HGET, HGETALL)</li>
 *   <li>List operations (LPUSH, LRANGE)</li>
 *   <li>Key expiration (SETEX, TTL)</li>
 *   <li>Error handling</li>
 * </ul>
 *
 * <p>Before running, ensure a Redis server is available on localhost:6379.
 * You can start one with Docker:
 * <pre>
 *   docker run -d -p 6379:6379 redis
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientExample {

    public static void main(String[] args) throws Exception {
        System.out.println("Gumdrop Redis Client Example");
        System.out.println("============================");
        System.out.println();

        // Parse command line arguments
        String host = "localhost";
        int port = 6379;
        
        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        System.out.println("Connecting to Redis at " + host + ":" + port);
        System.out.println();

        // Simple! No Gumdrop setup needed - just create client and connect
        RedisClient client = new RedisClient(host, port);
        client.connect(new ExampleHandler());

        // Infrastructure starts automatically on connect and stops when done
    }

    /**
     * Handler demonstrating various Redis operations.
     */
    static class ExampleHandler implements RedisConnectionReady {

        @Override
        public void handleReady(final RedisSession session) {
            System.out.println("[Connected] Redis connection ready, starting demo...");
            System.out.println();

            // Start with a PING to verify connection
            session.ping(new StringResultHandler() {
                @Override
                public void handleResult(String result, RedisSession s) {
                    System.out.println("[PING] Redis says: " + result);
                    demonstrateStringCommands(s);
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[PING] Error: " + error);
                    s.close();
                }
            });
        }

        /**
         * Demonstrates basic string commands.
         */
        private void demonstrateStringCommands(final RedisSession session) {
            System.out.println();
            System.out.println("=== String Commands ===");

            // SET a key
            session.set("greeting", "Hello, Gumdrop!", new StringResultHandler() {
                @Override
                public void handleResult(String result, RedisSession s) {
                    System.out.println("[SET greeting] " + result);

                    // GET the key back
                    s.get("greeting", new BulkResultHandler() {
                        @Override
                        public void handleResult(byte[] value, RedisSession s2) {
                            String str = new String(value, StandardCharsets.UTF_8);
                            System.out.println("[GET greeting] " + str);
                            demonstrateCounter(s2);
                        }

                        @Override
                        public void handleNull(RedisSession s2) {
                            System.out.println("[GET greeting] Key not found");
                            demonstrateCounter(s2);
                        }

                        @Override
                        public void handleError(String error, RedisSession s2) {
                            System.err.println("[GET greeting] Error: " + error);
                        }
                    });
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[SET greeting] Error: " + error);
                }
            });
        }

        /**
         * Demonstrates counter operations.
         */
        private void demonstrateCounter(final RedisSession session) {
            System.out.println();
            System.out.println("=== Counter Commands ===");

            // Set initial counter value
            session.set("counter", "10", new StringResultHandler() {
                @Override
                public void handleResult(String result, RedisSession s) {
                    System.out.println("[SET counter=10] " + result);

                    // Increment the counter
                    s.incr("counter", new IntegerResultHandler() {
                        @Override
                        public void handleResult(long value, RedisSession s2) {
                            System.out.println("[INCR counter] New value: " + value);

                            // Increment by 5
                            s2.incrby("counter", 5, new IntegerResultHandler() {
                                @Override
                                public void handleResult(long value2, RedisSession s3) {
                                    System.out.println("[INCRBY counter 5] New value: " + value2);
                                    demonstrateExpiration(s3);
                                }

                                @Override
                                public void handleError(String error, RedisSession s3) {
                                    System.err.println("[INCRBY] Error: " + error);
                                }
                            });
                        }

                        @Override
                        public void handleError(String error, RedisSession s2) {
                            System.err.println("[INCR] Error: " + error);
                        }
                    });
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[SET counter] Error: " + error);
                }
            });
        }

        /**
         * Demonstrates key expiration.
         */
        private void demonstrateExpiration(final RedisSession session) {
            System.out.println();
            System.out.println("=== Key Expiration ===");

            // Set key with 60 second expiration
            session.setex("temp-key", 60, "This key expires in 60 seconds",
                new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        System.out.println("[SETEX temp-key 60] " + result);

                        // Check TTL
                        s.ttl("temp-key", new IntegerResultHandler() {
                            @Override
                            public void handleResult(long value, RedisSession s2) {
                                System.out.println("[TTL temp-key] " + value + " seconds remaining");
                                demonstrateHash(s2);
                            }

                            @Override
                            public void handleError(String error, RedisSession s2) {
                                System.err.println("[TTL] Error: " + error);
                            }
                        });
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        System.err.println("[SETEX] Error: " + error);
                    }
                });
        }

        /**
         * Demonstrates hash operations.
         */
        private void demonstrateHash(final RedisSession session) {
            System.out.println();
            System.out.println("=== Hash Commands ===");

            // Set hash fields
            session.hset("user:1", "name", "Alice", new IntegerResultHandler() {
                @Override
                public void handleResult(long value, RedisSession s) {
                    System.out.println("[HSET user:1 name Alice] Fields added: " + value);

                    s.hset("user:1", "email", "alice@example.com", new IntegerResultHandler() {
                        @Override
                        public void handleResult(long value2, RedisSession s2) {
                            System.out.println("[HSET user:1 email alice@example.com] Fields added: " + value2);

                            s2.hset("user:1", "age", "30", new IntegerResultHandler() {
                                @Override
                                public void handleResult(long value3, RedisSession s3) {
                                    System.out.println("[HSET user:1 age 30] Fields added: " + value3);

                                    // Get a single field
                                    s3.hget("user:1", "name", new BulkResultHandler() {
                                        @Override
                                        public void handleResult(byte[] val, RedisSession s4) {
                                            String name = new String(val, StandardCharsets.UTF_8);
                                            System.out.println("[HGET user:1 name] " + name);

                                            // Get all fields
                                            s4.hgetall("user:1", new ArrayResultHandler() {
                                                @Override
                                                public void handleResult(List<RESPValue> array, RedisSession s5) {
                                                    System.out.println("[HGETALL user:1]");
                                                    for (int i = 0; i < array.size(); i += 2) {
                                                        String field = array.get(i).asString();
                                                        String fieldValue = array.get(i + 1).asString();
                                                        System.out.println("  " + field + " = " + fieldValue);
                                                    }
                                                    demonstrateList(s5);
                                                }

                                                @Override
                                                public void handleNull(RedisSession s5) {
                                                    System.out.println("[HGETALL user:1] Key not found");
                                                }

                                                @Override
                                                public void handleError(String error, RedisSession s5) {
                                                    System.err.println("[HGETALL] Error: " + error);
                                                }
                                            });
                                        }

                                        @Override
                                        public void handleNull(RedisSession s4) {
                                            System.out.println("[HGET user:1 name] Field not found");
                                        }

                                        @Override
                                        public void handleError(String error, RedisSession s4) {
                                            System.err.println("[HGET] Error: " + error);
                                        }
                                    });
                                }

                                @Override
                                public void handleError(String error, RedisSession s3) {
                                    System.err.println("[HSET age] Error: " + error);
                                }
                            });
                        }

                        @Override
                        public void handleError(String error, RedisSession s2) {
                            System.err.println("[HSET email] Error: " + error);
                        }
                    });
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[HSET name] Error: " + error);
                }
            });
        }

        /**
         * Demonstrates list operations.
         */
        private void demonstrateList(final RedisSession session) {
            System.out.println();
            System.out.println("=== List Commands ===");

            // Delete any existing list
            session.del(new IntegerResultHandler() {
                @Override
                public void handleResult(long value, RedisSession s) {
                    // Push items to list
                    s.rpush("queue", new IntegerResultHandler() {
                        @Override
                        public void handleResult(long length, RedisSession s2) {
                            System.out.println("[RPUSH queue item1 item2 item3] List length: " + length);

                            // Get list range
                            s2.lrange("queue", 0, -1, new ArrayResultHandler() {
                                @Override
                                public void handleResult(List<RESPValue> array, RedisSession s3) {
                                    System.out.println("[LRANGE queue 0 -1]");
                                    for (int i = 0; i < array.size(); i++) {
                                        System.out.println("  [" + i + "] " + array.get(i).asString());
                                    }

                                    // Pop from list
                                    s3.lpop("queue", new BulkResultHandler() {
                                        @Override
                                        public void handleResult(byte[] val, RedisSession s4) {
                                            System.out.println("[LPOP queue] " + new String(val, StandardCharsets.UTF_8));
                                            demonstrateKeys(s4);
                                        }

                                        @Override
                                        public void handleNull(RedisSession s4) {
                                            System.out.println("[LPOP queue] List is empty");
                                        }

                                        @Override
                                        public void handleError(String error, RedisSession s4) {
                                            System.err.println("[LPOP] Error: " + error);
                                        }
                                    });
                                }

                                @Override
                                public void handleNull(RedisSession s3) {
                                    System.out.println("[LRANGE queue 0 -1] List not found");
                                }

                                @Override
                                public void handleError(String error, RedisSession s3) {
                                    System.err.println("[LRANGE] Error: " + error);
                                }
                            });
                        }

                        @Override
                        public void handleError(String error, RedisSession s2) {
                            System.err.println("[RPUSH] Error: " + error);
                        }
                    }, "item1", "item2", "item3");
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[DEL] Error: " + error);
                }
            }, "queue");
        }

        /**
         * Demonstrates key pattern matching.
         */
        private void demonstrateKeys(final RedisSession session) {
            System.out.println();
            System.out.println("=== Key Pattern ===");

            session.keys("*", new ArrayResultHandler() {
                @Override
                public void handleResult(List<RESPValue> array, RedisSession s) {
                    System.out.println("[KEYS *] Found " + array.size() + " keys:");
                    for (RESPValue key : array) {
                        System.out.println("  - " + key.asString());
                    }
                    cleanup(s);
                }

                @Override
                public void handleNull(RedisSession s) {
                    System.out.println("[KEYS *] No keys found");
                    cleanup(s);
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[KEYS] Error: " + error);
                    cleanup(s);
                }
            });
        }

        /**
         * Cleans up example keys and closes connection.
         */
        private void cleanup(final RedisSession session) {
            System.out.println();
            System.out.println("=== Cleanup ===");

            // Delete the example keys we created
            session.del(new IntegerResultHandler() {
                @Override
                public void handleResult(long value, RedisSession s) {
                    System.out.println("[DEL] Removed " + value + " keys");
                    System.out.println();
                    System.out.println("Demo complete! Closing connection...");
                    s.quit();
                    // Infrastructure will auto-shutdown when connection closes
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[DEL] Error: " + error);
                    s.close();
                }
            }, "greeting", "counter", "temp-key", "user:1", "queue");
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            System.out.println("[Connected] " + endpoint.getRemoteAddress());
        }

        @Override
        public void onDisconnected() {
            System.out.println("[Disconnected]");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            System.out.println("[TLS] " + info.getProtocol() + " " + info.getCipherSuite());
        }

        @Override
        public void onError(Exception e) {
            System.err.println("[Error] " + e.getMessage());
            e.printStackTrace();
        }
    }

}
