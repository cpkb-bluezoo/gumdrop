/*
 * H3TrailersWireTest.java
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

import java.util.List;
import org.bluezoo.gumdrop.http.HeaderFields;
import java.util.ArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

/**
 * The frames an HTTP/3 request's trailers become on the wire: a final
 * HEADERS frame, after which the stream is closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3TrailersWireTest {

    private static final class Recording implements Endpoint {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean open = true;

        @Override public void send(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            sent.write(b, 0, b.length);
        }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public TelemetryConfig getTelemetryConfig() { return org.bluezoo.gumdrop.testsupport.StubTelemetry.CONFIG; }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    public void trailersAreAFinalHeadersFrameAndThenTheStreamCloses() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3ClientStream stream = new H3ClientStream(h, new Decoder(4096), new H3ClientFlowTest.Rec());
        Recording wire = new Recording();
        set(stream, "endpoint", wire);
        set(stream, "streamId", Long.valueOf(0L));

        List<Header> trailers = new ArrayList<Header>();
        trailers.add(new Header("X-Checksum", "99"));
        trailers.add(new Header("connection", "close"));   // not allowed in HTTP/3: dropped
        h.sendRequestTrailers(stream, trailers);

        byte[] frame = wire.sent.toByteArray();
        assertTrue("a frame was written", frame.length > 2);
        assertEquals("a HEADERS frame (type 0x01)", 0x01, frame[0]);
        assertEquals("its length covers the rest", frame.length - 2, frame[1] & 0xFF);
        assertFalse("and the stream is closed after it", wire.open);
    }

    @Test
    public void trailersWaitForTheStreamAndAreSentWhenItIsReady() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3ClientStream stream = new H3ClientStream(h, new Decoder(4096), new H3ClientFlowTest.Rec());
        List<Header> trailers = new ArrayList<Header>();
        trailers.add(new Header("x-checksum", "99"));
        h.sendRequestTrailers(stream, trailers);   // no endpoint yet: queued
        List<Header> queued = stream.takePendingTrailers();
        assertEquals("99", HeaderFields.getValue(queued, "x-checksum"));
    }
}
