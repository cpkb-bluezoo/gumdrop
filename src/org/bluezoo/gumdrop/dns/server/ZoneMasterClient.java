/*
 * ZoneMasterClient.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The network operations a zone server performs against other DNS servers:
 * asking a master for its SOA serial, transferring a zone, sending NOTIFY,
 * and resolving a notify target's name. A seam so the refresh logic can be
 * tested without a network.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface ZoneMasterClient {

    /** Receives the master's SOA serial, or a failure. */
    interface SerialCallback {
        void onSerial(int serial);

        void onFailure(Exception error);
    }

    void querySoaSerial(SelectorLoop loop, InetSocketAddress master, String origin, SerialCallback callback);

    void transfer(SelectorLoop loop, InetSocketAddress master, String origin,
            DnsZoneClient.TransferCallback callback);

    void notify(SelectorLoop loop, InetSocketAddress peer, String origin);

    /** Blocking; called off the selector loops. */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
