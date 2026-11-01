/*
 * ConnectUdpRelayTest.java
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

package org.bluezoo.gumdrop.http.server;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ConnectUdpRelay} before its upstream socket is opened: datagrams
 * are dropped and closing is idempotent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpRelayTest {

    @Test
    public void datagramsBeforeStartAreDropped() {
        ConnectUdpRelay relay = new ConnectUdpRelay(null);
        relay.receiveDatagram(ByteBuffer.allocate(8));
    }

    @Test
    public void closeIsIdempotentAndStopsRelaying() {
        ConnectUdpRelay relay = new ConnectUdpRelay(null, 0L);
        relay.close();
        relay.close();
        relay.receiveDatagram(ByteBuffer.allocate(8));
    }

    /** Collects the datagrams a relay sends back to the HTTP client and the timers it arms. */
    private static final class Recording extends HttpResponseDefaultsTest.Minimal {
        final List<ByteBuffer> datagrams = new ArrayList<ByteBuffer>();
        final List<Long> timers = new ArrayList<Long>();
        int cancelled;

        @Override
        public boolean sendDatagram(ByteBuffer data) {
            datagrams.add(data);
            return true;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(Long.valueOf(delayMs));
            return null;
        }

        @Override
        public void cancel() {
            cancelled++;
        }
    }

    private static ProtocolHandler upstream(ConnectUdpRelay relay) throws Exception {
        Class<?> type = Class.forName(ConnectUdpRelay.class.getName() + "$UpstreamHandler");
        Constructor<?> ctor = type.getDeclaredConstructor(ConnectUdpRelay.class);
        ctor.setAccessible(true);
        return (ProtocolHandler) ctor.newInstance(relay);
    }

    @Test
    public void repliesAreRelayedAsContextZeroDatagrams() throws Exception {
        Recording response = new Recording();
        ConnectUdpRelay relay = new ConnectUdpRelay(response, 5000L);
        ProtocolHandler handler = upstream(relay);
        handler.connected(null);
        handler.securityEstablished(null);
        handler.receive(ByteBuffer.wrap(new byte[] {9, 8, 7}));
        assertEquals(1, response.datagrams.size());
        HttpDatagramContext decoded = HttpDatagramContext.decode(response.datagrams.get(0));
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        assertEquals(3, decoded.getPayload().remaining());
        assertEquals("each reply restarts the idle timer", 1, response.timers.size());
        assertEquals(5000L, response.timers.get(0).longValue());
    }

    @Test
    public void nothingIsRelayedOnceClosed() throws Exception {
        Recording response = new Recording();
        ConnectUdpRelay relay = new ConnectUdpRelay(response, 0L);
        ProtocolHandler handler = upstream(relay);
        relay.close();
        handler.receive(ByteBuffer.wrap(new byte[] {1}));
        handler.disconnected();
        handler.error(new java.io.IOException("late"));
        assertTrue(response.datagrams.isEmpty());
    }

    @Test
    public void upstreamDisconnectClosesTheRelay() throws Exception {
        Recording response = new Recording();
        ConnectUdpRelay relay = new ConnectUdpRelay(response, 0L);
        ProtocolHandler handler = upstream(relay);
        handler.disconnected();
        handler.receive(ByteBuffer.wrap(new byte[] {1}));
        assertTrue("closed by the disconnect", response.datagrams.isEmpty());
    }

    @Test
    public void upstreamErrorClosesTheRelay() throws Exception {
        Recording response = new Recording();
        ConnectUdpRelay relay = new ConnectUdpRelay(response, 0L);
        ProtocolHandler handler = upstream(relay);
        handler.error(new java.io.IOException("unreachable"));
        handler.receive(ByteBuffer.wrap(new byte[] {1}));
        assertTrue(response.datagrams.isEmpty());
    }
}
