/*
 * ConnectionReady.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.amqp.client.FieldTable;

/**
 * Entry point for an AMQP client connection: extends the shared
 * {@link ClientHandler} connection lifecycle (connect/disconnect/error/
 * security) with the server's {@code connection.start} announcement.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ConnectionReady extends ClientHandler {

    /**
     * The server announced itself and is waiting for {@code start-ok}.
     *
     * @param serverProperties broker identification (product, version, etc.)
     * @param mechanisms space-separated list of SASL mechanisms the server supports
     * @param locales space-separated list of locales the server supports
     * @param handshake used to reply with {@code start-ok}
     */
    void handleStart(FieldTable serverProperties, String mechanisms, String locales,
            ClientHandshake handshake);

    /**
     * The server closed the connection with a reason (e.g. authentication
     * failure, vhost not found) rather than accepting {@code start-ok}/
     * {@code open}, or closed an already-open connection unsolicited.
     */
    void onConnectionClosed(int replyCode, String replyText);
}
