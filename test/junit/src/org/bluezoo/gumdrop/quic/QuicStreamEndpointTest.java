/*
 * QuicStreamEndpointTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.junit.Test;

/**
 * Receive-side behaviour of {@link QuicStreamEndpoint}: stream bytes must
 * reach the handler in order and exactly once, whether or not the handler
 * has paused reading or left bytes unconsumed
 * ({@link ProtocolHandler#receive}).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicStreamEndpointTest {

    /** Consumes {@code unit} bytes at a time and records everything taken. */
    private static final class UnitHandler implements ProtocolHandler {
        final int unit;
        final ByteArrayOutputStream taken = new ByteArrayOutputStream();
        int receives;
        int finishes;

        UnitHandler(int unit) {
            this.unit = unit;
        }

        @Override
        public void receive(ByteBuffer data) {
            receives++;
            while (data.remaining() >= unit) {
                byte[] b = new byte[unit];
                data.get(b);
                taken.write(b, 0, unit);
            }
        }

        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void readFinished() { finishes++; }
        @Override public void disconnected() { }
        @Override public void error(Exception cause) { }

        String text() {
            return new String(taken.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void dataArrivingWhilePausedIsDeliveredOnResume() {
        UnitHandler h = new UnitHandler(1);
        QuicStreamEndpoint ep = new QuicStreamEndpoint(null, 4L, h);
        ep.deliverData(buf("abc"));
        ep.pauseRead();
        ep.deliverData(buf("def"));
        ep.deliverData(buf("ghi"));
        assertEquals("nothing is delivered while paused", "abc", h.text());
        ep.resumeRead();
        assertEquals("abcdefghi", h.text());
        ep.deliverData(buf("jkl"));
        assertEquals("abcdefghijkl", h.text());
    }

    @Test
    public void unconsumedBytesArePreservedForTheNextRead() {
        int[] chunks = {1, 3, 7, 10, 13, 99};
        for (int c = 0; c < chunks.length; c++) {
            UnitHandler h = new UnitHandler(10);
            QuicStreamEndpoint ep = new QuicStreamEndpoint(null, 4L, h);
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                all.append("0123456789".replace('0', (char) ('a' + i)));
            }
            String s = all.toString();
            for (int pos = 0; pos < s.length(); pos += chunks[c]) {
                ep.deliverData(buf(s.substring(pos, Math.min(s.length(), pos + chunks[c]))));
            }
            assertEquals("chunk size " + chunks[c], s, h.text());
        }
    }

    @Test
    public void pausingInsideReceiveHoldsBackTheRest() {
        final QuicStreamEndpoint[] holder = new QuicStreamEndpoint[1];
        final UnitHandler inner = new UnitHandler(1);
        ProtocolHandler pausing = new ProtocolHandler() {
            boolean first = true;
            @Override public void receive(ByteBuffer data) {
                if (first) {
                    first = false;
                    holder[0].pauseRead();
                }
                inner.receive(data);
            }
            @Override public void connected(Endpoint endpoint) { }
            @Override public void securityEstablished(SecurityInfo info) { }
            @Override public void disconnected() { }
            @Override public void error(Exception cause) { }
        };
        QuicStreamEndpoint ep = new QuicStreamEndpoint(null, 4L, pausing);
        holder[0] = ep;
        ep.deliverData(buf("abc"));
        ep.deliverData(buf("def"));
        assertEquals("abc", inner.text());
        ep.resumeRead();
        assertEquals("abcdef", inner.text());
        assertTrue(inner.receives >= 2);
    }

    @Test
    public void finishRunsImmediatelyWhenNothingIsHeldBack() {
        UnitHandler h = new UnitHandler(1);
        QuicStreamEndpoint ep = new QuicStreamEndpoint(null, 4L, h);
        final int[] ran = new int[1];
        ep.deliverData(buf("abc"));
        ep.afterDelivery(new Runnable() {
            @Override public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, ran[0]);
        assertEquals("abc", h.text());
    }

    @Test
    public void finishDoesNotOvertakeDataHeldWhilePaused() {
        final UnitHandler h = new UnitHandler(1);
        QuicStreamEndpoint ep = new QuicStreamEndpoint(null, 4L, h);
        final String[] textAtFinish = new String[1];
        ep.pauseRead();
        ep.deliverData(buf("held"));
        ep.afterDelivery(new Runnable() {
            @Override public void run() {
                textAtFinish[0] = h.text();
            }
        });
        assertEquals("not finished while paused", null, textAtFinish[0]);
        ep.resumeRead();
        assertEquals("all data delivered before the finish", "held", textAtFinish[0]);
    }
}
