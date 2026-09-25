/*
 * Amqp1ConnectionOpener.java
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

package org.bluezoo.gumdrop.amqp1.client;

import org.bluezoo.gumdrop.amqp1.codec.Open;

/**
 * State after authentication: send the {@code open} performative.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Amqp1ConnectionOpener {

    /** Default advertised {@code max-frame-size}, in octets. */
    int DEFAULT_MAX_FRAME_SIZE = 65536;

    /**
     * Opens the connection with a container id and the default
     * {@code max-frame-size}. No idle timeout is requested.
     *
     * @param containerId the identifier of this client container
     * @param hostname the host being connected to (for virtual hosting),
     *      or {@code null}
     * @param handler receives the server's {@code open}
     */
    void open(String containerId, String hostname, Amqp1OpenHandler handler);

    /**
     * Opens the connection with full control of the {@code open}
     * parameters: max-frame-size, channel-max, idle timeout,
     * capabilities and properties.
     *
     * <p>{@code max-frame-size} bounds every frame the peer may send to
     * us and must be at least 512; if left at its unlimited default it
     * is set to {@link #DEFAULT_MAX_FRAME_SIZE}.
     *
     * @param open the parameters to send
     * @param handler receives the server's {@code open}
     * @throws IllegalArgumentException if max-frame-size is below 512
     */
    void open(Open open, Amqp1OpenHandler handler);
}
