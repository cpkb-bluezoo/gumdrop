/*
 * WebSocketMetricsSource.java
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

/**
 * Implemented by an {@code HttpRequestHandler} that upgrades a stream to
 * WebSocket and wants that upgrade recorded in {@link WebSocketServerMetrics}.
 *
 * <p>Checked via {@code instanceof} at the point a stream is upgraded
 * (server-side, both HTTP/2 and HTTP/3), so metrics resolution is
 * handler-scoped rather than tied to a particular listener type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler
 */
public interface WebSocketMetricsSource {

    /**
     * Returns the metrics to record this upgrade against, or {@code null}
     * if metrics are not enabled.
     */
    WebSocketServerMetrics getWebSocketMetrics();

}
