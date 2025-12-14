/*
 * RedisConnectionReady.java
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

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for receiving the initial Redis connection ready event.
 *
 * <p>This is the entry point for Redis client handlers. When connecting to a
 * Redis server, the handler passed to {@code RedisClient.connect()} must
 * implement this interface to receive notification that the connection is ready.
 *
 * <p>Unlike protocols with a server greeting, Redis clients can send commands
 * immediately upon connection. The handler receives a {@link RedisSession}
 * interface as soon as the TCP connection (and optional TLS handshake) completes.
 *
 * <h4>Example Usage</h4>
 * <pre>{@code
 * public class MyRedisHandler implements RedisConnectionReady {
 *
 *     public void handleReady(RedisSession session) {
 *         // Optionally authenticate
 *         session.auth("password", new StringResultHandler() {
 *             public void handleResult(String result, RedisSession session) {
 *                 // Authenticated, start using Redis
 *                 session.set("key", "value", myHandler);
 *             }
 *             public void handleError(String error, RedisSession session) {
 *                 session.close();
 *             }
 *         });
 *     }
 *
 *     public void onConnected(ConnectionInfo info) { }
 *     public void onDisconnected() { }
 *     public void onTLSStarted(TLSInfo info) { }
 *     public void onError(Exception e) { e.printStackTrace(); }
 * }
 * }</pre>
 *
 * <h4>Without Authentication</h4>
 * <pre>{@code
 * public void handleReady(RedisSession session) {
 *     // No auth needed, start using Redis immediately
 *     session.ping(new StringResultHandler() {
 *         public void handleResult(String result, RedisSession session) {
 *             // result is "PONG"
 *         }
 *     });
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisSession
 * @see ClientHandler
 */
public interface RedisConnectionReady extends ClientHandler {

    /**
     * Called when the Redis connection is ready for commands.
     *
     * <p>This is called immediately after TCP connection (and TLS handshake
     * for secure connections) is complete. The session is ready to accept
     * commands.
     *
     * <p>If authentication is required, call {@code session.auth()} before
     * other operations. If the server requires authentication and you don't
     * authenticate, commands will fail with "NOAUTH" errors.
     *
     * @param session the Redis session for sending commands
     */
    void handleReady(RedisSession session);

}

