/*
 * EchoWebSocketService.java
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

package org.bluezoo.gumdrop.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bluezoo.gumdrop.http.Headers;

/**
 * WebSocket service for integration tests: echoes text messages back
 * prefixed with {@code "echo:"}, and binary messages back unchanged.
 * Also exposes every connected session's handler so tests can assert on
 * lifecycle events (opened/closed/error) observed server-side.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EchoWebSocketService extends WebSocketService {

    /** Handlers for every connection accepted so far, in connection order. */
    public final CopyOnWriteArrayList<EchoHandler> handlers = new CopyOnWriteArrayList<>();

    @Override
    protected WebSocketEventHandler createConnectionHandler(String requestPath, Headers upgradeHeaders) {
        EchoHandler handler = new EchoHandler(requestPath);
        handlers.add(handler);
        return handler;
    }

    /** Per-connection handler recording lifecycle events for test assertions. */
    public static class EchoHandler implements WebSocketEventHandler {

        public final String path;
        public volatile WebSocketSession session;
        public volatile boolean opened;
        public volatile int closeCode = -1;
        public volatile String closeReason;
        public volatile Throwable error;
        public final java.util.List<String> textMessages = new CopyOnWriteArrayList<>();
        public final java.util.List<byte[]> binaryMessages = new CopyOnWriteArrayList<>();

        EchoHandler(String path) {
            this.path = path;
        }

        @Override
        public void opened(WebSocketSession session) {
            this.session = session;
            this.opened = true;
        }

        @Override
        public void textMessageReceived(WebSocketSession session, String message) {
            textMessages.add(message);
            try {
                session.sendText("echo:" + message);
            } catch (IOException e) {
                error = e;
            }
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryMessages.add(bytes);
            try {
                session.sendBinary(ByteBuffer.wrap(bytes));
            } catch (IOException e) {
                error = e;
            }
        }

        @Override
        public void closed(int code, String reason) {
            this.closeCode = code;
            this.closeReason = reason;
        }

        @Override
        public void error(Throwable cause) {
            this.error = cause;
        }
    }
}
