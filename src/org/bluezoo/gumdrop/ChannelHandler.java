/*
 * ChannelHandler.java
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

package org.bluezoo.gumdrop;

import java.nio.channels.SelectionKey;

/**
 * Common interface for objects attached to SelectionKeys in the SelectorLoop.
 * This interface allows the SelectorLoop to dispatch I/O events efficiently
 * using a type discriminator rather than instanceof checks.
 *
 * <p>Implementations include:
 * <ul>
 * <li>{@link TCPEndpoint} - TCP stream endpoints</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ChannelHandler {

    /**
     * Channel handler types for efficient dispatch in the selector loop.
     */
    enum Type {
        /** TCP connection using SocketChannel */
        TCP,
        /** UDP server using unconnected DatagramChannel */
        DATAGRAM_SERVER,
        /** UDP client using connected DatagramChannel */
        DATAGRAM_CLIENT,
        /** QUIC engine managing connections over DatagramChannel */
        QUIC
    }

    /**
     * Returns the type of this channel handler for dispatch purposes.
     *
     * @return the channel handler type
     */
    Type getChannelType();

    /**
     * Returns the SelectionKey associated with this handler.
     *
     * @return the selection key, or null if not yet registered
     */
    SelectionKey getSelectionKey();

    /**
     * Sets the SelectionKey for this handler.
     * Called by SelectorLoop during registration.
     *
     * @param key the selection key
     */
    void setSelectionKey(SelectionKey key);

    /**
     * Returns the SelectorLoop this handler is registered with.
     *
     * @return the selector loop, or null if not yet registered
     */
    SelectorLoop getSelectorLoop();

    /**
     * Sets the SelectorLoop for this handler.
     * Called by SelectorLoop during registration.
     *
     * @param loop the selector loop
     */
    void setSelectorLoop(SelectorLoop loop);

    /**
     * Schedules a callback to be executed after the specified delay.
     * The callback will be executed on this handler's SelectorLoop thread,
     * making it safe to perform I/O operations.
     *
     * <p>Common use cases include:
     * <ul>
     * <li>Periodic keep-alive or ping messages</li>
     * <li>Connection/request timeouts</li>
     * <li>Delayed retry operations</li>
     * </ul>
     *
     * @param delayMs delay in milliseconds before the callback is executed
     * @param callback the callback to execute
     * @return a handle that can be used to cancel the timer
     */
    default TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return Gumdrop.getInstance().scheduleTimer(this, delayMs, callback);
    }

}

