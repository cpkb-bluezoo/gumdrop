/*
 * BinaryRecordingEndpoint.java
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

package org.bluezoo.gumdrop.testsupport;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test {@link Endpoint} that records outbound writes as raw byte arrays,
 * for binary protocols (MQTT, WebSocket, gRPC framing) where the
 * line-oriented {@link RecordingStubEndpoint} is unsuitable. Timers are
 * captured rather than run, so tests fire them explicitly with
 * {@link #fireTimers()}. All callbacks run inline on the calling thread.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class BinaryRecordingEndpoint implements Endpoint {

    /** A timer scheduled through {@link #scheduleTimer}. */
    public static final class StubTimer implements TimerHandle {
        private final Runnable callback;
        private final long delayMs;
        private boolean cancelled;

        StubTimer(long delayMs, Runnable callback) {
            this.delayMs = delayMs;
            this.callback = callback;
        }

        public long getDelayMs() {
            return delayMs;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final List<byte[]> writes = new ArrayList<byte[]>();
    private final List<StubTimer> timers = new ArrayList<StubTimer>();
    private boolean open = true;
    private boolean secure;
    private int closeCount;
    private boolean startTlsCalled;

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /** Returns each write as a separate array, in send order. */
    public List<byte[]> getWrites() {
        return new ArrayList<byte[]>(writes);
    }

    /** Returns all writes concatenated. */
    public byte[] getAllBytes() {
        int total = 0;
        for (byte[] w : writes) {
            total += w.length;
        }
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] w : writes) {
            System.arraycopy(w, 0, all, pos, w.length);
            pos += w.length;
        }
        return all;
    }

    public void clearWrites() {
        writes.clear();
    }

    public int getCloseCount() {
        return closeCount;
    }

    public boolean isStartTlsCalled() {
        return startTlsCalled;
    }

    public List<StubTimer> getTimers() {
        return new ArrayList<StubTimer>(timers);
    }

    /** Runs every timer that has not been cancelled. */
    public void fireTimers() {
        List<StubTimer> snapshot = new ArrayList<StubTimer>(timers);
        for (StubTimer t : snapshot) {
            if (!t.cancelled) {
                t.callback.run();
            }
        }
    }

    @Override
    public void send(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        writes.add(bytes);
    }

    @Override public boolean isOpen() { return open; }
    @Override public boolean isClosing() { return false; }
    @Override public void close() { open = false; closeCount++; }
    @Override public SocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }
    @Override public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 54321);
    }
    @Override public boolean isSecure() { return secure; }
    @Override public SecurityInfo getSecurityInfo() { return null; }
    @Override public void startTLS() { startTlsCalled = true; }
    @Override public SelectorLoop getSelectorLoop() { return null; }
    @Override public void execute(Runnable task) { task.run(); }
    @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
        StubTimer t = new StubTimer(delayMs, cb);
        timers.add(t);
        return t;
    }
    @Override public Trace getTrace() { return null; }
    @Override public void setTrace(Trace trace) { }
    @Override public boolean isTelemetryEnabled() { return false; }
    @Override public TelemetryConfig getTelemetryConfig() { return null; }
    @Override public void pauseRead() { }
    @Override public void resumeRead() { }
    @Override public void onWriteReady(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }
}
