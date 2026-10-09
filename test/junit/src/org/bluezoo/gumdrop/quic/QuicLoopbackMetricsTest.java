/*
 * QuicLoopbackMetricsTest.java
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

import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;
import org.junit.Test;

/**
 * The aggregate QUIC server metrics, which are not tagged for any channel
 * and so reach every exporter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackMetricsTest {

    private QuicLoopback lb;
    private TelemetryConfig config;
    private ConnCapture server;
    private ConnCapture client;

    private void setUp(boolean retry) throws Exception {
        lb = new QuicLoopback();
        config = new TelemetryConfig();
        config.metricsEnabled(true);
        lb.serverFactory.setTelemetryConfig(config);
        lb.serverFactory.setRequireRetry(retry);
    }

    private void connect() throws Exception {
        lb.startFactories();
        server = new ConnCapture();
        lb.startServer(server);
        client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
    }

    private MetricData metric(String name) {
        List<MetricData> all = config.getMeter(QuicServerMetrics.METER_NAME, Gumdrop.VERSION).collect(AggregationTemporality.CUMULATIVE);
        for (MetricData m : all) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private long total(String name) {
        MetricData m = metric(name);
        if (m == null) {
            return 0;
        }
        long sum = 0;
        for (NumberDataPoint p : m.getNumberDataPoints()) {
            sum += p.getLongValue();
        }
        return sum;
    }

    private long observations(String name) {
        MetricData m = metric(name);
        if (m == null) {
            return 0;
        }
        long sum = 0;
        for (HistogramDataPoint p : m.getHistogramDataPoints()) {
            sum += p.getCount();
        }
        return sum;
    }

    @Test
    public void nothingIsRecordedUnlessMetricsAreEnabled() throws Exception {
        setUp(false);
        config.metricsEnabled(false);
        connect();
        assertEquals(0, total("quic.server.connections"));
    }

    @Test
    public void connectionsAndTrafficAreCounted() throws Exception {
        setUp(false);
        connect();
        assertEquals(1, total("quic.server.connections"));
        assertEquals(1, total("quic.server.active_connections"));
        assertTrue(total("quic.server.packets.sent") > 0);
        assertTrue(total("quic.server.packets.received") > 0);
        assertTrue(total("quic.server.bytes.sent") >= total("quic.server.packets.sent"));
        assertTrue(total("quic.server.bytes.received") >= total("quic.server.packets.received"));
        assertEquals(1, observations("quic.server.handshake.duration"));
        assertTrue(observations("quic.server.rtt") > 0);
        client.conn.closeWithApplicationError(0, "bye");
        lb.pump();
        assertEquals(0, total("quic.server.active_connections"));
        assertEquals(1, total("quic.server.connections"));
    }

    @Test
    public void lostPacketsAreCounted() throws Exception {
        setUp(false);
        connect();
        Endpoint e = client.conn.openStream(new Rec());
        e.send(ByteBuffer.wrap(new byte[] {1}));
        lb.pump();
        Endpoint s = server.bidi.recs.get(0).endpoint;
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
        final int dropAt = lb.sentToClient;
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer || index != dropAt;
            }
        };
        for (int i = 0; i < 6; i++) {
            s.send(ByteBuffer.wrap(new byte[] {(byte) i}));
            lb.pump();
        }
        assertTrue(total("quic.server.packets.lost") >= 1);
    }

    @Test
    public void retryPacketsAreCounted() throws Exception {
        setUp(true);
        connect();
        long retries = total("quic.server.retry_packets");
        assertTrue("retries " + retries, retries >= 1 && retries <= lb.toClientLog.size());
        assertEquals("the Retry is not a connection", 1, total("quic.server.connections"));
    }

    @Test
    public void versionNegotiationPacketsAreCounted() throws Exception {
        setUp(false);
        lb.serverFactory.setVersions("2");
        lb.clientFactory.setVersions("1");
        lb.startFactories();
        lb.startServer(new ConnCapture());
        lb.startClient(new Rec(), null);
        lb.pump();
        assertTrue(lb.toClientLog.size() >= 1);
        assertEquals("every datagram the server sent here was a Version Negotiation packet",
                lb.toClientLog.size(), total("quic.server.version_negotiation_packets"));
    }

    @Test
    public void statelessResetsAreCounted() throws Exception {
        setUp(false);
        connect();
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[200]));
        lb.pump();
        // the server forgets the connection and its goodbye is lost
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        server.conn.close();
        lb.filter = null;
        e.send(ByteBuffer.wrap(new byte[200]));
        lb.pump();
        assertEquals(1, total("quic.server.stateless_resets"));
    }
}
