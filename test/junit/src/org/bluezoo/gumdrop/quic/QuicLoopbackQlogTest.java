/*
 * QuicLoopbackQlogTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.testsupport.FlatJson;
import org.junit.Test;

/**
 * qlog events emitted by the transport as telemetry log records.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackQlogTest {

    /** Keeps what reaches the telemetry pipeline. */
    static final class Capture implements TelemetryExporter {
        final List<LogRecord> records = new ArrayList<LogRecord>();
        final boolean qlog;
        int others;

        Capture() {
            this(true);
        }

        Capture(boolean qlog) {
            this.qlog = qlog;
        }

        @Override
        public boolean accepts(LogLevel level) {
            return qlog && level == LogLevel.QLOG;
        }

        @Override
        public boolean acceptsTraces() {
            return false;
        }

        @Override
        public synchronized void export(LogRecord record) {
            records.add(record);
        }

        @Override
        public void export(Trace trace) {
            others++;
        }

        @Override
        public void export(List<MetricData> metrics) {
            others++;
        }

        @Override
        public void flush() {
        }

        @Override
        public void shutdown() {
        }

        synchronized List<LogRecord> named(String vantage, String name) {
            List<LogRecord> result = new ArrayList<LogRecord>();
            for (LogRecord r : records) {
                if (vantage.equals(attr(r, QlogAttributes.VANTAGE_POINT)) && name.equals(r.getKey())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    static String attr(LogRecord r, String key) {
        for (org.bluezoo.gumdrop.telemetry.Attribute a : r.getAttributes()) {
            if (a.getKey().equals(key)) {
                return a.getStringValue();
            }
        }
        return null;
    }

    private QuicLoopback lb;
    private Capture capture;
    private ConnCapture server;
    private ConnCapture client;

    private void connect(boolean qlog) throws Exception {
        lb = new QuicLoopback();
        capture = new Capture(qlog);
        TelemetryConfig config = new TelemetryConfig();
        config.exporter(capture);
        lb.serverFactory.setTelemetryConfig(config);
        lb.clientFactory.setTelemetryConfig(config);
        lb.startFactories();
        server = new ConnCapture();
        lb.startServer(server);
        client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
    }

    @Test
    public void nothingIsEmittedUnlessQlogIsEnabled() throws Exception {
        connect(false);
        Endpoint e = client.conn.openStream(new Rec());
        e.send(ByteBuffer.wrap(new byte[] {1}));
        lb.pump();
        assertEquals(0, capture.records.size());
    }

    @Test
    public void everyRecordIsTaggedForTheQlogChannel() throws Exception {
        connect(true);
        assertFalse(capture.records.isEmpty());
        for (LogRecord r : capture.records) {
            assertEquals(LogLevel.QLOG, r.getLevel());
            assertNotNull(r.getKey());
            assertTrue(r.getKey().indexOf(':') > 0);
            assertEquals(QlogAttributes.SCHEMA_QUIC, attr(r, QlogAttributes.SCHEMA));
            String vantage = attr(r, QlogAttributes.VANTAGE_POINT);
            assertTrue(vantage.equals("client") || vantage.equals("server"));
            // the body is the event's data object
            FlatJson.flatten(r.getBody());
        }
    }

    @Test
    public void bothEndsShareTheOriginalDestinationConnectionIdAsGroup() throws Exception {
        connect(true);
        String clientGroup = attr(capture.named("client", "quic:connection_started").get(0), QlogAttributes.GROUP_ID);
        String serverGroup = attr(capture.named("server", "quic:connection_started").get(0), QlogAttributes.GROUP_ID);
        assertTrue(clientGroup.matches("[0-9a-f]{2,}"));
        assertEquals(clientGroup, serverGroup);
        for (LogRecord r : capture.records) {
            assertEquals(clientGroup, attr(r, QlogAttributes.GROUP_ID));
        }
    }

    @Test
    public void handshakeProducesConnectionParameterAndVersionEvents() throws Exception {
        connect(true);
        String[] vantages = { "client", "server" };
        for (int i = 0; i < vantages.length; i++) {
            String v = vantages[i];
            assertEquals(v, 1, capture.named(v, "quic:connection_started").size());
            Map<String, String> started = FlatJson.flatten(capture.named(v, "quic:connection_started").get(0).getBody());
            assertTrue(started.containsKey("src_port"));
            assertTrue(started.containsKey("dst_port"));
            assertTrue(started.containsKey("src_cid"));
            assertTrue(started.containsKey("dst_cid"));

            List<LogRecord> parameters = capture.named(v, "quic:parameters_set");
            assertEquals(v, 2, parameters.size());
            boolean local = false;
            boolean remote = false;
            for (LogRecord r : parameters) {
                Map<String, String> data = FlatJson.flatten(r.getBody());
                local |= "local".equals(data.get("owner"));
                remote |= "remote".equals(data.get("owner"));
                assertTrue(data.containsKey("initial_max_data"));
                assertTrue(data.containsKey("max_ack_delay"));
            }
            assertTrue(local && remote);

            List<LogRecord> versions = capture.named(v, "quic:version_information");
            assertFalse(v, versions.isEmpty());
            assertTrue(FlatJson.flatten(versions.get(0).getBody()).containsKey("chosen_version"));
        }
    }

    @Test
    public void handshakeKeysAreReportedDiscardedInBothDirections() throws Exception {
        connect(true);
        // the held-back acknowledgement and HANDSHAKE_DONE complete the confirmation
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
        String[] vantages = { "client", "server" };
        for (int i = 0; i < vantages.length; i++) {
            List<String> types = new ArrayList<String>();
            for (LogRecord r : capture.named(vantages[i], "quic:key_discarded")) {
                types.add(FlatJson.flatten(r.getBody()).get("key_type"));
            }
            assertTrue(vantages[i] + " " + types, types.contains("client_initial_secret"));
            assertTrue(vantages[i] + " " + types, types.contains("server_initial_secret"));
            assertTrue(vantages[i] + " " + types, types.contains("client_handshake_secret"));
            assertTrue(vantages[i] + " " + types, types.contains("server_handshake_secret"));
        }
    }

    @Test
    public void ackedTrafficReportsRecoveryMetrics() throws Exception {
        connect(true);
        Endpoint e = client.conn.openStream(new Rec());
        e.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        lb.pump();
        List<LogRecord> metrics = capture.named("client", "quic:recovery_metrics_updated");
        assertFalse(metrics.isEmpty());
        Map<String, String> data = FlatJson.flatten(metrics.get(metrics.size() - 1).getBody());
        assertTrue(data.containsKey("smoothed_rtt"));
        assertTrue(data.containsKey("min_rtt"));
        assertTrue(data.containsKey("latest_rtt"));
        assertTrue(data.containsKey("rtt_variance"));
        assertTrue(Long.parseLong(data.get("congestion_window")) > 0);
        assertTrue(data.containsKey("bytes_in_flight"));
    }

    @Test
    public void timesNeverGoBackwardsWithinAConnection() throws Exception {
        connect(true);
        Endpoint e = client.conn.openStream(new Rec());
        e.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        lb.pump();
        String[] vantages = { "client", "server" };
        for (int i = 0; i < vantages.length; i++) {
            long last = 0;
            for (LogRecord r : capture.records) {
                if (vantages[i].equals(attr(r, QlogAttributes.VANTAGE_POINT))) {
                    assertTrue(r.getTimeUnixNano() >= last);
                    last = r.getTimeUnixNano();
                }
            }
            assertTrue(last > 0);
        }
        // times are wall-clock based
        long now = System.currentTimeMillis() * 1_000_000L;
        assertTrue(Math.abs(capture.records.get(0).getTimeUnixNano() - now) < 3_600_000_000_000L);
    }

    @Test
    public void closingReportsWhoClosed() throws Exception {
        connect(true);
        client.conn.closeWithApplicationError(42, "bye");
        lb.pump();
        List<LogRecord> local = capture.named("client", "quic:connection_closed");
        List<LogRecord> remote = capture.named("server", "quic:connection_closed");
        assertEquals(1, local.size());
        assertEquals(1, remote.size());
        Map<String, String> l = FlatJson.flatten(local.get(0).getBody());
        Map<String, String> r = FlatJson.flatten(remote.get(0).getBody());
        assertEquals("local", l.get("owner"));
        assertEquals("42", l.get("application_code"));
        assertEquals("bye", l.get("reason"));
        assertEquals("remote", r.get("owner"));
        assertEquals("42", r.get("application_code"));
    }

    @Test
    public void lostPacketIsReportedWithItsNumber() throws Exception {
        connect(true);
        // let the held-back acknowledgement from the handshake go out
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
        final int dropAt = lb.sentToServer;
        long dropped = ((long[]) QuicForger.field(client.conn, "sendPacketNumber"))[2];
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == dropAt);
            }
        };
        Endpoint e = client.conn.openStream(new Rec());
        for (int i = 0; i < 6; i++) {
            e.send(ByteBuffer.wrap(new byte[] {(byte) i}));
            lb.pump();
        }
        List<LogRecord> lost = capture.named("client", "quic:packet_lost");
        assertFalse(lost.isEmpty());
        Map<String, String> data = FlatJson.flatten(lost.get(0).getBody());
        assertEquals("1RTT", data.get("header.packet_type"));
        assertEquals(String.valueOf(dropped), data.get("header.packet_number"));
    }
}
