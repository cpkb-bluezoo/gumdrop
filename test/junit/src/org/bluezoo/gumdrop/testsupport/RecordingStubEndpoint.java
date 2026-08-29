/*
 * RecordingStubEndpoint.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test {@link Endpoint} that records outbound protocol lines and signals
 * waiters when a matching line arrives. Used instead of polling
 * {@code Thread.sleep} loops over {@link #getResponses()}.
 */
public final class RecordingStubEndpoint implements Endpoint {

    private enum MatchKind {
        STARTS_WITH, CONTAINS, EQUALS
    }

    private static final class LineWaiter {
        final String pattern;
        final MatchKind kind;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String matchedLine;

        LineWaiter(String pattern, MatchKind kind) {
            this.pattern = pattern;
            this.kind = kind;
        }

        boolean matches(String line) {
            switch (kind) {
                case STARTS_WITH:
                    return line.startsWith(pattern);
                case CONTAINS:
                    return line.contains(pattern);
                case EQUALS:
                    return pattern.equals(line);
                default:
                    return false;
            }
        }
    }

    private final int localPort;
    private final Object lock = new Object();
    private final List<String> lines = new ArrayList<String>();
    private final List<LineWaiter> waiters = new ArrayList<LineWaiter>();
    private boolean open = true;
    private boolean secure;

    public RecordingStubEndpoint() {
        this(0);
    }

    public RecordingStubEndpoint(int localPort) {
        this.localPort = localPort;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    @Override
    public void send(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        String payload = new String(bytes, StandardCharsets.US_ASCII);
        synchronized (lock) {
            for (String line : payload.split("\r\n", -1)) {
                if (!line.isEmpty()) {
                    onLine(line);
                }
            }
        }
    }

    private void onLine(String line) {
        lines.add(line);
        Iterator<LineWaiter> i = waiters.iterator();
        while (i.hasNext()) {
            LineWaiter waiter = i.next();
            if (waiter.matches(line)) {
                waiter.matchedLine = line;
                waiter.latch.countDown();
                i.remove();
            }
        }
    }

    /**
     * Clears recorded lines. Call between protocol steps once the
     * previous step's awaited line has been received.
     */
    public void clearResponses() {
        synchronized (lock) {
            lines.clear();
        }
    }

    public List<String> getResponses() {
        synchronized (lock) {
            return new ArrayList<String>(lines);
        }
    }

    public String findLineStartingWith(String prefix) {
        synchronized (lock) {
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }
        return null;
    }

    public String findLineContaining(String fragment) {
        synchronized (lock) {
            for (String line : lines) {
                if (line.contains(fragment)) {
                    return line;
                }
            }
        }
        return null;
    }

    public String awaitLineStartingWith(String prefix) throws InterruptedException {
        return awaitLine(prefix, MatchKind.STARTS_WITH);
    }

    public String awaitLineContaining(String fragment) throws InterruptedException {
        return awaitLine(fragment, MatchKind.CONTAINS);
    }

    public String awaitLineEquals(String exact) throws InterruptedException {
        return awaitLine(exact, MatchKind.EQUALS);
    }

    private String awaitLine(String pattern, MatchKind kind)
            throws InterruptedException {
        LineWaiter waiter;
        synchronized (lock) {
            for (String line : lines) {
                if (matches(line, pattern, kind)) {
                    return line;
                }
            }
            waiter = new LineWaiter(pattern, kind);
            waiters.add(waiter);
        }
        waiter.latch.await();
        if (waiter.matchedLine != null) {
            return waiter.matchedLine;
        }
        throw new AssertionError("line waiter released without a match");
    }

    private static boolean matches(String line, String pattern, MatchKind kind) {
        switch (kind) {
            case STARTS_WITH:
                return line.startsWith(pattern);
            case CONTAINS:
                return line.contains(pattern);
            case EQUALS:
                return pattern.equals(line);
            default:
                return false;
        }
    }

    @Override public boolean isOpen() { return open; }
    @Override public boolean isClosing() { return false; }
    @Override public void close() { open = false; }
    @Override public SocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", localPort);
    }
    @Override public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 54321);
    }
    @Override public boolean isSecure() { return secure; }
    @Override public SecurityInfo getSecurityInfo() { return null; }
    @Override public void startTLS() { }
    @Override public SelectorLoop getSelectorLoop() { return null; }
    @Override public void execute(Runnable task) { task.run(); }
    @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
        return new TimerHandle() {
            @Override public void cancel() { }
            @Override public boolean isCancelled() { return false; }
        };
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
