/*
 * SocksUdpRelayTest.java
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

package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;

import org.junit.Test;

import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * Tests for the lifecycle handling of {@link SocksUdpRelay} that does not
 * require live UDP sockets.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksUdpRelayTest {

    @Test
    public void closeReleasesRelaySlotOnce() {
        SocksServer server = new SocksServer();
        server.acquireRelay();
        SocksServerMetrics metrics =
                new SocksServerMetrics(new TelemetryConfig());
        SocksUdpRelay relay = new SocksUdpRelay(new StubEndpoint(), server,
                metrics, 1000L, InetAddress.getLoopbackAddress());
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void closeWithoutMetrics() {
        SocksServer server = new SocksServer();
        server.acquireRelay();
        SocksUdpRelay relay = new SocksUdpRelay(new StubEndpoint(), server,
                null, 0L, InetAddress.getLoopbackAddress());
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }
}
