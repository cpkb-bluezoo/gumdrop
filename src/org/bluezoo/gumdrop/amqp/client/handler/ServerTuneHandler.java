/*
 * ServerTuneHandler.java
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

/**
 * Receives {@code connection.tune}. The client replies with
 * {@code tune-ok} automatically (accepting or capping the broker's
 * proposed limits) before this callback fires, since {@code tune-ok}
 * must be sent before {@code open} regardless of what the application
 * wants to do next.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ServerTuneHandler {

    /**
     * @param channelMax negotiated max concurrent channels (0 = no limit)
     * @param frameMax negotiated max frame size in bytes
     * @param heartbeat negotiated heartbeat interval in seconds (0 = disabled)
     * @param state used to send {@code connection.open}
     */
    void handleTune(int channelMax, long frameMax, int heartbeat, ClientTuned state);
}
