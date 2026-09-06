package org.bluezoo.gumdrop.socks.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Minimal stub for {@link Endpoint} used in SOCKS client unit tests.
 * Records sent data and allows inspection of what the protocol
 * handler wrote.
 */
class StubEndpoint implements Endpoint {

    private boolean open = true;
    private boolean closing;
    private final List<byte[]> sentBuffers = new ArrayList<>();
    private SocketAddress localAddress =
            new InetSocketAddress("127.0.0.1", 54321);
    private SocketAddress remoteAddress =
            new InetSocketAddress("192.168.1.1", 1080);

    @Override
    public void send(ByteBuffer data) {
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        sentBuffers.add(copy);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isClosing() {
        return closing;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        return null;
    }

    @Override
    public void startTLS() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void pauseRead() {
    }

    @Override
    public void resumeRead() {
    }

    @Override
    public void onWriteReady(Runnable callback) {
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return null;
    }

    @Override
    public void execute(Runnable task) {
        task.run();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return new StubTimerHandle();
    }

    @Override
    public Trace getTrace() {
        return null;
    }

    @Override
    public void setTrace(Trace trace) {
    }

    @Override
    public boolean isTelemetryEnabled() {
        return false;
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return null;
    }

    // ── Test inspection methods ──

    List<byte[]> getSentBuffers() {
        return sentBuffers;
    }

    byte[] getLastSent() {
        return sentBuffers.isEmpty() ? null
                : sentBuffers.get(sentBuffers.size() - 1);
    }

    int getSentCount() {
        return sentBuffers.size();
    }

    void clearSent() {
        sentBuffers.clear();
    }

    void setOpen(boolean open) {
        this.open = open;
    }

    static class StubTimerHandle implements TimerHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
