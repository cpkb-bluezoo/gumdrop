/*
 * QuicLoopbackReceiveWindowTuningTest.java
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


package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.recovery.RttEstimator;
import org.junit.Test;

/**
 * The receive window is tuned to the round-trip time: when the peer fills
 * half a window within two round trips the window was the bottleneck and
 * it doubles, up to a ceiling; when raises come further apart it stays
 * as it is.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackReceiveWindowTuningTest {

    private static final int WINDOW = 40000;
    private static final int CHUNK = 1000;

    private QuicLoopback lb;
    private ConnCapture server;
    private ConnCapture client;
    private Endpoint stream;

    private void connect(long streamCeiling, long connectionCeiling) throws Exception {
        lb = new QuicLoopback();
        lb.serverFactory.setMaxData(10 * WINDOW);
        lb.serverFactory.setMaxStreamDataBidiRemote(WINDOW);
        if (streamCeiling > 0) {
            lb.serverFactory.setStreamReceiveWindowCeiling(streamCeiling);
            lb.serverFactory.setConnectionReceiveWindowCeiling(connectionCeiling);
        }
        lb.startFactories();
        server = new ConnCapture();
        client = new ConnCapture();
        lb.startServer(server);
        lb.startClient(null, client);
        lb.pump();
        stream = client.conn.openStream(new Rec());
        stream.send(ByteBuffer.wrap(new byte[1]));
        lb.pump();
        long now = System.nanoTime();
        client.conn.frozenNanos = now;
        server.conn.frozenNanos = now;
        // Pin the server's idea of the round trip at a second, so that
        // the chunk-by-chunk clock steps below are clearly within two
        // round trips, or clearly beyond them.
        Object rtt = ((LossDetector) QuicForger.field(server.conn, "lossDetector")).getRttEstimator();
        Field smoothed = RttEstimator.class.getDeclaredField("smoothedRtt");
        smoothed.setAccessible(true);
        smoothed.setLong(rtt, 1_000_000L);
    }

    // Sends `bytes` in chunks, advancing the clocks by `millisPerChunk`
    // between them, and returns every distinct stream limit the server
    // advertised along the way, in order.
    private List<Long> limitsWhileSending(int bytes, long millisPerChunk) throws Exception {
        List<Long> limits = new ArrayList<Long>();
        for (int sent = 0; sent < bytes; sent += CHUNK) {
            stream.send(ByteBuffer.wrap(new byte[CHUNK]));
            server.conn.clockOffsetMillis += millisPerChunk;
            client.conn.clockOffsetMillis += millisPerChunk;
            lb.pump();
            Map<?, ?> perStream = (Map<?, ?>) QuicForger.field(server.conn, "localMaxStreamData");
            Long limit = (Long) perStream.get(Long.valueOf(0L));
            if (limit != null && (limits.isEmpty() || !limit.equals(limits.get(limits.size() - 1)))) {
                limits.add(limit);
            }
        }
        return limits;
    }

    private static List<Long> raises(List<Long> limits) {
        List<Long> raises = new ArrayList<Long>();
        for (int i = 1; i < limits.size(); i++) {
            raises.add(Long.valueOf(limits.get(i).longValue() - limits.get(i - 1).longValue()));
        }
        return raises;
    }

    @Test
    public void windowDoublesWhenHalfOfItArrivesWithinTwoRoundTrips() throws Exception {
        connect(0, 0);
        List<Long> limits = limitsWhileSending(600000, 1);
        List<Long> raises = raises(limits);
        assertTrue("raises " + raises, raises.size() >= 3);
        // Each raise after the first moves the limit by a window that has
        // doubled since the previous one (give or take the chunk that
        // crossed the threshold).
        for (int i = 1; i < raises.size(); i++) {
            assertTrue("raise " + i + " of " + raises,
                    raises.get(i).longValue() >= 2 * raises.get(i - 1).longValue() - 2 * CHUNK);
        }
        assertTrue("limits " + limits, limits.get(limits.size() - 1).longValue() > 600000);
    }

    @Test
    public void windowStaysWhenRaisesAreFarApart() throws Exception {
        connect(0, 0);
        // 10 s between chunks: far more than two round trips.
        List<Long> limits = limitsWhileSending(200000, 10000);
        List<Long> raises = raises(limits);
        assertTrue("raises " + raises, raises.size() >= 3);
        for (Long raise : raises) {
            // A raise moves the limit by at most one window plus the chunk that crossed the threshold.
            assertTrue("raise " + raise, raise.longValue() <= WINDOW + CHUNK);
        }
    }

    @Test
    public void windowNeverExceedsTheCeiling() throws Exception {
        connect(100000, 1000000);
        List<Long> limits = limitsWhileSending(1000000, 1);
        for (Long raise : raises(limits)) {
            assertTrue("raise " + raise, raise.longValue() <= 100000 + CHUNK);
        }
        Map<?, ?> windows = (Map<?, ?>) QuicForger.field(server.conn, "streamReceiveWindow");
        assertEquals(Long.valueOf(100000), windows.get(Long.valueOf(0L)));
        long connectionWindow = ((Long) QuicForger.field(server.conn, "connectionReceiveWindow")).longValue();
        assertTrue("connection window " + connectionWindow, connectionWindow <= 1000000);
    }
}
