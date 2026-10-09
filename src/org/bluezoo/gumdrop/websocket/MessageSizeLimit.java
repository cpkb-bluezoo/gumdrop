/*
 * MessageSizeLimit.java
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

package org.bluezoo.gumdrop.websocket;

import java.nio.ByteBuffer;

/**
 * Applies a maximum message size to each connection handed to a
 * {@link WebSocketEventHandler}, then passes every event through to it.
 *
 * <p>The limit is set when the connection opens, before any frame is read,
 * so a message larger than {@code maxBytes} closes the connection with code
 * 1009 (RFC 6455 section 7.4.1). Without it a connection keeps {@link
 * WebSocketConnection#DEFAULT_MAX_MESSAGE_SIZE}. The server's
 * {@code WebSocketRequestHandler.Builder.maxMessageSize} and the client's
 * {@code WebSocketClient.maxMessageSize} use this, and it can be used
 * directly to wrap a handler given to any other upgrade path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageSizeLimit implements WebSocketEventHandler {

    private final WebSocketEventHandler delegate;
    private final long maxBytes;

    /**
     * Wraps a handler.
     *
     * @param delegate the handler that receives the events
     * @param maxBytes the largest assembled message in bytes, or 0 for
     *        unlimited
     * @throws NullPointerException if the handler is null
     * @throws IllegalArgumentException if the size is negative
     */
    public MessageSizeLimit(WebSocketEventHandler delegate, long maxBytes) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public void opened(WebSocketSession session) {
        session.setMaxMessageSize(maxBytes);
        delegate.opened(session);
    }

    @Override
    public void textMessageReceived(WebSocketSession session, String message) {
        delegate.textMessageReceived(session, message);
    }

    @Override
    public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
        delegate.binaryMessageReceived(session, data);
    }

    @Override
    public void closed(int code, String reason) {
        delegate.closed(code, reason);
    }

    @Override
    public void error(Throwable cause) {
        delegate.error(cause);
    }
}
