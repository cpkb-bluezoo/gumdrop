/*
 * DNSClientTransport.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * Transport abstraction for DNS client communication.
 *
 * <p>Implementations provide the wire-level transport for sending DNS
 * queries and receiving responses. The resolver uses this interface to
 * decouple DNS protocol logic from the underlying transport mechanism.
 *
 * <p>Each implementation wraps an existing Gumdrop transport factory:
 * <ul>
 * <li>{@link UDPDNSClientTransport} -- plain UDP via
 *     {@link org.bluezoo.gumdrop.UDPTransportFactory}</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver
 * @see DNSClientTransportHandler
 */
public interface DNSClientTransport {

    /**
     * Opens a connection to the specified DNS server.
     *
     * @param server the server address
     * @param port the server port
     * @param loop the SelectorLoop for I/O, or null to use a worker loop
     * @param handler the handler to receive transport events
     * @throws IOException if the connection cannot be established
     */
    void open(InetAddress server, int port, SelectorLoop loop,
              DNSClientTransportHandler handler) throws IOException;

    /**
     * Sends a serialized DNS message to the server.
     *
     * @param data the DNS message bytes
     */
    void send(ByteBuffer data);

    /**
     * Schedules a timer on this transport's event loop.
     *
     * @param delayMs the delay in milliseconds
     * @param callback the callback to invoke when the timer fires
     * @return a handle that can be used to cancel the timer
     */
    TimerHandle scheduleTimer(long delayMs, Runnable callback);

    /**
     * Closes this transport and releases resources.
     */
    void close();

}
