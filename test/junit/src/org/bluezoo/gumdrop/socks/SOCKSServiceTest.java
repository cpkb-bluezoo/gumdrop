/*
 * SOCKSServiceTest.java
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

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import org.bluezoo.gumdrop.socks.server.SocksServer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SocksServer}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SOCKSServiceTest {

    private SocksServer createService() {
        return new SocksServer();
    }

    // ── Destination filtering ──

    @Test
    public void testAllowAllByDefault() throws UnknownHostException {
        SocksServer service = createService();
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void testBlockedDestination() throws UnknownHostException {
        SocksServer service = createService();
        service.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.1.2.3")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.1")));
    }

    @Test
    public void testAllowedDestination() throws UnknownHostException {
        SocksServer service = createService();
        service.setAllowedDestinations(CidrNetwork.parseList("192.168.0.0/16"));

        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.1")));
        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    @Test
    public void testBlockedTakesPrecedence() throws UnknownHostException {
        SocksServer service = createService();
        service.setBlockedDestinations(CidrNetwork.parseList("192.168.1.0/24"));
        service.setAllowedDestinations(CidrNetwork.parseList("192.168.0.0/16"));

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.5")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.2.5")));
    }

    @Test
    public void testMultipleBlockedRanges() throws UnknownHostException {
        SocksServer service = createService();
        service.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8,172.16.0.0/12"));

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.255.0.1")));
        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("172.20.0.1")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void testEmptyBlockedString() throws UnknownHostException {
        SocksServer service = createService();
        service.setBlockedDestinations(CidrNetwork.parseList(""));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    @Test
    public void testNullBlockedString() throws UnknownHostException {
        SocksServer service = createService();
        service.setBlockedDestinations(null);
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    // ── Relay tracking ──

    @Test
    public void testAcquireRelayUnlimited() {
        SocksServer service = createService();
        assertEquals(0, service.getMaxRelays());

        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertEquals(3, service.getActiveRelayCount());
    }

    @Test
    public void testAcquireRelayWithLimit() {
        SocksServer service = createService();
        service.setMaxRelays(2);
        assertEquals(2, service.getMaxRelays());

        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertFalse(service.acquireRelay());
        assertEquals(2, service.getActiveRelayCount());
    }

    @Test
    public void testReleaseRelay() {
        SocksServer service = createService();
        service.setMaxRelays(2);

        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertFalse(service.acquireRelay());

        service.releaseRelay();
        assertEquals(1, service.getActiveRelayCount());
        assertTrue(service.acquireRelay());
    }

    @Test
    public void testRelayCountStartsAtZero() {
        SocksServer service = createService();
        assertEquals(0, service.getActiveRelayCount());
    }

    // ── Relay idle timeout ──

    @Test
    public void testDefaultIdleTimeout() {
        SocksServer service = createService();
        assertEquals(5 * 60 * 1000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testSetRelayIdleTimeoutMs() {
        SocksServer service = createService();
        service.setRelayIdleTimeoutMs(42_000);
        assertEquals(42_000, service.getRelayIdleTimeoutMs());
    }

    // ── Realm ──

    @Test
    public void testRealmDefaultNull() {
        SocksServer service = createService();
        assertNull(service.getRealm());
    }

    // ── Session pipeline ──

    @Test
    public void testOpenSessionReturnsNullByDefault() {
        SocksServer service = createService();
        assertNull(service.openSession(null));
    }
}
