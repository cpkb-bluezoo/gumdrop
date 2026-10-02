/*
 * SocksBindRelayTest.java
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

package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;

/**
 * Tests for {@link SocksBindRelay} acceptance validation, timeout and close
 * handling. A loopback socket pair supplies the accepted channel.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksBindRelayTest {

    /** Endpoint that captures scheduled timers. */
    private static final class TimerEndpoint extends StubEndpoint {
        final List<Runnable> timers = new ArrayList<Runnable>();

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            return super.scheduleTimer(delayMs, callback);
        }
    }

    /** Callback recording the outcome. */
    private static final class Recorder implements SocksBindRelay.Callback {
        int accepted;
        int failed;
        byte lastCode;
        InetSocketAddress peer;

        @Override
        public void bindAccepted(SocketChannel sc,
                                 InetSocketAddress peerAddress) {
            accepted++;
            peer = peerAddress;
            try {
                sc.close();
            } catch (IOException e) {
                // ignore
            }
        }

        @Override
        public void bindFailed(byte replyCode) {
            failed++;
            lastCode = replyCode;
        }
    }

    private SocksServer server;
    private TimerEndpoint endpoint;
    private Recorder recorder;
    private ServerSocketChannel listen;
    private SocketChannel clientSide;

    @Before
    public void setUp() throws IOException {
        server = new SocksServer();
        server.acquireRelay();
        endpoint = new TimerEndpoint();
        recorder = new Recorder();
        listen = ServerSocketChannel.open();
        listen.bind(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0));
    }

    @After
    public void tearDown() throws IOException {
        if (clientSide != null) {
            clientSide.close();
        }
        listen.close();
    }

    private SocketChannel connectPair() throws IOException {
        clientSide = SocketChannel.open(listen.getLocalAddress());
        return listen.accept();
    }

    @Test
    public void acceptedPeerWithNoExpectation() throws IOException {
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                null, recorder);
        relay.accepted(connectPair());
        assertEquals(1, recorder.accepted);
        assertEquals(0, recorder.failed);
        assertNotNull(recorder.peer);
    }

    @Test
    public void acceptedPeerMatchingExpectation() throws IOException {
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                InetAddress.getLoopbackAddress(), recorder);
        relay.accepted(connectPair());
        assertEquals(1, recorder.accepted);
    }

    @Test
    public void peerMismatchIsRejected() throws IOException {
        InetAddress other = InetAddress.getByAddress(
                new byte[]{(byte) 192, 0, 2, 7});
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                other, recorder);
        relay.accepted(connectPair());
        assertEquals(0, recorder.accepted);
        assertEquals(1, recorder.failed);
        assertEquals(SocksConstants.SOCKS5_REPLY_NOT_ALLOWED,
                recorder.lastCode);
    }

    @Test
    public void blockedPeerIsRejected() throws IOException {
        server.setBlockedDestinations(
                CidrNetwork.parseList("127.0.0.0/8, ::1/128"));
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                null, recorder);
        relay.accepted(connectPair());
        assertEquals(0, recorder.accepted);
        assertEquals(1, recorder.failed);
        assertEquals(SocksConstants.SOCKS5_REPLY_NOT_ALLOWED,
                recorder.lastCode);
    }

    @Test
    public void acceptAfterCloseDropsConnection() throws IOException {
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                null, recorder);
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
        relay.accepted(connectPair());
        assertEquals(0, recorder.accepted);
        assertEquals(0, recorder.failed);
    }

    @Test
    public void closeIsIdempotent() {
        SocksBindRelay relay = new SocksBindRelay(endpoint, server, 0L,
                null, recorder);
        relay.close();
        relay.close();
        assertEquals(0, server.getActiveRelayCount());
    }
}
