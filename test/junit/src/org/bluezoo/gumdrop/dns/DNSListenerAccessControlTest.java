/*
 * DNSListenerAccessControlTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.Assert.*;

/**
 * Unit tests for listener ACL enforcement on {@link DNSListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSListenerAccessControlTest {

    @Test
    public void testBlockedNetworkRejectsSource() throws Exception {
        DNSListener listener = new DNSListener();
        listener.setBlockedNetworks("10.0.0.0/8");

        InetSocketAddress blocked =
                new InetSocketAddress("10.1.2.3", 12345);
        assertFalse(listener.acceptConnection(blocked));

        InetSocketAddress allowed =
                new InetSocketAddress("8.8.8.8", 12345);
        assertTrue(listener.acceptConnection(allowed));
    }

    @Test
    public void testAllowedNetworkRestrictsSource() throws Exception {
        DNSListener listener = new DNSListener();
        listener.setAllowedNetworks("192.168.0.0/16");

        InetSocketAddress allowed =
                new InetSocketAddress("192.168.1.50", 53);
        assertTrue(listener.acceptConnection(allowed));

        InetSocketAddress denied =
                new InetSocketAddress("8.8.8.8", 53);
        assertFalse(listener.acceptConnection(denied));
    }

    @Test
    public void testRateLimitRejectsExcessDatagrams() throws Exception {
        DNSListener listener = new DNSListener();
        listener.setRateLimit("1/60s");

        InetAddress ip = InetAddress.getByName("192.168.1.1");
        InetSocketAddress source = new InetSocketAddress(ip, 1234);

        assertTrue(listener.acceptConnection(source));
        listener.connectionOpened(source);
        listener.connectionClosed(source);

        assertFalse(listener.acceptConnection(source));
    }
}
