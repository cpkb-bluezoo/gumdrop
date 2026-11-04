/*
 * TcpEndpointProxyTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link TcpEndpoint} behind a PROXY protocol listener: the header is
 * consumed before the handler is connected (and before TLS), the asserted
 * addresses replace the socket's, and anything malformed or late closes the
 * connection without the handler hearing of it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointProxyTest {

    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 4000);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 4001);
    private static final long HEADER_TIMEOUT_MS = 5000;

    private static final class ManualLoop extends SelectorLoop {
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
        }

        List<ScheduledTimer.TimerEntry> pending() {
            List<ScheduledTimer.TimerEntry> all = getTimer().pendingEntries();
            Collections.sort(all);
            List<ScheduledTimer.TimerEntry> live = new ArrayList<ScheduledTimer.TimerEntry>();
            for (ScheduledTimer.TimerEntry e : all) {
                if (!e.isCancelled()) {
                    live.add(e);
                }
            }
            return live;
        }
    }

    private static final class Recorder implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        Endpoint endpoint;
        SocketAddress seenRemoteAtConnect;

        @Override
        public void receive(ByteBuffer data) {
            while (data.hasRemaining()) {
                received.write(data.get());
            }
        }

        @Override
        public void connected(Endpoint ep) {
            events.add("connected");
            endpoint = ep;
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            events.add("secure");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void error(Exception cause) {
            events.add("error");
        }

        String text() {
            return new String(received.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private ManualLoop loop;
    private StubSocketChannel channel;
    private Recorder handler;
    private TcpEndpoint endpoint;

    @Before
    public void setUp() throws IOException {
        build(false);
    }

    private void build(boolean secure) throws IOException {
        loop = new ManualLoop();
        channel = new StubSocketChannel(LOCAL, REMOTE);
        handler = new Recorder();
        if (secure) {
            HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.SERVER, new CryptoServices());
            endpoint = new TcpEndpoint(handler, cfg, true);
        } else {
            endpoint = new TcpEndpoint(handler);
        }
        endpoint.setChannel(channel);
        endpoint.setSelectorLoop(loop);
        endpoint.setListener(new TcpListener() {
            @Override
            protected ProtocolHandler createHandler() {
                return null;
            }

            @Override
            public String getDescription() {
                return "proxy-test";
            }
        }, REMOTE);
        endpoint.init();
        endpoint.requireProxyHeader(HEADER_TIMEOUT_MS);
        endpoint.connected();
    }

    /** Appends bytes to netIn and runs the inbound path, as the loop does. */
    private void push(byte[] data) {
        endpoint.netIn.put(data);
        endpoint.netIn.flip();
        endpoint.processInbound();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] V1 = ascii("PROXY TCP4 192.0.2.7 198.51.100.9 51000 25\r\n");

    @Test
    public void handlerIsNotConnectedUntilTheHeaderIsComplete() {
        assertTrue("nothing before any bytes", handler.events.isEmpty());
        push(ascii("PROXY TCP4 192.0.2.7 "));
        assertTrue("nothing on a partial header", handler.events.isEmpty());
        assertEquals("addresses are the socket's until then", REMOTE,
                endpoint.getRemoteAddress());
        push(ascii("198.51.100.9 51000 25\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals("connected", handler.events.get(0));
    }

    @Test
    public void headerFedOneByteAtATime() {
        for (int i = 0; i < V1.length - 1; i++) {
            push(new byte[] { V1[i] });
            assertTrue(handler.events.isEmpty());
        }
        push(new byte[] { V1[V1.length - 1] });
        assertEquals(1, handler.events.size());
    }

    @Test
    public void assertedAddressesReplaceTheSocketsButNotThePeer() throws Exception {
        push(V1);
        assertEquals(new InetSocketAddress(
                InetAddress.getByName("192.0.2.7"), 51000), endpoint.getRemoteAddress());
        assertEquals(new InetSocketAddress(
                InetAddress.getByName("198.51.100.9"), 25), endpoint.getLocalAddress());
        assertEquals("trust decisions still see the relay", REMOTE,
                endpoint.getTransportPeerAddress());
    }

    @Test
    public void bytesAfterTheHeaderGoToTheHandler() {
        push(concat(V1, ascii("EHLO x\r\n")));
        assertEquals("EHLO x\r\n", handler.text());
        assertEquals("connected", handler.events.get(0));
    }

    @Test
    public void onlyTheFirstHeaderIsConsumed() {
        byte[] second = ascii("PROXY TCP4 10.0.0.1 10.0.0.2 3 4\r\n");
        push(concat(V1, second));
        assertEquals(new String(second, StandardCharsets.US_ASCII), handler.text());
        assertEquals(51000, ((InetSocketAddress) endpoint.getRemoteAddress()).getPort());
    }

    @Test
    public void unknownKeepsTheSocketAddresses() {
        push(ascii("PROXY UNKNOWN\r\n"));
        assertEquals("connected", handler.events.get(0));
        assertEquals(REMOTE, endpoint.getRemoteAddress());
        assertEquals(LOCAL, endpoint.getLocalAddress());
    }

    @Test
    public void v2TlvsAreExposed() {
        byte[] body = new byte[] {
            (byte) 0xC0, 0, 2, 1, (byte) 0xC6, 0x33, 0x64, 9, (byte) 0xC7, 0x38, 0, 25,
            0x02, 0, 4, 'm', 'x', '.', 'y' };
        byte[] sig = { 0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A };
        push(concat(sig, new byte[] { 0x21, 0x11, 0, (byte) body.length }, body));
        assertEquals("connected", handler.events.get(0));
        assertNotNull(endpoint.getProxyTlvs());
        assertEquals("mx.y", endpoint.getProxyTlvs().getAuthority());
        assertEquals(51000, ((InetSocketAddress) endpoint.getRemoteAddress()).getPort());
    }

    @Test
    public void malformedHeaderClosesWithoutTheHandlerHearingOfIt() {
        push(ascii("EHLO example.org\r\n"));
        assertFalse(endpoint.isOpen());
        assertTrue("handler never connected, so it is told nothing: "
                + handler.events, handler.events.isEmpty());
        assertEquals("", handler.text());
    }

    @Test
    public void closeBeforeTheHeaderDoesNotReachTheHandler() {
        push(ascii("PROXY TCP4 "));
        endpoint.close();
        assertTrue(handler.events.toString(), handler.events.isEmpty());
    }

    @Test
    public void headerDeadlineClosesTheConnection() {
        List<ScheduledTimer.TimerEntry> timers = loop.pending();
        assertEquals("only the header deadline is armed", 1, timers.size());
        timers.get(0).callback.run();
        assertFalse(endpoint.isOpen());
        assertTrue(handler.events.toString(), handler.events.isEmpty());
    }

    @Test
    public void deadlineIsReleasedAndNormalTimeoutsTakeOverOnceTheHeaderArrives() {
        push(V1);
        List<ScheduledTimer.TimerEntry> timers = loop.pending();
        assertEquals("the first-byte timeout replaces the header deadline",
                1, timers.size());
    }

    @Test
    public void tlsEstablishmentWaitsForTheHeader() throws Exception {
        build(true);
        assertEquals("only the header deadline before the header",
                1, loop.pending().size());
        push(V1);
        assertEquals("the handshake timeout is armed once the header is read",
                1, loop.pending().size());
        assertEquals(51000, ((InetSocketAddress) endpoint.getRemoteAddress()).getPort());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return out.toByteArray();
    }

}
