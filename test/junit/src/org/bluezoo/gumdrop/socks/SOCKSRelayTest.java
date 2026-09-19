/*
 * SOCKSRelayTest.java
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

import java.nio.ByteBuffer;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.socks.server.SocksServer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SocksRelay}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SOCKSRelayTest {

    private SocksServer service;
    private StubEndpoint clientEndpoint;
    private StubEndpoint upstreamEndpoint;
    private SocksRelay relay;

    @Before
    public void setUp() {
        service = new SocksServer();
        clientEndpoint = new StubEndpoint();
        relay = new SocksRelay(clientEndpoint, service, null, 0);
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
