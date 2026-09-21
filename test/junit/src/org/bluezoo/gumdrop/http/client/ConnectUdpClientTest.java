/*
 * ConnectUdpClientTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Facade-level tests for {@link ConnectUdpClient} that do not require a live proxy.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpClientTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    @Test
    public void notOpenBeforeConnect() {
        ConnectUdpClient client = new ConnectUdpClient("proxy.example.com", 443);
        assertFalse(client.isOpen());
    }

    @Test
    public void altSvcReceivedPopulatesCache() {
        ConnectUdpClient client = new ConnectUdpClient("proxy.example.com", 443);
        client.altSvcReceived("h3=\":443\"; ma=86400");

        AltSvcCache.Entry entry = AltSvcCache.get("proxy.example.com", 443);
        assertNotNull(entry);
        assertEquals(443, entry.getH3Port());
    }

    @Test
    public void altSvcIgnoredForUnixSocketClient() {
        ConnectUdpClient client = new ConnectUdpClient("/tmp/connect-udp.sock");
        client.altSvcReceived("h3=\":443\"; ma=3600");
        assertNull(AltSvcCache.get("proxy.example.com", 443));
    }

    @Test(expected = NullPointerException.class)
    public void unixSocketConstructorRejectsNullPath() {
        new ConnectUdpClient((String) null);
    }

    @Test
    public void inetAddressConstructorSkipsDnsDiscovery() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(
                null, InetAddress.getByName("203.0.113.2"), 8443);
        assertFalse(client.isOpen());
    }

    @Test
    public void h3EnabledOnUnixSocketReportsErrorWithoutConnecting() {
        ConnectUdpClient client = new ConnectUdpClient("/tmp/connect-udp.sock");
        client.setH3Enabled(true);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        client.connect(null, "example.test", 53,
                new ConnectUdpEventHandler() {
                    @Override
                    public void opened(ConnectUdpSession session) {
                    }

                    @Override
                    public void datagramReceived(java.nio.ByteBuffer payload) {
                    }

                    @Override
                    public void closed() {
                    }

                    @Override
                    public void error(Throwable cause) {
                        failure.set(cause);
                    }
                });
        assertNotNull(failure.get());
        assertTrue(failure.get().getMessage().contains("HTTP/3"));
    }
}
