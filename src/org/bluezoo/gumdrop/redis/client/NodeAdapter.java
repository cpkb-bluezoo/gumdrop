/*
 * NodeAdapter.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.text.MessageFormat;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * Turns an ordinary single-node connection into a cluster node connection:
 * authenticates it if required, then reports it to a {@link NodeListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class NodeAdapter implements RedisConnectionReady {

    private final NodeListener listener;
    private final String username;
    private final String password;
    private RedisClientProtocolHandler connection;
    private boolean ready;
    private boolean finished;

    /**
     * @param listener told how the connection gets on
     * @param username the user to authenticate as, or null for the default user
     * @param password the password, or null if the node needs none
     */
    NodeAdapter(NodeListener listener, String username, String password) {
        this.listener = listener;
        this.username = username;
        this.password = password;
    }

    @Override
    public void handleReady(RedisSession session) {
        connection = (RedisClientProtocolHandler) session;
        if (password == null) {
            becomeReady();
            return;
        }
        StringResultHandler result = new StringResultHandler() {
            @Override
            public void handleResult(String reply, RedisSession s) {
                becomeReady();
            }

            @Override
            public void handleError(String error, RedisSession s) {
                fail(new IOException(MessageFormat.format(
                        RedisClientProtocolHandler.L10N.getString("err.cluster_auth_failed"), error)));
                connection.close();
            }
        };
        if (username != null) {
            connection.auth(username, password, result);
        } else {
            connection.auth(password, result);
        }
    }

    private void becomeReady() {
        if (finished) {
            return;
        }
        ready = true;
        listener.ready(connection);
    }

    private void fail(Exception cause) {
        if (finished) {
            return;
        }
        finished = true;
        listener.failed(cause);
    }

    @Override
    public void onConnected(Endpoint endpoint) {
    }

    @Override
    public void onSecurityEstablished(SecurityInfo info) {
    }

    @Override
    public void onError(Exception cause) {
        // once ready, the close that follows an error is reported instead
        if (!ready) {
            fail(cause);
        }
    }

    @Override
    public void onDisconnected() {
        if (ready) {
            listener.closed();
        } else {
            fail(new IOException(RedisClientProtocolHandler.L10N.getString("err.not_connected")));
        }
    }
}
