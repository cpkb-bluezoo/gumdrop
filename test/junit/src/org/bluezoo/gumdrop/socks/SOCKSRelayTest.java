package org.bluezoo.gumdrop.socks;

import java.nio.ByteBuffer;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SOCKSRelay}.
 */
public class SOCKSRelayTest {

    private DefaultSOCKSService service;
    private StubEndpoint clientEndpoint;
    private StubEndpoint upstreamEndpoint;
    private SOCKSRelay relay;

    @Before
    public void setUp() {
        service = new DefaultSOCKSService();
        clientEndpoint = new StubEndpoint();
        relay = new SOCKSRelay(clientEndpoint, service, null, 0);
        upstreamEndpoint = new StubEndpoint();
        service.acquireRelay();
    }

    // ── Data relay ──

    @Test
    public void testClientDataForwardedToUpstream() {
        relay.upstreamConnected(upstreamEndpoint);

        byte[] data = {0x01, 0x02, 0x03};
        relay.clientData(ByteBuffer.wrap(data));

        assertEquals(1, upstreamEndpoint.getSentCount());
        assertArrayEquals(data, upstreamEndpoint.getLastSent());
    }

    @Test
    public void testUpstreamDataForwardedToClient() {
        relay.upstreamConnected(upstreamEndpoint);

        byte[] data = {0x0A, 0x0B};
        relay.upstreamData(ByteBuffer.wrap(data));

        assertEquals(1, clientEndpoint.getSentCount());
        assertArrayEquals(data, clientEndpoint.getLastSent());
    }

    @Test
    public void testBidirectionalRelay() {
        relay.upstreamConnected(upstreamEndpoint);

        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        relay.upstreamData(ByteBuffer.wrap(new byte[]{2}));
        relay.clientData(ByteBuffer.wrap(new byte[]{3}));

        assertEquals(2, upstreamEndpoint.getSentCount());
        assertEquals(1, clientEndpoint.getSentCount());
    }

    // ── Data ignored before upstream connected ──

    @Test
    public void testClientDataIgnoredBeforeUpstreamConnected() {
        relay.clientData(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertEquals(0, upstreamEndpoint.getSentCount());
    }

    // ── Disconnect handling ──

    @Test
    public void testClientDisconnectClosesUpstream() {
        relay.upstreamConnected(upstreamEndpoint);

        relay.clientDisconnected();

        assertFalse(upstreamEndpoint.isOpen());
    }

    @Test
    public void testUpstreamDisconnectClosesClient() {
        relay.upstreamConnected(upstreamEndpoint);

        relay.upstreamDisconnected();

        assertFalse(clientEndpoint.isOpen());
    }

    @Test
    public void testDataIgnoredAfterClose() {
        relay.upstreamConnected(upstreamEndpoint);
        relay.clientDisconnected();

        upstreamEndpoint.clearSent();
        clientEndpoint.clearSent();

        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        relay.upstreamData(ByteBuffer.wrap(new byte[]{2}));

        assertEquals(0, upstreamEndpoint.getSentCount());
        assertEquals(0, clientEndpoint.getSentCount());
    }

    @Test
    public void testRelaySlotReleasedOnClose() {
        relay.upstreamConnected(upstreamEndpoint);
        assertEquals(1, service.getActiveRelayCount());

        relay.clientDisconnected();

        assertEquals(0, service.getActiveRelayCount());
    }

    @Test
    public void testDoubleDisconnectDoesNotDecrement() {
        relay.upstreamConnected(upstreamEndpoint);
        relay.clientDisconnected();
        int afterFirst = service.getActiveRelayCount();

        relay.upstreamDisconnected();
        assertEquals(afterFirst, service.getActiveRelayCount());
    }

    // ── Empty data ──

    @Test
    public void testEmptyClientData() {
        relay.upstreamConnected(upstreamEndpoint);

        relay.clientData(ByteBuffer.allocate(0));
        assertEquals(1, upstreamEndpoint.getSentCount());
        assertEquals(0, upstreamEndpoint.getLastSent().length);
    }

    // ── Upstream not open ──

    @Test
    public void testClientDataIgnoredWhenUpstreamClosed() {
        relay.upstreamConnected(upstreamEndpoint);
        upstreamEndpoint.setOpen(false);

        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(0, upstreamEndpoint.getSentCount());
    }

    @Test
    public void testUpstreamDataIgnoredWhenClientClosed() {
        relay.upstreamConnected(upstreamEndpoint);
        clientEndpoint.setOpen(false);

        relay.upstreamData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(0, clientEndpoint.getSentCount());
    }
}
