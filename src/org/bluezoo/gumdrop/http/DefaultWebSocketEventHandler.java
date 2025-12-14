/*
 * DefaultWebSocketEventHandler.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * A default implementation of {@link WebSocketEventHandler} with empty methods.
 *
 * <p>Extend this class and override only the methods you need, rather than
 * implementing all methods of the interface.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see WebSocketEventHandler
 */
public abstract class DefaultWebSocketEventHandler implements WebSocketEventHandler {

    @Override
    public void opened(WebSocketSession session) {
    }

    @Override
    public void textMessageReceived(String message) {
    }

    @Override
    public void binaryMessageReceived(ByteBuffer data) {
    }

    @Override
    public void closed(int code, String reason) {
    }

    @Override
    public void error(Throwable cause) {
    }

}

