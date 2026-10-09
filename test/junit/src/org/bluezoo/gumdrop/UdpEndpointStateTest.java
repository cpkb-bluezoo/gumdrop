/*
 * UdpEndpointStateTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.Test;

/**
 * Unit tests for {@link UdpEndpoint} that need no socket: plaintext
 * datagram queueing, unsupported operations, and the client side of the
 * DTLS 1.2, DTLS 1.3 and negotiating session set-up driven by hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointStateTest {

    private static class Recorder implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        int received;

        @Override
        public void receive(ByteBuffer data) {
            received += data.remaining();
            data.position(data.limit());
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
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 9);

    private static UdpEndpoint plain(Recorder r) {
        UdpEndpoint ep = new UdpEndpoint(r);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static UdpEndpoint dtlsClient(Recorder r, DtlsVersion version) {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setDtlsVersion(version);
        UdpEndpoint ep = new UdpEndpoint(r);
        ep.setFactory(factory);
        ep.setSecure(true);
        ep.setClientMode(true);
        ep.setRemoteAddress(PEER);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static void exerciseSecurityInfo(SecurityInfo info) {
        assertNotNull(info);
        info.getProtocol();
        info.getCipherSuite();
        info.getKeySize();
        info.getPeerCertificates();
        info.getLocalCertificates();
        info.getApplicationProtocol();
        info.getHandshakeDurationMs();
        info.isSessionResumed();
        assertNotNull(info.toString());
    }

    @Test(expected = NullPointerException.class)
    public void nullHandlerRejected() {
        new UdpEndpoint(null);
    }

    @Test
    public void plainEndpointDefaults() {
        Recorder r = new Recorder();
        UdpEndpoint ep = plain(r);
        assertFalse(ep.isOpen());
        assertFalse(ep.isClosing());
        assertFalse(ep.isSecure());
        assertEquals(ChannelHandler.Type.DATAGRAM_SERVER, ep.getChannelType());
        assertEquals("localhost", ((InetSocketAddress) ep.getLocalAddress()).getHostString());
        assertEquals("unknown", ((InetSocketAddress) ep.getRemoteAddress()).getHostString());
        assertSame(NullSecurityInfo.INSTANCE, ep.getSecurityInfo());
        assertNotNull(ep.getSelectorLoop());
        assertNull(ep.getTrace());
        assertNotNull(ep.getTelemetryConfig());
        assertNull(ep.getSelectionKey());
        assertSame(r, ep.getHandler());
        ep.setSelectionKey(null);
        ep.close();
        assertTrue(ep.isClosing());
        assertEquals("disconnected", r.events.get(0));
        ep.close();
        assertEquals(1, r.events.size());
    }

    @Test
    public void unsupportedOperations() {
        UdpEndpoint ep = plain(new Recorder());
        try {
            ep.startTLS();
            fail("expected UnsupportedOperationException");
        } catch (IOException e) {
            fail("unexpected IOException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            ep.pauseRead();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            ep.resumeRead();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            ep.onWriteReady(null);
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        ep.close();
    }

    @Test
    public void serverModeSendNeedsDestination() {
        UdpEndpoint ep = plain(new Recorder());
        try {
            ep.send(ByteBuffer.wrap(new byte[] {1}));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
        ep.close();
    }

    @Test
    public void sendCopiesDatagramIntoPendingQueue() {
        UdpEndpoint ep = plain(new Recorder());
        ep.setRemoteAddress(PEER);
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        ep.sendTo(ByteBuffer.wrap(new byte[] {4, 5}), PEER);
        assertEquals(2, ep.pendingDatagrams.size());
        UdpEndpoint.PendingDatagram first = ep.pendingDatagrams.peek();
        assertEquals(3, first.queuedBytes);
        ep.onPendingDatagramFullySent(ep.pendingDatagrams.poll());
        ByteBuffer owned = java.nio.ByteBuffer.allocate(4);
        ep.sendOwnedRawDatagram(owned, PEER);
        assertEquals(2, ep.pendingDatagrams.size());
        ep.send(null);
        assertTrue(ep.isClosing());
        assertTrue(ep.pendingDatagrams.isEmpty());
    }

    @Test
    public void pendingQueueOverflowClosesEndpoint() {
        Recorder r = new Recorder();
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setMaxNetOutSize(8);
        UdpEndpoint ep = new UdpEndpoint(r);
        ep.setFactory(factory);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.setRemoteAddress(PEER);
        ep.sendTo(ByteBuffer.wrap(new byte[6]), PEER);
        assertFalse(ep.isClosing());
        ep.sendTo(ByteBuffer.wrap(new byte[6]), PEER);
        assertTrue(ep.isClosing());
        assertTrue(r.events.contains("disconnected"));
    }

    @Test
    public void plaintextReceiveGoesToHandler() {
        Recorder r = new Recorder();
        UdpEndpoint ep = plain(r);
        ep.netReceive(ByteBuffer.wrap(new byte[] {1, 2, 3}), PEER);
        assertEquals(3, r.received);
        assertEquals(PEER, ep.getRemoteAddress());
        ep.close();
    }

    @Test
    public void deliverPlaintextReleasesBuffer() {
        Recorder r = new Recorder();
        UdpEndpoint ep = plain(r);
        ByteBuffer b = org.bluezoo.gumdrop.util.ByteBufferPool.acquire(16);
        b.put(new byte[5]);
        b.flip();
        ep.deliverPlaintext(PEER, b);
        assertEquals(5, r.received);
        ep.notifyDtlsHandshakeComplete(PEER, NullSecurityInfo.INSTANCE);
        assertTrue(r.events.contains("secure"));
        ep.close();
    }

    /**
     * Decrypted data can surface after datagrams from other peers have been
     * received (handshake offload), so the reply address must be the peer
     * of the delivering session, not the last datagram's source.
     */
    @Test
    public void deliverPlaintextSelectsTheSessionsPeerInServerMode() {
        final InetSocketAddress other = new InetSocketAddress("127.0.0.1", 10);
        final List<SocketAddress> seen = new ArrayList<SocketAddress>();
        final UdpEndpoint[] holder = new UdpEndpoint[1];
        Recorder r = new Recorder() {
            @Override
            public void receive(ByteBuffer data) {
                seen.add(holder[0].getRemoteAddress());
                data.position(data.limit());
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
                seen.add(holder[0].getRemoteAddress());
            }
        };
        UdpEndpoint ep = plain(r);
        holder[0] = ep;
        ep.netReceive(ByteBuffer.wrap(new byte[] {1}), other);
        seen.clear();
        ByteBuffer b = org.bluezoo.gumdrop.util.ByteBufferPool.acquire(16);
        b.put(new byte[2]);
        b.flip();
        ep.deliverPlaintext(PEER, b);
        ep.notifyDtlsHandshakeComplete(PEER, NullSecurityInfo.INSTANCE);
        assertEquals(2, seen.size());
        assertEquals(PEER, seen.get(0));
        assertEquals(PEER, seen.get(1));
        ep.close();
    }

    @Test
    public void dtls12ClientStartsHandshake() {
        Recorder r = new Recorder();
        UdpEndpoint ep = dtlsClient(r, DtlsVersion.DTLS_1_2);
        assertTrue(ep.isSecure());
        ep.startClientDtlsHandshake();
        assertFalse(ep.pendingDatagrams.isEmpty());
        exerciseSecurityInfo(ep.getSecurityInfo());
        ep.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        ep.netReceive(ByteBuffer.wrap(new byte[] {0x16, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 2}), PEER);
        ep.onDtlsSessionFailed(PEER, new IOException("failed"));
        assertFalse(r.errors.isEmpty());
        ep.close();
    }

    @Test
    public void dtls13ClientStartsHandshake() {
        Recorder r = new Recorder();
        UdpEndpoint ep = dtlsClient(r, DtlsVersion.DTLS_1_3);
        ep.startClientDtlsHandshake();
        assertFalse(ep.pendingDatagrams.isEmpty());
        exerciseSecurityInfo(ep.getSecurityInfo());
        ep.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        ep.netReceive(ByteBuffer.wrap(new byte[] {0x16, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 2}), PEER);
        ep.onDtls13SessionFailed(PEER, new IOException("failed"));
        assertFalse(r.errors.isEmpty());
        ep.close();
    }

    @Test
    public void negotiatingClientStartsHandshake() {
        Recorder r = new Recorder();
        UdpEndpoint ep = dtlsClient(r, DtlsVersion.NEGOTIATE);
        ep.startClientDtlsHandshake();
        assertFalse(ep.pendingDatagrams.isEmpty());
        exerciseSecurityInfo(ep.getSecurityInfo());
        ep.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        ep.netReceive(ByteBuffer.wrap(new byte[] {0x16, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 2}), PEER);
        ep.removeNegotiatingDtlsSession(PEER);
        ep.close();
    }

    @Test
    public void secureEndpointWithoutSessionDropsOutboundData() {
        UdpEndpoint ep = dtlsClient(new Recorder(), DtlsVersion.DTLS_1_2);
        ep.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        assertTrue(ep.pendingDatagrams.isEmpty());
        ep.close();
        UdpEndpoint ep13 = dtlsClient(new Recorder(), DtlsVersion.DTLS_1_3);
        ep13.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        assertTrue(ep13.pendingDatagrams.isEmpty());
        ep13.close();
        UdpEndpoint epN = dtlsClient(new Recorder(), DtlsVersion.NEGOTIATE);
        epN.sendTo(ByteBuffer.wrap(new byte[] {1}), PEER);
        assertTrue(epN.pendingDatagrams.isEmpty());
        epN.close();
    }

    @Test
    public void serverWithoutDtlsConfigurationFails() {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setSecure(true);
        factory.setDtlsVersion(DtlsVersion.DTLS_1_2);
        UdpEndpoint ep = new UdpEndpoint(new Recorder());
        ep.setFactory(factory);
        ep.setSecure(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        try {
            ep.netReceive(ByteBuffer.wrap(new byte[] {1, 2, 3}), PEER);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
        ep.close();
    }

    @Test
    public void clientHandshakeIgnoredWhenNotApplicable() {
        Recorder r = new Recorder();
        UdpEndpoint ep = plain(r);
        ep.startClientDtlsHandshake();
        assertTrue(ep.pendingDatagrams.isEmpty());
        ep.close();
    }
}
