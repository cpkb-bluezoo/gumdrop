/*
 * H3ControlStreamTest.java
 * Copyright (C) 2025 Chris Burdess
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
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.junit.Test;

/**
 * Tests for {@link H3ControlStream}: stream type detection and control
 * stream frame validation (RFC 9114 section 6.2, 7.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ControlStreamTest {

    static final class Listener implements H3ControlStream.Listener {
        final List<String> events = new ArrayList<String>();

        @Override
        public void settingsReceived(long[] settings) {
            events.add("settings");
        }

        @Override
        public void goawayReceived(long streamOrPushId) {
            events.add("goaway:" + streamOrPushId);
        }

        @Override
        public void priorityUpdateReceived(long streamId, String fieldValue) {
            events.add("priority:" + streamId + ":" + fieldValue);
        }
    }

    private static H3ControlStream create(Listener l, boolean client) {
        QuicConnection conn = QuicConnectionTestFactory.create(!client);
        return new H3ControlStream(conn, l, new Encoder(0), new Decoder(4096), client);
    }

    private static byte[] settings(long... values) {
        ByteBuffer b = ByteBuffer.allocate(H3Writer.settingsLength(values));
        H3Writer.writeSettings(b, values);
        return b.array();
    }

    private static byte[] goaway(long id) {
        ByteBuffer b = ByteBuffer.allocate(H3Writer.goawayLength(id));
        H3Writer.writeGoaway(b, id);
        return b.array();
    }

    private static byte[] join(byte[]... parts) {
        return H3ServerFlowTest.concat(parts);
    }

    private static void feed(H3ControlStream s, byte[] data) {
        s.receive(ByteBuffer.wrap(data));
    }

    private static final byte[] TYPE_CONTROL = new byte[] {0x00};

    @Test
    public void testSettingsGoawayAndPriority() {
        Listener l = new Listener();
        H3ControlStream s = create(l, false);
        s.connected(null);
        s.securityEstablished(null);
        byte[] fv = "u=2".getBytes();
        ByteBuffer pu = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(4, fv.length));
        H3Writer.writePriorityUpdateRequest(pu, 4, fv);
        ByteBuffer mp = ByteBuffer.allocate(H3Writer.maxPushIdLength(8));
        H3Writer.writeMaxPushId(mp, 8);
        feed(s, join(TYPE_CONTROL, settings(1, 100, 6, 2000), pu.array(), mp.array(),
                goaway(8), goaway(4)));
        assertEquals("settings", l.events.get(0));
        assertTrue(l.events.toString(), l.events.contains("priority:4:u=2"));
        assertTrue(l.events.contains("goaway:8"));
        assertTrue(l.events.contains("goaway:4"));
    }

    @Test
    public void testFragmentedStreamTypeAndFrames() {
        Listener l = new Listener();
        H3ControlStream s = create(l, false);
        byte[] all = join(new byte[] {0x40, 0x00}, settings(8, 1));
        for (int i = 0; i < all.length; i++) {
            feed(s, new byte[] {all[i]});
        }
        s.receive(ByteBuffer.allocate(0));
        assertEquals("settings", l.events.get(0));
    }

    @Test
    public void testSettingsErrors() {
        Listener l = new Listener();
        H3ControlStream s = create(l, false);
        feed(s, join(TYPE_CONTROL, settings(1, 100), settings(1, 100)));
        H3ControlStream s2 = create(new Listener(), false);
        feed(s2, join(TYPE_CONTROL, settings(0x02, 1)));
        H3ControlStream s3 = create(new Listener(), false);
        feed(s3, join(TYPE_CONTROL, settings(0x08, 5)));
        assertEquals(1, l.events.size());
    }

    @Test
    public void testFramesBeforeSettings() {
        byte[] data = H3ServerFlowTest.dataFrame(new byte[] {1});
        byte[] headers = H3ServerFlowTest.headersFrame(":method", "GET");
        byte[] fv = "u=1".getBytes();
        ByteBuffer pu = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(0, fv.length));
        H3Writer.writePriorityUpdateRequest(pu, 0, fv);
        ByteBuffer pp = ByteBuffer.allocate(H3Writer.priorityUpdatePushLength(0, fv.length));
        H3Writer.writePriorityUpdatePush(pp, 0, fv);
        ByteBuffer cp = ByteBuffer.allocate(H3Writer.cancelPushLength(0));
        H3Writer.writeCancelPush(cp, 0);
        ByteBuffer mp = ByteBuffer.allocate(H3Writer.maxPushIdLength(0));
        H3Writer.writeMaxPushId(mp, 0);
        ByteBuffer ps = ByteBuffer.allocate(H3Writer.pushPromiseLength(0, 3));
        H3Writer.writePushPromise(ps, 0, new byte[] {0, 0, (byte) 0xd1});
        byte[][] frames = new byte[][] {data, headers, pu.array(), pp.array(), cp.array(),
            mp.array(), ps.array(), goaway(0), new byte[] {0x21, 0x00}, new byte[] {0x40, 0x40, 0x00}};
        for (int i = 0; i < frames.length; i++) {
            H3ControlStream s = create(new Listener(), false);
            feed(s, join(TYPE_CONTROL, frames[i]));
        }
    }

    @Test
    public void testFramesAfterSettings() {
        byte[] data = H3ServerFlowTest.dataFrame(new byte[] {1});
        byte[] headers = H3ServerFlowTest.headersFrame(":method", "GET");
        byte[] fv = "u=1".getBytes();
        ByteBuffer pu = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(1, fv.length));
        H3Writer.writePriorityUpdateRequest(pu, 1, fv);
        ByteBuffer pp = ByteBuffer.allocate(H3Writer.priorityUpdatePushLength(0, fv.length));
        H3Writer.writePriorityUpdatePush(pp, 0, fv);
        ByteBuffer cp = ByteBuffer.allocate(H3Writer.cancelPushLength(0));
        H3Writer.writeCancelPush(cp, 0);
        ByteBuffer ps = ByteBuffer.allocate(H3Writer.pushPromiseLength(0, 3));
        H3Writer.writePushPromise(ps, 0, new byte[] {0, 0, (byte) 0xd1});
        byte[][] frames = new byte[][] {data, headers, pu.array(), pp.array(), cp.array(),
            ps.array(), goaway(1), new byte[] {0x21, 0x00}, new byte[] {0x40, 0x40, 0x00}};
        boolean[] clients = new boolean[] {false, true};
        for (int c = 0; c < clients.length; c++) {
            for (int i = 0; i < frames.length; i++) {
                H3ControlStream s = create(new Listener(), clients[c]);
                feed(s, join(TYPE_CONTROL, settings(), frames[i]));
            }
        }
    }

    @Test
    public void testClientSideGoawayRules() {
        Listener l = new Listener();
        H3ControlStream s = create(l, true);
        ByteBuffer mp = ByteBuffer.allocate(H3Writer.maxPushIdLength(0));
        H3Writer.writeMaxPushId(mp, 0);
        feed(s, join(TYPE_CONTROL, settings(), goaway(8), goaway(12), mp.array()));
        assertTrue(l.events.contains("goaway:8"));
        H3ControlStream s2 = create(new Listener(), true);
        feed(s2, join(TYPE_CONTROL, settings(), goaway(3)));
    }

    @Test
    public void testPushStreams() {
        H3ControlStream client = create(new Listener(), true);
        feed(client, new byte[] {0x01});
        H3ControlStream server = create(new Listener(), false);
        feed(server, new byte[] {0x01, 0x00});
    }

    @Test
    public void testQpackAndUnknownStreams() {
        H3ControlStream enc = create(new Listener(), false);
        feed(enc, new byte[] {0x02});
        feed(enc, new byte[] {0x3f, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f});
        H3ControlStream dec = create(new Listener(), false);
        feed(dec, new byte[] {0x03});
        feed(dec, new byte[] {(byte) 0x80 | 0x20, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f});
        H3ControlStream unknown = create(new Listener(), false);
        feed(unknown, new byte[] {0x21, 1, 2, 3});
        unknown.readFinished();
        unknown.disconnected();
        H3ControlStream control = create(new Listener(), false);
        feed(control, TYPE_CONTROL);
        control.readFinished();
        control.disconnected();
        control.error(new RuntimeException("x"));
    }
}
