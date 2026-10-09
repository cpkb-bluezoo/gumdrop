/*
 * H3QlogTest.java
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

package org.bluezoo.gumdrop.http.h3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.bluezoo.gumdrop.testsupport.QlogCapture;
import org.junit.Test;

/**
 * The HTTP/3 and HTTP capsule qlog events (draft-ietf-quic-qlog-h3-events-13).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3QlogTest {

    private static QlogCapture capture;

    private static QuicConnection connection(boolean server) {
        capture = new QlogCapture();
        TelemetryConfig tc = new TelemetryConfig();
        tc.exporter(capture);
        return QuicConnectionTestFactory.create(server, tc);
    }

    private static H3ServerFlowTest.Fixture serverFixture(final QuicConnection conn) {
        return new H3ServerFlowTest.Fixture(conn, null, null, null, false, false, null);
    }

    @Test
    public void controlAndQpackStreamsAndSettingsAreReported() throws Exception {
        QuicConnection conn = connection(true);
        H3ServerFlowTest.Fixture f = serverFixture(conn);
        List<Map<String, String>> types = capture.events("http3:stream_type_set");
        boolean control = false;
        boolean encoder = false;
        boolean decoder = false;
        for (Map<String, String> t : types) {
            assertEquals("local", t.get("owner"));
            control |= "control".equals(t.get("new"));
            encoder |= "qpack_encode".equals(t.get("new"));
            decoder |= "qpack_decode".equals(t.get("new"));
        }
        assertTrue(types.toString(), control && encoder && decoder);

        List<Map<String, String>> parameters = capture.events("http3:parameters_set");
        assertEquals(1, parameters.size());
        Map<String, String> p = parameters.get(0);
        assertEquals("local", p.get("owner"));
        assertEquals("true", p.get("enable_connect_protocol"));
        assertEquals("true", p.get("h3_datagram"));
        assertTrue(p.containsKey("max_table_capacity"));
        assertTrue(p.containsKey("max_header_list_size"));

        List<Map<String, String>> created = capture.events("http3:frame_created");
        assertEquals("settings", created.get(0).get("frame.frame_type"));
        assertEquals(QlogAttributes.SCHEMA_HTTP3, capture.schemaOf("http3:frame_created"));
        assertTrue(f.conn == conn);
    }

    @Test
    public void requestAndResponseFramesAreReported() throws Exception {
        QuicConnection conn = connection(true);
        H3ServerFlowTest.Fixture f = serverFixture(conn);
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.concat(H3ServerFlowTest.get("/qlog"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2})));
        stream.readFinished();

        boolean request = false;
        for (Map<String, String> t : capture.events("http3:stream_type_set")) {
            request |= "request".equals(t.get("new")) && "remote".equals(t.get("owner"));
        }
        assertTrue(request);

        boolean headers = false;
        boolean data = false;
        for (Map<String, String> fp : capture.events("http3:frame_parsed")) {
            if ("headers".equals(fp.get("frame.frame_type"))) {
                for (int i = 0; fp.containsKey("frame.headers." + i + ".name"); i++) {
                    if (":path".equals(fp.get("frame.headers." + i + ".name"))) {
                        assertEquals("/qlog", fp.get("frame.headers." + i + ".value"));
                        headers = true;
                    }
                }
            }
            if ("data".equals(fp.get("frame.frame_type")) && "2".equals(fp.get("frame.length"))) {
                data = true;
            }
        }
        assertTrue(headers);
        assertTrue(data);

        f.rec.state.status(HttpStatus.OK.code);
        f.rec.state.header("content-type", "text/plain");
        f.rec.state.bodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        f.rec.state.endMessage();
        boolean status = false;
        boolean body = false;
        for (Map<String, String> fc : capture.events("http3:frame_created")) {
            if ("headers".equals(fc.get("frame.frame_type"))) {
                for (int i = 0; fc.containsKey("frame.headers." + i + ".name"); i++) {
                    status |= ":status".equals(fc.get("frame.headers." + i + ".name"))
                            && "200".equals(fc.get("frame.headers." + i + ".value"));
                }
            }
            body |= "data".equals(fc.get("frame.frame_type")) && "3".equals(fc.get("frame.length"));
        }
        assertTrue(status);
        assertTrue(body);
    }

    @Test
    public void everyEventSharesTheConnectionGroup() throws Exception {
        QuicConnection conn = connection(true);
        H3ServerFlowTest.Fixture f = serverFixture(conn);
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.get("/"));
        assertFalse(capture.size() == 0);
        assertTrue(capture.firstGroupId().matches("[0-9a-f]+"));
    }

    @Test
    public void peerControlStreamIsReported() throws Exception {
        QuicConnection conn = connection(true);
        H3ControlStream s = new H3ControlStream(conn, new H3ControlStream.Listener() {
            @Override
            public void settingsReceived(long[] settings) {
            }

            @Override
            public void goawayReceived(long streamOrPushId) {
            }

            @Override
            public void priorityUpdateReceived(long streamId, String fieldValue) {
            }
        }, new Encoder(0), new Decoder(4096), false);
        byte[] fv = "u=2".getBytes();
        ByteBuffer pu = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(4, fv.length));
        H3Writer.writePriorityUpdateRequest(pu, 4, fv);
        ByteBuffer settings = ByteBuffer.allocate(H3Writer.settingsLength(new long[] {1, 100, 6, 2000, 0x33, 1}));
        H3Writer.writeSettings(settings, new long[] {1, 100, 6, 2000, 0x33, 1});
        ByteBuffer goaway = ByteBuffer.allocate(H3Writer.goawayLength(8));
        H3Writer.writeGoaway(goaway, 8);
        s.receive(ByteBuffer.wrap(H3ServerFlowTest.concat(new byte[] {0x00}, settings.array(), pu.array(),
                goaway.array())));

        List<Map<String, String>> types = capture.events("http3:stream_type_set");
        assertEquals("remote", types.get(0).get("owner"));
        assertEquals("control", types.get(0).get("new"));

        Map<String, String> p = capture.events("http3:parameters_set").get(0);
        assertEquals("remote", p.get("owner"));
        assertEquals("100", p.get("max_table_capacity"));
        assertEquals("2000", p.get("max_header_list_size"));
        assertEquals("true", p.get("h3_datagram"));

        boolean settingsFrame = false;
        boolean goawayFrame = false;
        for (Map<String, String> fp : capture.events("http3:frame_parsed")) {
            settingsFrame |= "settings".equals(fp.get("frame.frame_type"));
            goawayFrame |= "goaway".equals(fp.get("frame.frame_type")) && "8".equals(fp.get("frame.id"));
        }
        assertTrue(settingsFrame);
        assertTrue(goawayFrame);

        Map<String, String> priority = capture.events("http3:priority_updated").get(0);
        assertEquals("request", priority.get("type"));
        assertEquals("4", priority.get("id"));
        assertEquals("u=2", priority.get("new"));
    }

    @Test
    public void httpDatagramsAreReported() throws Exception {
        QuicConnection conn = connection(true);
        H3ServerFlowTest.Fixture f = serverFixture(conn);
        // the peer must accept datagrams before it may say it wants HTTP Datagrams
        TransportParameters peer = new TransportParameters();
        peer.setMaxDatagramFrameSize(1200);
        peer.setInitialMaxStreamsBidi(100);
        peer.setInitialMaxStreamsUni(100);
        peer.setInitialMaxData(10_000_000);
        conn.transportParametersReceived(peer);
        f.server.settingsReceived(new long[] {H3FrameHandler.SETTINGS_H3_DATAGRAM, 1});
        // whether the datagram can be sent does not matter here, only that it is reported
        f.server.sendHttpDatagram(4, new byte[] {1, 2, 3});
        Map<String, String> created = capture.events("http3:datagram_created").get(0);
        assertEquals("1", created.get("quarter_stream_id"));
        assertEquals("3", created.get("payload_length"));

        Method on = Http3ServerHandler.class.getDeclaredMethod("onHttpDatagram", ByteBuffer.class);
        on.setAccessible(true);
        on.invoke(f.server, ByteBuffer.wrap(H3Datagram.encode(8, new byte[] {9, 9})));
        Map<String, String> parsed = capture.events("http3:datagram_parsed").get(0);
        assertEquals("2", parsed.get("quarter_stream_id"));
        assertEquals("2", parsed.get("payload_length"));
    }

    @Test
    public void capsulesAreReported() throws Exception {
        QuicConnection conn = connection(true);
        H3ServerFlowTest.Fixture f = serverFixture(conn);
        f.rec.datagrams = true;
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "POST", ":scheme", "https",
                ":path", "/", ":authority", "x", "capsule-protocol", "?1"));
        byte[] datagramCapsule = new byte[] {0x00, 0x03, 1, 2, 3};
        byte[] otherCapsule = new byte[] {0x17, 0x01, 9};
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.dataFrame(H3ServerFlowTest.concat(datagramCapsule, otherCapsule)));
        List<Map<String, String>> parsed = capture.events("http:capsule_parsed");
        assertEquals(2, parsed.size());
        assertEquals("datagram", parsed.get(0).get("capsule.capsule_type"));
        assertEquals("3", parsed.get(0).get("capsule.payload_length"));
        assertEquals("unknown", parsed.get(1).get("capsule.capsule_type"));
        assertEquals("23", parsed.get(1).get("capsule.raw_capsule_type"));
        assertEquals(QlogAttributes.SCHEMA_HTTP, capture.schemaOf("http:capsule_parsed"));

        assertTrue(stream.sendCapsule(0x17, ByteBuffer.wrap(new byte[] {5})));
        List<Map<String, String>> created = capture.events("http:capsule_created");
        assertEquals(1, created.size());
        assertEquals("23", created.get(0).get("capsule.raw_capsule_type"));
        assertTrue(created.get(0).get("stream_id") != null);
    }

    @Test
    public void clientConnectionAndRequestAreReported() throws Exception {
        QuicConnection conn = connection(false);
        Http3ClientHandler h = new Http3ClientHandler(conn);
        Map<String, String> parameters = capture.events("http3:parameters_set").get(0);
        assertEquals("local", parameters.get("owner"));
        boolean control = false;
        for (Map<String, String> t : capture.events("http3:stream_type_set")) {
            control |= "control".equals(t.get("new")) && "local".equals(t.get("owner"));
        }
        assertTrue(control);

        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        h.sendRequest(H3ClientFlowTest.request("GET"), rec);
        boolean request = false;
        for (Map<String, String> t : capture.events("http3:stream_type_set")) {
            request |= "request".equals(t.get("new")) && "local".equals(t.get("owner"));
        }
        assertTrue(request);
        boolean method = false;
        for (Map<String, String> fc : capture.events("http3:frame_created")) {
            if ("headers".equals(fc.get("frame.frame_type"))) {
                for (int i = 0; fc.containsKey("frame.headers." + i + ".name"); i++) {
                    method |= ":method".equals(fc.get("frame.headers." + i + ".name"))
                            && "GET".equals(fc.get("frame.headers." + i + ".value"));
                }
            }
        }
        assertTrue(method);

        H3ClientStream s = H3ClientFlowTest.only(h);
        H3ServerFlowTest.feed(s, H3ServerFlowTest.concat(
                H3ClientFlowTest.response("200", "content-length", "3"),
                H3ServerFlowTest.dataFrame(new byte[] {1, 2, 3})));
        boolean status = false;
        boolean body = false;
        for (Map<String, String> fp : capture.events("http3:frame_parsed")) {
            if ("headers".equals(fp.get("frame.frame_type"))) {
                for (int i = 0; fp.containsKey("frame.headers." + i + ".name"); i++) {
                    status |= ":status".equals(fp.get("frame.headers." + i + ".name"))
                            && "200".equals(fp.get("frame.headers." + i + ".value"));
                }
            }
            body |= "data".equals(fp.get("frame.frame_type")) && "3".equals(fp.get("frame.length"));
        }
        assertTrue(status);
        assertTrue(body);
    }
}
