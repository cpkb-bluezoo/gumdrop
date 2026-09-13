package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SocksServer}.
 * Uses {@link DefaultSOCKSServer} as the concrete implementation.
 */
public class SOCKSServiceTest {

    private DefaultSOCKSServer createService() {
        return new DefaultSOCKSServer();
    }

    // ── Destination filtering ──

    @Test
    public void testAllowAllByDefault() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void testBlockedDestination() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setBlockedDestinations("10.0.0.0/8");

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.1.2.3")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.1")));
    }

    @Test
    public void testAllowedDestination() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setAllowedDestinations("192.168.0.0/16");

        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.1")));
        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    @Test
    public void testBlockedTakesPrecedence() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setBlockedDestinations("192.168.1.0/24");
        service.setAllowedDestinations("192.168.0.0/16");

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("192.168.1.5")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("192.168.2.5")));
    }

    @Test
    public void testMultipleBlockedRanges() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setBlockedDestinations("10.0.0.0/8,172.16.0.0/12");

        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("10.255.0.1")));
        assertFalse(service.isDestinationAllowed(
                InetAddress.getByName("172.20.0.1")));
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void testEmptyBlockedString() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setBlockedDestinations("");
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    @Test
    public void testNullBlockedString() throws UnknownHostException {
        DefaultSOCKSServer service = createService();
        service.setBlockedDestinations(null);
        assertTrue(service.isDestinationAllowed(
                InetAddress.getByName("10.0.0.1")));
    }

    // ── Relay tracking ──

    @Test
    public void testAcquireRelayUnlimited() {
        DefaultSOCKSServer service = createService();
        assertEquals(0, service.getMaxRelays());

        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertEquals(3, service.getActiveRelayCount());
    }

    @Test
    public void testAcquireRelayWithLimit() {
        DefaultSOCKSServer service = createService();
        service.setMaxRelays(2);
        assertEquals(2, service.getMaxRelays());

        assertTrue(service.acquireRelay());
        assertTrue(service.acquireRelay());
        assertFalse(service.acquireRelay());
        assertEquals(2, service.getActiveRelayCount());
    }

    @Test
    public void testReleaseRelay() {
        DefaultSOCKSServer service = createService();
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
        DefaultSOCKSServer service = createService();
        assertEquals(0, service.getActiveRelayCount());
    }

    // ── Duration parsing (via setRelayIdleTimeout) ──

    @Test
    public void testParseDurationSeconds() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("300s");
        assertEquals(300_000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationMinutes() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("5m");
        assertEquals(300_000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationHours() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("1h");
        assertEquals(3_600_000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationMilliseconds() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("5000ms");
        assertEquals(5000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationNoUnit() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("1000");
        assertEquals(1000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationEmpty() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("");
        assertEquals(0, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationNull() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout(null);
        assertEquals(0, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testParseDurationInvalid() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeout("abc");
        assertEquals(0, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testDefaultIdleTimeout() {
        DefaultSOCKSServer service = createService();
        assertEquals(5 * 60 * 1000, service.getRelayIdleTimeoutMs());
    }

    @Test
    public void testSetRelayIdleTimeoutMs() {
        DefaultSOCKSServer service = createService();
        service.setRelayIdleTimeoutMs(42_000);
        assertEquals(42_000, service.getRelayIdleTimeoutMs());
    }

    // ── Realm ──

    @Test
    public void testRealmDefaultNull() {
        DefaultSOCKSServer service = createService();
        assertNull(service.getRealm());
    }

    // ── Handler creation ──

    @Test
    public void testCreateConnectHandlerReturnsNull() {
        DefaultSOCKSServer service = createService();
        assertNull(service.createConnectHandler(null));
    }

    @Test
    public void testCreateBindHandlerReturnsNull() {
        DefaultSOCKSServer service = createService();
        assertNull(service.createBindHandler(null));
    }
}
