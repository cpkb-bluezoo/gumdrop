/*
 * QuicLoopbackQlogPacketEventsTest.java
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
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackQlogTest.Capture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.FlatJson;
import org.junit.Test;

/**
 * The packet, stream, key, recovery and path qlog events of the transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackQlogPacketEventsTest {

    private QuicLoopback lb;
    private Capture capture;
    private ConnCapture server;
    private ConnCapture client;

    private void connect() throws Exception {
        lb = new QuicLoopback();
        capture = new Capture();
        TelemetryConfig config = new TelemetryConfig();
        config.exporter(capture);
        lb.serverFactory.setTelemetryConfig(config);
        lb.clientFactory.setTelemetryConfig(config);
        lb.serverFactory.setMaxDatagramFrameSize(1200);
        lb.clientFactory.setMaxDatagramFrameSize(1200);
        lb.startFactories();
        server = new ConnCapture();
        lb.startServer(server);
        client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        // the held-back acknowledgements and HANDSHAKE_DONE
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
    }

    private List<Map<String, String>> events(String vantage, String name) throws Exception {
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (LogRecord r : capture.named(vantage, name)) {
            result.add(FlatJson.flatten(r.getBody()));
        }
        return result;
    }

    /** The frame types of one packet event. */
    private static List<String> frameTypes(Map<String, String> packet) {
        List<String> types = new ArrayList<String>();
        for (int i = 0; packet.containsKey("frames." + i + ".frame_type"); i++) {
            types.add(packet.get("frames." + i + ".frame_type"));
        }
        return types;
    }

    private void exchange() throws Exception {
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        e.close();
        lb.pump();
        Rec s = server.bidi.recs.get(server.bidi.recs.size() - 1);
        s.endpoint.send(ByteBuffer.wrap(new byte[] {4, 5, 6, 7}));
        s.endpoint.close();
        lb.pump();
    }

    @Test
    public void sentPacketsCarryTheirHeaderSizeAndFrames() throws Exception {
        connect();
        exchange();
        List<Map<String, String>> sent = events("client", "quic:packet_sent");
        Set<String> types = new HashSet<String>();
        for (Map<String, String> p : sent) {
            types.add(p.get("header.packet_type"));
            assertTrue(p.containsKey("header.packet_number"));
            assertTrue(Integer.parseInt(p.get("raw.length")) > 0);
            assertFalse(frameTypes(p).isEmpty());
        }
        assertTrue(types.toString(), types.contains("initial"));
        assertTrue(types.toString(), types.contains("handshake"));
        assertTrue(types.toString(), types.contains("1RTT"));

        Map<String, String> first = sent.get(0);
        assertEquals("initial", first.get("header.packet_type"));
        assertEquals("00000001", first.get("header.version"));
        assertTrue(first.containsKey("header.scid"));
        assertTrue(first.containsKey("header.dcid"));
        assertTrue(frameTypes(first).contains("crypto"));
        assertTrue(frameTypes(first).contains("padding"));
    }

    @Test
    public void streamFramesNameTheStreamRangeAndFin() throws Exception {
        connect();
        exchange();
        boolean found = false;
        for (Map<String, String> p : events("client", "quic:packet_sent")) {
            for (int i = 0; p.containsKey("frames." + i + ".frame_type"); i++) {
                if ("stream".equals(p.get("frames." + i + ".frame_type"))
                        && "3".equals(p.get("frames." + i + ".length"))) {
                    assertEquals("0", p.get("frames." + i + ".stream_id"));
                    assertEquals("0", p.get("frames." + i + ".offset"));
                    found = true;
                }
            }
        }
        assertTrue(found);
    }

    @Test
    public void ackFramesCarryRangesAndDelay() throws Exception {
        connect();
        exchange();
        boolean found = false;
        for (Map<String, String> p : events("server", "quic:packet_sent")) {
            for (int i = 0; p.containsKey("frames." + i + ".frame_type"); i++) {
                if ("ack".equals(p.get("frames." + i + ".frame_type"))) {
                    assertTrue(p.containsKey("frames." + i + ".acked_ranges.0.0"));
                    assertTrue(p.containsKey("frames." + i + ".ack_delay"));
                    found = true;
                }
            }
        }
        assertTrue(found);
    }

    @Test
    public void receivedPacketsMatchWhatThePeerSent() throws Exception {
        connect();
        exchange();
        Set<String> clientSent = new HashSet<String>();
        for (Map<String, String> p : events("client", "quic:packet_sent")) {
            if ("1RTT".equals(p.get("header.packet_type"))) {
                clientSent.add(p.get("header.packet_number"));
            }
        }
        List<Map<String, String>> received = events("server", "quic:packet_received");
        int oneRtt = 0;
        for (Map<String, String> p : received) {
            assertTrue(Integer.parseInt(p.get("raw.length")) > 0);
            if ("1RTT".equals(p.get("header.packet_type"))) {
                oneRtt++;
                assertTrue(p.get("header.packet_number"), clientSent.contains(p.get("header.packet_number")));
            }
        }
        assertTrue(oneRtt > 0);
        boolean stream = false;
        for (Map<String, String> p : received) {
            stream |= frameTypes(p).contains("stream");
        }
        assertTrue(stream);
    }

    @Test
    public void undecryptablePacketIsReportedDropped() throws Exception {
        connect();
        byte[] garbage = new byte[60];
        byte[] dcid = server.conn.getOurConnectionId();
        garbage[0] = 0x40;
        System.arraycopy(dcid, 0, garbage, 1, dcid.length);
        for (int i = 1 + dcid.length; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 7);
        }
        lb.injectToServer(garbage);
        lb.pump();
        Map<String, String> ours = null;
        for (Map<String, String> d : events("server", "quic:packet_dropped")) {
            if ("60".equals(d.get("raw.length"))) {
                assertEquals("only the injected packet has that size", null, ours);
                ours = d;
            }
        }
        assertTrue(ours != null);
        assertEquals("decryption_failure", ours.get("trigger"));
        assertEquals("1RTT", ours.get("header.packet_type"));
    }

    @Test
    public void congestionStateAndRecoveryParametersAreReported() throws Exception {
        connect();
        List<Map<String, String>> parameters = events("client", "quic:recovery_parameters_set");
        assertEquals(1, parameters.size());
        Map<String, String> p = parameters.get(0);
        assertEquals("3", p.get("reordering_threshold"));
        assertEquals(1.125, Double.parseDouble(p.get("time_threshold")), 1e-9);
        assertEquals(333.0, Double.parseDouble(p.get("initial_rtt")), 1e-9);
        assertTrue(Long.parseLong(p.get("initial_congestion_window")) > 0);
        assertTrue(Long.parseLong(p.get("max_datagram_size")) > 0);
        assertEquals(0.5, Double.parseDouble(p.get("loss_reduction_factor")), 1e-9);
        assertEquals("3", p.get("persistent_congestion_threshold"));

        List<Map<String, String>> states = events("client", "quic:congestion_state_updated");
        assertFalse(states.isEmpty());
        assertEquals("slow_start", states.get(0).get("new"));
    }

    @Test
    public void lossMovesTheCongestionControllerToRecovery() throws Exception {
        connect();
        final int dropAt = lb.sentToServer;
        Endpoint e = client.conn.openStream(new Rec());
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == dropAt);
            }
        };
        for (int i = 0; i < 6; i++) {
            e.send(ByteBuffer.wrap(new byte[] {(byte) i}));
            lb.pump();
        }
        boolean recovery = false;
        for (Map<String, String> s : events("client", "quic:congestion_state_updated")) {
            recovery |= "recovery".equals(s.get("new"));
        }
        assertTrue(recovery);
    }

    @Test
    public void keysInstalledAndUpdatedAreReported() throws Exception {
        connect();
        String[] vantages = { "client", "server" };
        for (int i = 0; i < vantages.length; i++) {
            Set<String> installed = new HashSet<String>();
            for (Map<String, String> k : events(vantages[i], "quic:key_updated")) {
                if ("tls".equals(k.get("trigger"))) {
                    installed.add(k.get("key_type"));
                }
            }
            assertTrue(vantages[i] + " " + installed, installed.contains("client_handshake_secret"));
            assertTrue(vantages[i] + " " + installed, installed.contains("server_handshake_secret"));
            assertTrue(vantages[i] + " " + installed, installed.contains("client_1rtt_secret"));
            assertTrue(vantages[i] + " " + installed, installed.contains("server_1rtt_secret"));
        }
        assertTrue(client.conn.requestKeyUpdate());
        exchange();
        boolean local = false;
        for (Map<String, String> k : events("client", "quic:key_updated")) {
            // the client's own new send key; the server's, now in use for
            // receiving, follows from it
            if ("local_update".equals(k.get("trigger")) && "client_1rtt_secret".equals(k.get("key_type"))) {
                assertEquals("1", k.get("generation"));
                local = true;
            }
        }
        boolean remote = false;
        for (Map<String, String> k : events("server", "quic:key_updated")) {
            if ("remote_update".equals(k.get("trigger"))) {
                remote = true;
            }
        }
        assertTrue(local);
        assertTrue(remote);
    }

    @Test
    public void learningThePeersConnectionIdIsReported() throws Exception {
        connect();
        String serverCid = QlogJson.hex(server.conn.getOurConnectionId());
        boolean found = false;
        for (Map<String, String> u : events("client", "quic:connection_id_updated")) {
            if ("remote".equals(u.get("owner")) && serverCid.equals(u.get("new"))) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void pathIsReportedAtTheStartAndAfterMigration() throws Exception {
        connect();
        List<Map<String, String>> first = events("client", "quic:tuple_assigned");
        assertEquals(1, first.size());
        assertTrue(first.get(0).containsKey("tuple_id"));
        assertEquals("127.0.0.1", first.get(0).get("tuple_remote.ip_v4"));
        assertEquals(String.valueOf(QuicLoopback.SERVER_ADDRESS.getPort()), first.get(0).get("tuple_remote.port_v4"));

        Endpoint e = client.conn.openStream(new Rec());
        e.send(ByteBuffer.wrap(new byte[10]));
        lb.pump();
        lb.clientSource = new java.net.InetSocketAddress("127.0.0.1", 50077);
        e.send(ByteBuffer.wrap(new byte[10]));
        lb.pump();
        List<Map<String, String>> server = events("server", "quic:tuple_assigned");
        assertEquals(2, server.size());
        assertEquals("50077", server.get(1).get("tuple_remote.port_v4"));
        assertFalse(server.get(0).get("tuple_id").equals(server.get(1).get("tuple_id")));
    }

    @Test
    public void streamsReportTheirStateAndTheDataMovingThroughThem() throws Exception {
        connect();
        exchange();
        List<String> clientStates = new ArrayList<String>();
        for (Map<String, String> s : events("client", "quic:stream_state_updated")) {
            if ("0".equals(s.get("stream_id"))) {
                assertEquals("bidirectional", s.get("stream_type"));
                clientStates.add(s.get("new"));
            }
        }
        assertTrue(clientStates.toString(), clientStates.contains("open"));
        assertTrue(clientStates.toString(), clientStates.contains("half_closed_local"));
        assertTrue(clientStates.toString(), clientStates.contains("closed"));

        List<String> serverStates = new ArrayList<String>();
        for (Map<String, String> s : events("server", "quic:stream_state_updated")) {
            if ("0".equals(s.get("stream_id"))) {
                serverStates.add(s.get("new"));
            }
        }
        assertTrue(serverStates.toString(), serverStates.contains("open"));
        assertTrue(serverStates.toString(), serverStates.contains("half_closed_remote"));
        assertTrue(serverStates.toString(), serverStates.contains("closed"));

        boolean sent = false;
        for (Map<String, String> m : events("client", "quic:stream_data_moved")) {
            if ("0".equals(m.get("stream_id")) && "application".equals(m.get("from"))) {
                assertEquals("transport", m.get("to"));
                assertEquals("0", m.get("offset"));
                assertEquals("3", m.get("length"));
                sent = true;
            }
        }
        assertTrue(sent);
        boolean delivered = false;
        for (Map<String, String> m : events("server", "quic:stream_data_moved")) {
            if ("0".equals(m.get("stream_id")) && "transport".equals(m.get("from"))) {
                assertEquals("application", m.get("to"));
                assertEquals("3", m.get("length"));
                delivered = true;
            }
        }
        assertTrue(delivered);
    }

    @Test
    public void datagramsReportTheirMovement() throws Exception {
        connect();
        final List<byte[]> received = new ArrayList<byte[]>();
        server.conn.setDatagramHandler(new org.bluezoo.gumdrop.ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void securityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void error(Exception cause) {
            }

            @Override
            public void datagramReceived(ByteBuffer data) {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                received.add(copy);
            }
        });
        assertTrue(client.conn.sendDatagram(ByteBuffer.wrap(new byte[] {9, 8, 7, 6, 5})));
        lb.pump();
        assertEquals(1, received.size());
        List<Map<String, String>> sent = events("client", "quic:datagram_data_moved");
        assertEquals(1, sent.size());
        assertEquals("application", sent.get(0).get("from"));
        assertEquals("transport", sent.get(0).get("to"));
        assertEquals("5", sent.get(0).get("length"));
        List<Map<String, String>> got = events("server", "quic:datagram_data_moved");
        assertEquals(1, got.size());
        assertEquals("transport", got.get(0).get("from"));
        assertEquals("application", got.get(0).get("to"));
        assertEquals("5", got.get(0).get("length"));
    }

    @Test
    public void negotiatedProtocolIsReported() throws Exception {
        lb = new QuicLoopback();
        capture = new Capture();
        TelemetryConfig config = new TelemetryConfig();
        config.exporter(capture);
        lb.serverFactory.setTelemetryConfig(config);
        lb.clientFactory.setTelemetryConfig(config);
        lb.serverFactory.setApplicationProtocols("h3,hq-interop");
        lb.clientFactory.setApplicationProtocols("hq-interop,h3");
        lb.startFactories();
        lb.startServer(new ConnCapture());
        lb.startClient(null, new ConnCapture());
        lb.pump();
        String[] vantages = { "client", "server" };
        for (int i = 0; i < vantages.length; i++) {
            List<Map<String, String>> info = events(vantages[i], "quic:alpn_information");
            assertEquals(vantages[i], 1, info.size());
            Map<String, String> a = info.get(0);
            // the server prefers h3
            assertEquals("h3", a.get("chosen_alpn.string_value"));
            String ours = "client".equals(vantages[i]) ? "client_alpns" : "server_alpns";
            assertEquals("client".equals(vantages[i]) ? "hq-interop" : "h3", a.get(ours + ".0.string_value"));
        }
    }

    @Test
    public void parametersRememberedFromATicketAreReportedRestored() throws Exception {
        SessionTicketCache.clear();
        try {
            lb = new QuicLoopback();
            capture = new Capture();
            TelemetryConfig config = new TelemetryConfig();
            config.exporter(capture);
            lb.serverFactory.setTelemetryConfig(config);
            lb.clientFactory.setTelemetryConfig(config);
            lb.serverFactory.setEarlyDataEnabled(true);
            lb.clientFactory.setEarlyDataEnabled(true);
            lb.startFactories();
            lb.startServer(new ConnCapture());
            lb.startClient(null, new ConnCapture());
            lb.pump();
            lb.pump();
            assertTrue(events("client", "quic:parameters_restored").isEmpty());

            lb.startServer(new ConnCapture());
            lb.startClientEarly(new ConnCapture(), new QuicEngine.EarlyDataHandler() {
                @Override
                public void earlyDataReady(QuicConnection connection) {
                }
            });
            lb.pump();
            List<Map<String, String>> restored = events("client", "quic:parameters_restored");
            assertEquals(1, restored.size());
            assertTrue(restored.get(0).containsKey("initial_max_data"));
            assertFalse(restored.get(0).containsKey("owner"));
        } finally {
            SessionTicketCache.clear();
        }
    }
}
