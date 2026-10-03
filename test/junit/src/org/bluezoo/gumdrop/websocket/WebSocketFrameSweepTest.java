/*
 * WebSocketFrameSweepTest.java
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

package org.bluezoo.gumdrop.websocket;

import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over {@link WebSocketFrame#parse}: only
 * {@link WebSocketProtocolException} may escape, and every strict prefix of
 * a valid frame must report "incomplete" and leave the buffer untouched.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketFrameSweepTest {

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static TruncationSweep.Target target() {
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                WebSocketFrame.parse(ByteBuffer.wrap(input), 1 << 20);
            }
        };
    }

    private static String repeat(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    @Test
    public void parseOnlyThrowsProtocolException() throws Exception {
        byte[][] frames = {
            bytes(WebSocketFrame.createTextFrame("hello", true).encode()),
            bytes(WebSocketFrame.createTextFrame(repeat(300), true).encode()),
            bytes(WebSocketFrame.createCloseFrame(1000, "bye", false).encode()),
            bytes(WebSocketFrame.createPingFrame(ByteBuffer.wrap(new byte[] {1, 2}), true).encode()),
        };
        for (int i = 0; i < frames.length; i++) {
            List<String> failures = TruncationSweep.allFailures(frames[i], target(),
                    WebSocketProtocolException.class);
            assertTrue("frame " + i + " " + failures, failures.isEmpty());
        }
    }

    @Test
    public void everyStrictPrefixIsIncompleteAndUnconsumed() throws Exception {
        byte[] frame = bytes(WebSocketFrame.createTextFrame(repeat(300), true).encode());
        for (int n = 0; n < frame.length; n++) {
            ByteBuffer buf = ByteBuffer.wrap(frame, 0, n);
            assertNull("prefix " + n, WebSocketFrame.parse(buf, 1 << 20));
            assertEquals("prefix " + n, 0, buf.position());
        }
    }

    @Test
    public void hostileLengthsAreRejected() {
        byte[][] hostile = {
            {(byte) 0x82, 127, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            {(byte) 0x82, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0},
            {(byte) 0x82, 127, 0, 0, 0, 0, (byte) 0x80, 0, 0, 0},
            {(byte) 0x82, 127, 0, 0, 0, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            {(byte) 0x88, 126, 0, 126},
            {(byte) 0x08, 0},
        };
        for (int i = 0; i < hostile.length; i++) {
            List<String> failures = TruncationSweep.inputFailures(target(), hostile[i],
                    "hostile " + i, WebSocketProtocolException.class);
            assertTrue(failures.toString(), failures.isEmpty());
        }
    }
}
