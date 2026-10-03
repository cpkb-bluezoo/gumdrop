/*
 * H3TruncationSweepTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.quic.packet.VarInt;
import org.junit.Test;

/**
 * Truncation sweeps over the HTTP/3 and HTTP Datagram wire structures:
 * every frame payload cut to every shorter length (with a matching
 * declared length), and every prefix of datagram and capsule encodings.
 * Truncated peer input is a frame error, a null decode or underflow,
 * never a runtime exception.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3TruncationSweepTest {

    private static class Sink implements H3FrameHandler {
        final List<String> events = new ArrayList<String>();

        @Override public void dataFrameReceived(ByteBuffer d, boolean e) { events.add("data"); }
        @Override public void headersFrameReceived(ByteBuffer d) { events.add("headers"); }
        @Override public void cancelPushFrameReceived(long id) { events.add("cancel_push"); }
        @Override public void settingsFrameReceived(long[] s) { events.add("settings"); }
        @Override public void pushPromiseFrameReceived(long id, ByteBuffer d) { events.add("push_promise"); }
        @Override public void goawayFrameReceived(long id) { events.add("goaway"); }
        @Override public void maxPushIdFrameReceived(long id) { events.add("max_push_id"); }
        @Override public void priorityUpdateRequestFrameReceived(long id, String v) { events.add("pur"); }
        @Override public void priorityUpdatePushFrameReceived(long id, String v) { events.add("pup"); }
        @Override public void unknownFrameReceived(long type) { events.add("unknown"); }
        @Override public void frameError(String message) { events.add("error"); }
    }

    private static byte[] frame(long type, byte[] payload, int length) {
        ByteBuffer out = ByteBuffer.allocate(16 + length);
        VarInt.encode(type, out);
        VarInt.encode(length, out);
        out.put(payload, 0, length);
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        return bytes;
    }

    private static byte[] varints(long... values) {
        ByteBuffer out = ByteBuffer.allocate(64);
        for (int i = 0; i < values.length; i++) {
            VarInt.encode(values[i], out);
        }
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        return bytes;
    }

    @Test
    public void testEveryTruncatedFramePayloadIsDispatchedOrAFrameError() {
        long[] types = {
            H3FrameHandler.TYPE_CANCEL_PUSH, H3FrameHandler.TYPE_SETTINGS,
            H3FrameHandler.TYPE_PUSH_PROMISE, H3FrameHandler.TYPE_GOAWAY,
            H3FrameHandler.TYPE_MAX_PUSH_ID, H3FrameHandler.TYPE_PRIORITY_UPDATE_REQUEST,
            H3FrameHandler.TYPE_PRIORITY_UPDATE_PUSH
        };
        byte[] payload = varints(1000L, 70000L, 5000000000L);
        for (int t = 0; t < types.length; t++) {
            for (int length = 0; length <= payload.length; length++) {
                Sink sink = new Sink();
                new H3Parser(sink).receive(ByteBuffer.wrap(frame(types[t], payload, length)));
                assertEquals("type " + types[t] + " length " + length, 1, sink.events.size());
            }
        }
    }

    @Test
    public void testEveryPrefixOfAFrameStreamLeavesOnlyUnderflow() {
        byte[] whole = frame(H3FrameHandler.TYPE_SETTINGS, varints(1L, 4096L, 6L, 100000L), 8);
        for (int cut = 0; cut < whole.length; cut++) {
            Sink sink = new Sink();
            byte[] prefix = new byte[cut];
            System.arraycopy(whole, 0, prefix, 0, cut);
            new H3Parser(sink).receive(ByteBuffer.wrap(prefix));
            assertTrue("cut " + cut, sink.events.isEmpty());
        }
    }

    @Test
    public void testEveryPrefixOfAnH3DatagramIsDecodedOrNull() {
        byte[] whole = H3Datagram.encode(4L * 70000L, new byte[] {1, 2, 3});
        assertNotNull(whole);
        for (int cut = 0; cut < whole.length; cut++) {
            byte[] prefix = new byte[cut];
            System.arraycopy(whole, 0, prefix, 0, cut);
            H3Datagram decoded = H3Datagram.decode(ByteBuffer.wrap(prefix));
            if (cut < 3) {
                assertNull("cut " + cut, decoded);
            }
        }
    }

    @Test
    public void testEveryPrefixOfAContextIdDatagramIsDecodedOrNull() {
        ByteBuffer encoded = HttpDatagramContext.encode(70000L, ByteBuffer.wrap(new byte[] {9, 9}));
        byte[] whole = new byte[encoded.remaining()];
        encoded.get(whole);
        for (int cut = 0; cut < 4; cut++) {
            byte[] prefix = new byte[cut];
            System.arraycopy(whole, 0, prefix, 0, cut);
            assertNull("cut " + cut, HttpDatagramContext.decode(ByteBuffer.wrap(prefix)));
        }
    }

    @Test
    public void testEveryPrefixOfACapsuleIsUnderflowUntilComplete() throws Exception {
        byte[] whole = new Capsule(70000L, new byte[300]).encode();
        for (int cut = 0; cut < whole.length; cut++) {
            byte[] prefix = new byte[cut];
            System.arraycopy(whole, 0, prefix, 0, cut);
            CapsuleParser parser = new CapsuleParser();
            List<Capsule> out = parser.push(ByteBuffer.wrap(prefix));
            assertTrue("cut " + cut, out.isEmpty());
        }
        CapsuleParser parser = new CapsuleParser();
        List<Capsule> out = parser.push(ByteBuffer.wrap(whole));
        assertEquals(1, out.size());
    }
}
