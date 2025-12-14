/*
 * package-info.java
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

/**
 * Asynchronous Redis client for Gumdrop's non-blocking I/O framework.
 *
 * <p>This package provides a fully asynchronous Redis client that integrates
 * with Gumdrop's event-driven architecture. All operations use callbacks
 * rather than blocking, making it suitable for high-concurrency scenarios.
 *
 * <h2>Architecture</h2>
 *
 * <p>The client follows the same patterns as other Gumdrop clients:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.RedisClient} - Connection factory</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.RedisClientConnection} - Protocol handler</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.RedisConnectionReady} - Entry point handler</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.RedisSession} - Operations interface</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * RedisClient client = new RedisClient(selectorLoop, "localhost", 6379);
 *
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         // Set a value
 *         session.set("mykey", "myvalue", new StringResultHandler() {
 *             public void handleResult(String result, RedisSession session) {
 *                 // result is "OK"
 *             }
 *             public void handleError(String error, RedisSession session) {
 *                 // Handle error
 *             }
 *         });
 *     }
 *
 *     public void onConnected(ConnectionInfo info) { }
 *     public void onDisconnected() { }
 *     public void onTLSStarted(TLSInfo info) { }
 *     public void onError(Exception e) { e.printStackTrace(); }
 * });
 * }</pre>
 *
 * <h2>Authentication</h2>
 *
 * <pre>{@code
 * // Redis 6+ ACL authentication
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.auth("username", "password", new StringResultHandler() {
 *             public void handleResult(String result, RedisSession session) {
 *                 // Authenticated, now use Redis
 *                 session.set("key", "value", handler);
 *             }
 *             public void handleError(String error, RedisSession session) {
 *                 // Authentication failed
 *                 session.close();
 *             }
 *         });
 *     }
 * });
 *
 * // Legacy password-only authentication
 * session.auth("password", handler);
 * }</pre>
 *
 * <h2>TLS/SSL Connections</h2>
 *
 * <pre>{@code
 * RedisClient client = new RedisClient(selectorLoop, "redis.example.com", 6379);
 * client.setSecure(true);
 * client.setKeystoreFile("/path/to/truststore.p12");
 * client.connect(handler);
 * }</pre>
 *
 * <h2>Data Types</h2>
 *
 * <h3>Strings</h3>
 * <pre>{@code
 * session.set("key", "value", handler);
 * session.get("key", new BulkResultHandler() {
 *     public void handleResult(byte[] value, RedisSession session) {
 *         String s = new String(value, StandardCharsets.UTF_8);
 *     }
 *     public void handleNull(RedisSession session) {
 *         // Key doesn't exist
 *     }
 * });
 * session.incr("counter", intHandler);
 * session.setex("key", 3600, "value", handler); // Expires in 1 hour
 * }</pre>
 *
 * <h3>Hashes</h3>
 * <pre>{@code
 * session.hset("user:1", "name", "John", handler);
 * session.hget("user:1", "name", bulkHandler);
 * session.hgetall("user:1", new MapResultHandler() {
 *     public void handleResult(Map<String, byte[]> map, RedisSession session) {
 *         // All fields and values
 *     }
 * });
 * }</pre>
 *
 * <h3>Lists</h3>
 * <pre>{@code
 * session.lpush("queue", "item1", "item2", intHandler);
 * session.rpop("queue", bulkHandler);
 * session.lrange("queue", 0, -1, listHandler);
 * }</pre>
 *
 * <h3>Sets</h3>
 * <pre>{@code
 * session.sadd("tags", "java", "redis", intHandler);
 * session.smembers("tags", setHandler);
 * session.sismember("tags", "java", boolHandler);
 * }</pre>
 *
 * <h3>Sorted Sets</h3>
 * <pre>{@code
 * session.zadd("leaderboard", 100.0, "player1", intHandler);
 * session.zrange("leaderboard", 0, 9, listHandler); // Top 10
 * session.zscore("leaderboard", "player1", doubleHandler);
 * }</pre>
 *
 * <h2>Pipelining</h2>
 *
 * <p>The async nature of this client naturally supports pipelining.
 * Multiple commands can be sent without waiting for responses:
 *
 * <pre>{@code
 * session.set("a", "1", handler);
 * session.set("b", "2", handler);
 * session.set("c", "3", handler);
 * session.get("a", getHandler);
 * // All commands sent immediately, responses arrive asynchronously
 * }</pre>
 *
 * <h2>Pub/Sub</h2>
 *
 * <pre>{@code
 * session.subscribe("channel", new MessageHandler() {
 *     public void handleMessage(String channel, byte[] message) {
 *         // Received message on channel
 *     }
 *     public void handleSubscribed(String channel, int count) {
 *         // Successfully subscribed
 *     }
 *     public void handleUnsubscribed(String channel, int count) {
 *         // Unsubscribed from channel
 *     }
 * });
 *
 * // On another connection:
 * session.publish("channel", "Hello!", intHandler);
 * }</pre>
 *
 * <h2>Transactions</h2>
 *
 * <pre>{@code
 * session.multi(new TransactionHandler() {
 *     public void handleQueued(RedisSession session) {
 *         // Commands are now queued
 *         session.incr("counter", null);  // Queued
 *         session.get("counter", null);   // Queued
 *
 *         session.exec(new ArrayResultHandler() {
 *             public void handleResult(List<RESPValue> results, RedisSession session) {
 *                 // results[0] = INCR result
 *                 // results[1] = GET result
 *             }
 *         });
 *     }
 * });
 * }</pre>
 *
 * <h2>Scripting</h2>
 *
 * <pre>{@code
 * String script = "return redis.call('get', KEYS[1])";
 * session.eval(script, 1, new String[]{"mykey"}, new String[]{}, resultHandler);
 * }</pre>
 *
 * <h2>Result Handler Interfaces</h2>
 *
 * <p>Different handlers for different return types:
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.StringResultHandler} - Simple string results (OK, PONG)</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.BulkResultHandler} - Bulk string results (GET, HGET)</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.IntegerResultHandler} - Integer results (INCR, LPUSH, DEL)</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.BooleanResultHandler} - Boolean from integer (EXISTS, SISMEMBER)</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.ArrayResultHandler} - Array results (KEYS, LRANGE)</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client.MessageHandler} - Pub/Sub messages</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The Redis client is designed for single-threaded use within a
 * {@link org.bluezoo.gumdrop.SelectorLoop}. All callbacks are invoked
 * on the selector thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.redis.codec
 * @see <a href="https://redis.io/commands">Redis Commands</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol</a>
 */
package org.bluezoo.gumdrop.redis.client;

