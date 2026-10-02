/*
 * TcpEndpointChannelTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link TcpEndpoint} holding a channel, with no network behind it: address
 * reporting, error and close handling, admission accounting, the
 * establishment timeouts (fired by hand), hand-off and shutdown. The loop is
 * inline and its timer records instead of waiting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointChannelTest {

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 4000);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 4001);

    /**
     * The loop's own timer, whose thread is never started: what a test arms
     * stays queued, and the test fires it by hand.
     */
    private static final class Fire {
        private final ScheduledTimer timer;

        Fire(ScheduledTimer timer) {
            this.timer = timer;
        }

        List<ScheduledTimer.TimerEntry> entries() {
            List<ScheduledTimer.TimerEntry> all = timer.pendingEntries();
            java.util.Collections.sort(all);
            return all;
        }

        void fire(int index) {
            ScheduledTimer.TimerEntry e = entries().get(index);
            if (!e.isCancelled()) {
                e.callback.run();
            }
        }
    }

    /** Runs tasks inline and hands out the recording timer. */
    private static final class ManualLoop extends SelectorLoop {
        final Fire timer = new Fire(getTimer());
        int writeRequests;

        ManualLoop() {
            super(0);
        }

        @Override
        public boolean tryInvokeLater(Runnable task) {
            task.run();
            return true;
        }

        @Override
        void requestWrite(TcpEndpoint endpoint) {
            writeRequests++;
        }
    }

    private static final class Recorder implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        int received;
        boolean failDisconnect;
        int consumeAtMost;

        @Override
        public void receive(ByteBuffer data) {
            received += data.remaining();
            if (consumeAtMost > 0 && data.remaining() > consumeAtMost) {
                data.position(data.position() + consumeAtMost);
            } else {
                data.position(data.limit());
            }
        }

        @Override
        public void connected(Endpoint endpoint) {
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            events.add("secure");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
            if (failDisconnect) {
                throw new IllegalStateException("handler failure");
            }
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final class Counting extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "counting";
        }
    }

    private ManualLoop loop;
    private StubSocketChannel channel;
    private Recorder handler;
    private TcpEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        loop = new ManualLoop();
        channel = new StubSocketChannel(LOCAL, REMOTE);
        handler = new Recorder();
        endpoint = new TcpEndpoint(handler);
        endpoint.setChannel(channel);
        endpoint.setSelectorLoop(loop);
        endpoint.init();
    }

    @Test
    public void addressesAndOpenStateComeFromTheChannel() throws IOException {
        assertEquals(LOCAL, endpoint.getLocalAddress());
        assertEquals(REMOTE, endpoint.getRemoteAddress());
        assertTrue(endpoint.isOpen());
        endpoint.close();
        assertFalse(endpoint.isOpen());
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void initToleratesChannelsWithoutASocketView() throws IOException {
        StubSocketChannel unix = new StubSocketChannel(LOCAL, REMOTE);
        unix.setSocketUnsupported(true);
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setChannel(unix);
        ep.setSelectorLoop(loop);
        ep.init();
        assertTrue(ep.isOpen());
        ep.send(ByteBuffer.wrap(new byte[] {1, 2}));
        assertTrue(ep.hasPendingWrite());
        assertTrue(loop.writeRequests > 0);
    }

    @Test
    public void sendOnAClosedChannelIsDropped() throws IOException {
        channel.close();
        endpoint.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertFalse(endpoint.hasPendingWrite());
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void readErrorsAreClassifiedAndCloseTheEndpoint() {
        endpoint.handleReadError(new IOException("Connection reset by peer"));
        assertEquals(1, handler.errors.size());
        assertTrue(handler.events.contains("disconnected"));
        assertEquals(1, channel.getCloseCount());

        StubSocketChannel c2 = new StubSocketChannel(LOCAL, REMOTE);
        Recorder h2 = new Recorder();
        TcpEndpoint ep2 = new TcpEndpoint(h2);
        ep2.setChannel(c2);
        ep2.handleReadError(new IOException("disk on fire"));
        assertEquals(1, h2.errors.size());
        assertEquals(1, c2.getCloseCount());
    }

    @Test
    public void writeErrorsAreClassifiedAndCloseTheEndpoint() {
        endpoint.handleWriteError(new IOException("Broken pipe"));
        assertEquals(1, handler.errors.size());
        assertEquals(1, channel.getCloseCount());

        StubSocketChannel c2 = new StubSocketChannel(LOCAL, REMOTE);
        Recorder h2 = new Recorder();
        TcpEndpoint ep2 = new TcpEndpoint(h2);
        ep2.setChannel(c2);
        ep2.handleWriteError(new IOException("quota exceeded"));
        assertEquals(1, h2.errors.size());
        assertEquals(1, c2.getCloseCount());
    }

    @Test
    public void connectAndDispatchErrorsReachTheHandler() {
        endpoint.handleConnectError(new IOException("refused"));
        assertEquals(1, handler.errors.size());
        assertEquals(1, channel.getCloseCount());

        StubSocketChannel c2 = new StubSocketChannel(LOCAL, REMOTE);
        Recorder h2 = new Recorder();
        TcpEndpoint ep2 = new TcpEndpoint(h2);
        ep2.setChannel(c2);
        ep2.handleDispatchError(new IllegalStateException("boom"));
        assertEquals(1, h2.errors.size());
        assertTrue(h2.errors.get(0) instanceof IllegalStateException);
        assertEquals(1, c2.getCloseCount());
    }

    @Test
    public void closeFailureAndHandlerFailureDoNotPreventCleanup() {
        channel.setFailOnClose(true);
        handler.failDisconnect = true;
        endpoint.doClose();
        assertEquals(1, channel.getCloseCount());
        assertTrue(handler.events.contains("disconnected"));
        endpoint.doClose();
        assertEquals("the disconnect is delivered once", 1, countOf(handler.events, "disconnected"));
    }

    private static int countOf(List<String> events, String what) {
        int n = 0;
        for (int i = 0; i < events.size(); i++) {
            if (what.equals(events.get(i))) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void admissionIsReleasedExactlyOnce() throws IOException {
        Counting listener = new Counting();
        listener.connectionOpened(REMOTE);
        assertEquals(1, listener.getActiveConnectionCount());
        endpoint.setListener(listener, REMOTE);
        endpoint.doClose();
        assertEquals(0, listener.getActiveConnectionCount());
        endpoint.doClose();
        assertEquals(0, listener.getActiveConnectionCount());
    }

    @Test
    public void silentPlaintextConnectionIsClosedByTheFirstByteTimeout() throws IOException {
        Counting listener = new Counting();
        listener.setReadTimeoutMs(5000L);
        endpoint.setListener(listener, REMOTE);
        endpoint.connected();
        assertEquals("connected", handler.events.get(0));
        assertEquals(1, loop.timer.entries().size());
        assertTrue(loop.timer.entries().get(0).fireTime > 0L);
        loop.timer.fire(0);
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void firstInboundBytesReleaseTheFirstByteTimeout() throws IOException {
        Counting listener = new Counting();
        listener.setReadTimeoutMs(5000L);
        endpoint.setListener(listener, REMOTE);
        endpoint.connected();
        ByteBuffer in = endpoint.prepareNetInForRead();
        in.put(new byte[] {1, 2, 3});
        in.flip();
        endpoint.processInbound();
        assertEquals(3, handler.received);
        assertTrue(loop.timer.entries().get(0).isCancelled());
        loop.timer.fire(0);
        assertFalse(endpoint.isClosing());
    }

    @Test
    public void timeoutAfterCloseDoesNothingMore() throws IOException {
        Counting listener = new Counting();
        listener.setReadTimeoutMs(5000L);
        endpoint.setListener(listener, REMOTE);
        endpoint.connected();
        endpoint.close();
        endpoint.closeRequested = true;
        loop.timer.fire(0);
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void noTimeoutsAreArmedWithoutAListenerOrWithZeroTimeouts() throws IOException {
        endpoint.connected();
        assertTrue(loop.timer.entries().isEmpty());
        Counting listener = new Counting();
        listener.setReadTimeoutMs(0L);
        listener.setConnectionTimeoutMs(0L);
        endpoint.setListener(listener, REMOTE);
        endpoint.connected();
        assertTrue(loop.timer.entries().isEmpty());
    }

    @Test
    public void stalledStartTlsHandshakeIsClosedByTheHandshakeTimeout() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.SERVER);
        Recorder h = new Recorder();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, null, TlsVersion.TLS_1_3, false);
        StubSocketChannel c = new StubSocketChannel(LOCAL, REMOTE);
        ep.setChannel(c);
        ep.setSelectorLoop(loop);
        ep.init();
        Counting listener = new Counting();
        listener.setConnectionTimeoutMs(7000L);
        ep.setListener(listener, REMOTE);
        ep.startTLS();
        assertEquals(1, loop.timer.entries().size());
        assertTrue(loop.timer.entries().get(0).fireTime > 0L);
        loop.timer.fire(0);
        assertTrue(ep.isClosing());
    }

    @Test
    public void handoffReleasesTheChannelWithoutClosingIt() throws IOException {
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1);
        endpoint.setSelectionKey(key);
        Counting listener = new Counting();
        listener.setReadTimeoutMs(5000L);
        endpoint.setListener(listener, REMOTE);
        endpoint.connected();
        assertSame(channel, endpoint.takeSocketChannelForHandoff());
        assertFalse(key.isValid());
        assertTrue(endpoint.isClosing());
        assertEquals(0, channel.getCloseCount());
        assertNull(endpoint.takeSocketChannelForHandoff());
        assertTrue(loop.timer.entries().get(0).isCancelled());
    }

    @Test
    public void clientModeHandoffWithoutARuntimeStillReleases() throws IOException {
        endpoint.setClientMode(true);
        assertSame(channel, endpoint.takeSocketChannelForHandoff());
        endpoint.setClientMode(true);
    }

    @Test
    public void shutdownWithoutAKeyClosesAtOnce() {
        endpoint.closeForShutdown(true);
        assertEquals(1, channel.getCloseCount());
        assertTrue(handler.events.contains("disconnected"));
    }

    @Test
    public void abortShutdownClosesEvenWithAKey() {
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1);
        endpoint.setSelectionKey(key);
        endpoint.closeForShutdown(false);
        assertEquals(1, channel.getCloseCount());
        assertFalse(key.isValid());
    }

    @Test
    public void orderlyShutdownStopsReadingAndFlushesBeforeClosing() {
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1 | 4);
        endpoint.setSelectionKey(key);
        endpoint.send(ByteBuffer.wrap(new byte[] {9, 9}));
        endpoint.closeForShutdown(true);
        assertEquals("OP_READ removed", 4, key.interestOps());
        assertTrue(endpoint.isClosing());
        assertTrue(endpoint.hasPendingWrite());
        assertEquals("not closed until its output drains", 0, channel.getCloseCount());
        endpoint.closeForShutdown(true);
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void orderlyShutdownOfAnUnconnectedChannelAborts() {
        channel.setConnected(false);
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1);
        endpoint.setSelectionKey(key);
        endpoint.closeForShutdown(true);
        assertEquals(1, channel.getCloseCount());
    }

    @Test
    public void orderlyShutdownSurvivesAKeyCancelledUnderIt() {
        StubSocketChannel.Key key = new StubSocketChannel.Key(channel, 1);
        key.setFailOnInterest(true);
        endpoint.setSelectionKey(key);
        endpoint.closeForShutdown(true);
        assertTrue(endpoint.isClosing());
    }

    @Test
    public void unconsumedPlaintextStaysBufferedForTheNextRead() throws IOException {
        Recorder h = new Recorder();
        h.consumeAtMost = 2;
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setChannel(new StubSocketChannel(LOCAL, REMOTE));
        ep.setSelectorLoop(loop);
        ep.init();
        ByteBuffer in = ep.prepareNetInForRead();
        in.put(new byte[] {1, 2, 3, 4, 5});
        in.flip();
        ep.processInbound();
        assertEquals(5, h.received);
        ByteBuffer again = ep.prepareNetInForRead();
        again.put(new byte[] {6});
        again.flip();
        ep.processInbound();
        assertEquals(5 + 4, h.received);
    }
}
