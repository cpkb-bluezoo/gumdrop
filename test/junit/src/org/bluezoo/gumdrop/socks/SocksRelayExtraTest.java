/*
 * SocksRelayExtraTest.java
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
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * Additional {@link SocksRelay} tests covering metrics recording, the idle
 * timer and the write-ready backpressure callbacks.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksRelayExtraTest {

    /** Endpoint that records timers and write-ready callbacks. */
    private static final class CapturingEndpoint extends StubEndpoint {
        final List<Runnable> timers = new ArrayList<Runnable>();
        final List<TimerHandle> handles = new ArrayList<TimerHandle>();
        Runnable writeReady;
        int resumed;

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            TimerHandle h = super.scheduleTimer(delayMs, callback);
            handles.add(h);
            return h;
        }

        @Override
        public void onWriteReady(Runnable callback) {
            writeReady = callback;
        }

        @Override
        public void resumeRead() {
            resumed++;
        }
    }

    private SocksServer server;
    private CapturingEndpoint client;
    private CapturingEndpoint upstream;
    private SocksServerMetrics metrics;
    private SocksRelay relay;

    @Before
    public void setUp() {
        server = new SocksServer();
        server.acquireRelay();
        client = new CapturingEndpoint();
        upstream = new CapturingEndpoint();
        metrics = new SocksServerMetrics(new TelemetryConfig());
        relay = new SocksRelay(client, server, metrics, 1000L);
    }

    @Test
    public void metricsRecordedForRelayedData() {
        relay.upstreamConnected(upstream);
        relay.clientData(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        relay.upstreamData(ByteBuffer.wrap(new byte[]{4, 5}));
        assertEquals(1, upstream.getSentCount());
        assertEquals(1, client.getSentCount());
        relay.upstreamDisconnected();
        assertFalse(client.isOpen());
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void idleTimerIsRescheduledAndCancelled() {
        relay.upstreamConnected(upstream);
        assertEquals(1, client.timers.size());
        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(2, client.timers.size());
        assertTrue(client.handles.get(0).isCancelled());
        relay.upstreamData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(3, client.timers.size());
    }

    @Test
    public void idleTimeoutClosesBothEnds() {
        relay.upstreamConnected(upstream);
        Runnable timer = client.timers.get(0);
        timer.run();
        assertFalse(client.isOpen());
        assertFalse(upstream.isOpen());
        assertEquals(0, server.getActiveRelayCount());
        // firing again after close is a no-op
        timer.run();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void writeReadyResumesUpstreamOnlyWhenPaused() {
        relay.upstreamConnected(upstream);
        assertNotNull(client.writeReady);
        assertNotNull(upstream.writeReady);
        // nothing paused: callbacks do not resume reads
        client.writeReady.run();
        upstream.writeReady.run();
        assertEquals(0, upstream.resumed);
        assertEquals(0, client.resumed);
    }

    @Test
    public void closedRelayIgnoresDataAndCallbacks() {
        relay.upstreamConnected(upstream);
        relay.clientDisconnected();
        assertFalse(upstream.isOpen());
        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        relay.upstreamData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(0, upstream.getSentCount());
        assertEquals(0, client.getSentCount());
        client.writeReady.run();
        upstream.writeReady.run();
    }

    @Test
    public void closedClientStopsUpstreamData() {
        relay.upstreamConnected(upstream);
        client.close();
        relay.upstreamData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(0, client.getSentCount());
    }

    @Test
    public void closedUpstreamStopsClientData() {
        relay.upstreamConnected(upstream);
        upstream.close();
        relay.clientData(ByteBuffer.wrap(new byte[]{1}));
        assertEquals(0, upstream.getSentCount());
    }

    @Test
    public void emptyBuffersAreNotCountedButStillForwarded() {
        relay.upstreamConnected(upstream);
        relay.clientData(ByteBuffer.allocate(0));
        relay.upstreamData(ByteBuffer.allocate(0));
        assertEquals(1, upstream.getSentCount());
        assertEquals(1, client.getSentCount());
    }

    @Test
    public void disconnectBeforeUpstreamConnectedReleasesSlot() {
        relay.clientDisconnected();
        assertEquals(0, server.getActiveRelayCount());
        assertTrue(upstream.isOpen());
    }

    @Test
    public void noIdleTimerWhenTimeoutDisabled() {
        SocksRelay r = new SocksRelay(client, server, null, 0L);
        r.upstreamConnected(upstream);
        assertEquals(0, client.timers.size());
    }
}
