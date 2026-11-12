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
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.codec.RespValue;

/**
 * Negotiates RESP3 with {@code HELLO 3} as soon as a connection is up, and
 * only then tells the application's handler that it is ready, so no command
 * the application sends can run before the protocol is settled. A rejected
 * HELLO is reported to the handler as an error and the connection is closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HelloOnReady implements RedisConnectionReady {

    private final RedisConnectionReady application;
    private final int protocol;

    /**
     * @param application the handler to tell once the protocol is settled
     * @param protocol the RESP version to ask for
     */
    HelloOnReady(RedisConnectionReady application, int protocol) {
        this.application = application;
        this.protocol = protocol;
    }

    @Override
    public void handleReady(final RedisSession session) {
        session.hello(protocol, new ArrayResultHandler() {
            @Override
            public void handleResult(List<RespValue> values, RedisSession s) {
                application.handleReady(s);
            }

            @Override
            public void handleNull(RedisSession s) {
                application.handleReady(s);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                application.onError(new IOException(MessageFormat.format(
                        RedisClientProtocolHandler.L10N.getString("err.hello_failed"), error)));
                s.close();
            }
        });
    }

    @Override
    public void onConnected(Endpoint endpoint) {
        application.onConnected(endpoint);
    }

    @Override
    public void onSecurityEstablished(SecurityInfo info) {
        application.onSecurityEstablished(info);
    }

    @Override
    public void onError(Exception cause) {
        application.onError(cause);
    }

    @Override
    public void onDisconnected() {
        application.onDisconnected();
    }
}
