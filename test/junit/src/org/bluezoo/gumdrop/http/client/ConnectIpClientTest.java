/*
 * ConnectIpClientTest.java
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

import org.bluezoo.gumdrop.http.ConnectIpTarget;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Facade-level tests for {@link ConnectIpClient} that do not require a live proxy.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpClientTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    @Test
    public void notOpenBeforeConnect() {
        ConnectIpClient client = new ConnectIpClient("proxy.example.com", 443);
        assertFalse(client.isOpen());
    }

    @Test
    public void altSvcReceivedPopulatesCache() {
        ConnectIpClient client = new ConnectIpClient("proxy.example.com", 443);
        client.altSvcReceived("h3=\"alt.example.com:8443\"; ma=120");

        AltSvcCache.Entry entry = AltSvcCache.get("proxy.example.com", 443);
        assertNotNull(entry);
        assertEquals(8443, entry.getH3Port());
        assertEquals("alt.example.com", entry.getH3Host());
    }

    @Test
    public void altSvcIgnoredForUnixSocketClient() {
        ConnectIpClient client = new ConnectIpClient("/tmp/connect-ip.sock");
        client.altSvcReceived("h3=\":443\"; ma=3600");
        assertNull(AltSvcCache.get("proxy.example.com", 443));
    }

    @Test(expected = NullPointerException.class)
    public void unixSocketConstructorRejectsNullPath() {
        new ConnectIpClient((String) null);
    }

    @Test
    public void inetAddressConstructorSkipsDnsDiscovery() throws Exception {
        ConnectIpClient client = new ConnectIpClient(
                null, InetAddress.getByName("203.0.113.1"), 8443);
        assertFalse(client.isOpen());
    }

    @Test
    public void h3EnabledOnUnixSocketReportsErrorWithoutConnecting() {
        ConnectIpClient client = new ConnectIpClient("/tmp/connect-ip.sock");
        client.setH3Enabled(true);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        client.connect(null, ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD,
                new ConnectIpEventHandler() {
                    @Override
                    public void opened(ConnectIpClientSession session) {
                    }

                    @Override
                    public void packetReceived(java.nio.ByteBuffer packet) {
                    }

                    @Override
                    public void addressAssigned(
                            java.util.List<org.bluezoo.gumdrop.http.ConnectIpAddress> assignments) {
                    }

                    @Override
                    public void routeAdvertised(
                            java.util.List<org.bluezoo.gumdrop.http.ConnectIpRoute> routes) {
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
