/*
 * MdnsListenerLifecycleTest.java
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

package org.bluezoo.gumdrop.mdns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.mdns.server.MdnsServer;
import org.bluezoo.gumdrop.testsupport.RecordingSelectorLoop;
import org.bluezoo.gumdrop.testsupport.StubDatagramChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

/**
 * Lifecycle of {@link MdnsListener} over an in-memory datagram channel:
 * bind, goodbye on the loop at stop, send paths, timers and datagram
 * dispatch. The channel opener seam replaces the multicast socket, a
 * {@link RecordingSelectorLoop} stands in for the endpoint's loop and the
 * test runs the recorded loop tasks by hand, so nothing touches the network
 * or a thread. The real multicast join stays in the integration test.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MdnsListenerLifecycleTest {

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 5353);

    /** Listener whose channel is an in-memory stub. */
    private static final class StubListener extends MdnsListener {
        final StubDatagramChannel channel = new StubDatagramChannel(LOCAL);
        boolean failOpen;

        @Override
        DatagramChannel openChannel() throws IOException {
            if (failOpen) {
                throw new IOException("bind refused");
            }
            return channel;
        }
    }

    /** Server that records its callbacks. */
    private static final class RecordingServer extends MdnsServer {
        int goodbyes;
        final List<byte[]> received = new ArrayList<byte[]>();
        InetSocketAddress lastSource;

        @Override
        public void sendGoodbye(MdnsListener origin) {
            goodbyes++;
        }

        @Override
        public void handleDatagram(MdnsListener origin, ByteBuffer data,
                InetSocketAddress source) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            received.add(copy);
            lastSource = source;
        }
    }

    private final RecordingSelectorLoop loop = new RecordingSelectorLoop();
    private final Gumdrop gumdrop = Gumdrop.embedded(loop, new TestGumdrop.DirectExecutor());

    private StubListener started(RecordingServer server) {
        StubListener l = new StubListener();
        if (server != null) {
            l.setServer(server);
        }
        l.start(gumdrop);
        return l;
    }

    @Test
    public void startBindsAndExposesGroup() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        assertTrue(l.isBound());
        assertSame(server, l.getServer());
        InetSocketAddress group = l.getGroupAddress();
        assertEquals("224.0.0.251", group.getAddress().getHostAddress());
        assertNotNull(l.getDatagramHandler());
    }

    @Test
    public void bindFailureIsSwallowedAndLeavesListenerUnbound() {
        StubListener l = new StubListener();
        l.failOpen = true;
        l.start(gumdrop);
        assertFalse(l.isBound());
        assertNull(l.getDatagramHandler());
    }

    @Test
    public void stopHandsGoodbyeAndCloseToTheLoop() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        l.stop();
        assertEquals("nothing runs on the caller's thread", 0, server.goodbyes);
        assertTrue(l.isBound());
        assertEquals(1, loop.recordedCount());
        loop.runRecorded();
        assertEquals(1, server.goodbyes);
        assertFalse(l.isBound());
    }

    @Test
    public void stopWithoutServerJustCloses() {
        StubListener l = started(null);
        l.stop();
        loop.runRecorded();
        assertFalse(l.isBound());
    }

    @Test
    public void stopTwiceAnnouncesGoodbyeOnlyOnce() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        l.stop();
        loop.runRecorded();
        l.stop();
        loop.runRecorded();
        assertEquals(1, server.goodbyes);
    }

    @Test
    public void stopWhileTaskPendingStillAnnouncesOnce() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        l.stop();
        l.stop();
        loop.runRecorded();
        assertEquals(1, server.goodbyes);
        assertFalse(l.isBound());
    }

    @Test
    public void beginShutdownThenStopAnnouncesOnceOnTheLoop() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        l.beginShutdown();
        assertEquals(0, server.goodbyes);
        assertEquals(1, loop.recordedCount());
        l.beginShutdown();
        assertEquals("second beginShutdown is a no-op", 1, loop.recordedCount());
        loop.runRecorded();
        assertEquals(1, server.goodbyes);
        l.stop();
        loop.runRecorded();
        assertEquals(1, server.goodbyes);
        assertFalse(l.isBound());
    }

    @Test
    public void beginShutdownBeforeStartIsNoOp() {
        RecordingServer server = new RecordingServer();
        MdnsListener l = new MdnsListener();
        l.setServer(server);
        l.beginShutdown();
        assertEquals(0, server.goodbyes);
    }

    @Test
    public void stopNeverStartedWithAndWithoutServer() {
        MdnsListener bare = new MdnsListener();
        bare.stop();
        assertFalse(bare.isBound());
        RecordingServer server = new RecordingServer();
        MdnsListener wired = new MdnsListener();
        wired.setServer(server);
        wired.stop();
        assertEquals(1, server.goodbyes);
        wired.stop();
        assertEquals("goodbye is announced once", 1, server.goodbyes);
    }

    @Test
    public void stopAfterEndpointClosedByLoopSendsNothing() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        l.getEndpoint().closeForShutdown(false);
        l.stop();
        assertEquals(0, loop.recordedCount());
        assertEquals(0, server.goodbyes);
        assertFalse(l.isBound());
    }

    @Test
    public void datagramIsDispatchedToServer() {
        RecordingServer server = new RecordingServer();
        StubListener l = started(server);
        byte[] payload = new byte[] {1, 2, 3, 4};
        l.getDatagramHandler().receive(ByteBuffer.wrap(payload));
        assertEquals(1, server.received.size());
        assertEquals(4, server.received.get(0).length);
        assertEquals(3, server.received.get(0)[2]);
    }

    @Test
    public void datagramWithoutServerIsDropped() {
        StubListener l = started(null);
        byte[] payload = new byte[] {1};
        ByteBuffer buf = ByteBuffer.wrap(payload);
        l.getDatagramHandler().receive(buf);
        assertEquals(1, buf.remaining());
    }

    @Test
    public void handlerCallbacksAreSafe() {
        StubListener l = started(new RecordingServer());
        l.getDatagramHandler().disconnected();
        l.getDatagramHandler().securityEstablished(null);
        l.getDatagramHandler().error(new IOException("boom"));
        assertTrue(l.isBound());
    }

    @Test
    public void sendToAndSendToGroupQueueDatagrams() {
        StubListener l = started(new RecordingServer());
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 6000);
        l.sendTo(ByteBuffer.wrap(new byte[] {9, 8, 7}), dest);
        l.sendToGroup(ByteBuffer.wrap(new byte[] {0}));
        l.getEndpoint().closeForShutdown(true);
        List<StubDatagramChannel.Sent> sent = l.channel.getSent();
        assertEquals(2, sent.size());
        assertEquals(3, sent.get(0).getBytes().length);
        assertEquals(dest, sent.get(0).getDestination());
        assertEquals(l.getGroupAddress(), sent.get(1).getDestination());
    }

    @Test
    public void scheduleTimerCanBeCancelled() {
        StubListener l = started(new RecordingServer());
        Runnable cb = new Runnable() {
            @Override
            public void run() {
            }
        };
        MdnsListener.TimerHandleWrapper h = l.scheduleTimer(60000, cb);
        assertNotNull(h);
        h.cancel();
    }
}
