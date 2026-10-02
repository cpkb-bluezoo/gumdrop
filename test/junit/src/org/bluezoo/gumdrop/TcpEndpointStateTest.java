/*
 * TcpEndpointStateTest.java
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * Unit tests for {@link TcpEndpoint} state handling that need no socket:
 * buffer management, close semantics, STARTTLS preconditions and the
 * client-side TLS record states driven by hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointStateTest {

    /** Handler that records every callback. */
    private static final class RecordingHandler implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        int received;
        SecurityInfo security;

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
            security = info;
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

    private static TcpEndpoint plain(RecordingHandler h) throws IOException {
        TcpEndpoint ep = new TcpEndpoint(h);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static byte[] drain(TcpEndpoint ep) {
        ByteBuffer out = ep.getNetOut();
        ByteBuffer copy = out.duplicate();
        copy.flip();
        byte[] b = new byte[copy.remaining()];
        copy.get(b);
        return b;
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
        new TcpEndpoint(null);
    }

    @Test
    public void defaultsWithoutChannel() throws IOException {
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = plain(h);
        assertFalse(ep.isOpen());
        assertFalse(ep.isClosing());
        assertFalse(ep.isSecure());
        assertFalse(ep.isClientMode());
        assertFalse(ep.isReadPaused());
        assertEquals(ChannelHandler.Type.TCP, ep.getChannelType());
        assertEquals("localhost", ((InetSocketAddress) ep.getLocalAddress()).getHostString());
        assertEquals("unknown", ((InetSocketAddress) ep.getRemoteAddress()).getHostString());
        assertSame(NullSecurityInfo.INSTANCE, ep.getSecurityInfo());
        assertNull(ep.getTransportFactory());
        assertNull(ep.getTrace());
        assertFalse(ep.isTelemetryEnabled());
        assertNull(ep.getTelemetryConfig());
        assertTrue(ep.getTimestampCreated() > 0);
        assertTrue(ep.getTimestampLastActivity() >= ep.getTimestampCreated());
        assertTrue(ep.getIdleTimeMs() >= 0);
        assertNull(ep.takeSocketChannelForHandoff());
        assertNull(ep.getSelectionKey());
        assertFalse(ep.hasPendingWrite());
    }

    @Test
    public void sendAppendsToOutboundBuffer() throws IOException {
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = plain(h);
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertTrue(ep.hasPendingWrite());
        assertEquals(3, drain(ep).length);
        byte[] big = new byte[100000];
        ep.send(ByteBuffer.wrap(big));
        assertEquals(100003, drain(ep).length);
    }

    @Test
    public void sendNullClosesEndpoint() throws IOException {
        TcpEndpoint ep = plain(new RecordingHandler());
        ep.send(null);
        assertTrue(ep.isClosing());
        assertTrue(ep.hasPendingWrite());
        ep.close();
    }

    @Test
    public void writeReadyCallbackAccessors() throws IOException {
        TcpEndpoint ep = plain(new RecordingHandler());
        Runnable r = new Runnable() {
            @Override
            public void run() {
                // no-op
            }
        };
        ep.onWriteReady(r);
        assertSame(r, ep.getWriteCompleteCallback());
        ep.setWriteCompleteCallback(null);
        assertNull(ep.getWriteCompleteCallback());
    }

    @Test
    public void closeWhenOutboundIdleRegistersCallbackThatCloses() throws IOException {
        TcpEndpoint ep = plain(new RecordingHandler());
        ep.closeWhenOutboundIdle();
        Runnable cb = ep.getWriteCompleteCallback();
        assertNotNull(cb);
        assertFalse(ep.isClosing());
        cb.run();
        assertTrue(ep.isClosing());
        ep.closeWhenOutboundIdle();
    }

    @Test
    public void pauseAndResumeRead() throws IOException {
        TcpEndpoint ep = plain(new RecordingHandler());
        ep.resumeRead();
        assertFalse(ep.isReadPaused());
        ep.pauseRead();
        assertTrue(ep.isReadPaused());
        ep.pauseRead();
        ep.resumeRead();
        assertFalse(ep.isReadPaused());
    }

    @Test
    public void plaintextInboundIsDeliveredAndCompacted() throws IOException {
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = plain(h);
        ByteBuffer in = ep.prepareNetInForRead();
        in.put(new byte[] {1, 2, 3, 4});
        in.flip();
        ep.processInbound();
        assertEquals(4, h.received);
    }

    @Test
    public void inboundBufferGrowsAndRespectsLimit() throws IOException {
        TcpEndpoint ep = plain(new RecordingHandler());
        ByteBuffer in = ep.prepareNetInForRead();
        int cap = in.capacity();
        in.position(in.limit() - 100);
        ByteBuffer grown = ep.prepareNetInForRead();
        assertTrue(grown.capacity() > cap);
    }

    @Test
    public void connectedDeliversToHandler() throws IOException {
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = plain(h);
        ep.connected();
        assertEquals("connected", h.events.get(0));
        ep.initiateClientTLSHandshake();
    }

    @Test
    public void eofDeliversDisconnectOnce() throws IOException {
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = plain(h);
        ep.handleEOF();
        ep.deliverDisconnected();
        ep.doClose();
        assertEquals(1, h.events.size());
        assertEquals("disconnected", h.events.get(0));
    }

    @Test
    public void errorHandlersNotifyHandlerAndClose() throws IOException {
        IOException reset = new IOException("Connection reset by peer");
        RecordingHandler h1 = new RecordingHandler();
        TcpEndpoint ep1 = plain(h1);
        ep1.handleReadError(reset);
        assertEquals(1, h1.errors.size());
        assertTrue(h1.events.contains("disconnected"));

        RecordingHandler h2 = new RecordingHandler();
        TcpEndpoint ep2 = plain(h2);
        ep2.handleWriteError(new IOException("Broken pipe"));
        assertEquals(1, h2.errors.size());

        RecordingHandler h3 = new RecordingHandler();
        TcpEndpoint ep3 = plain(h3);
        ep3.handleConnectError(new IOException("refused"));
        assertEquals(1, h3.errors.size());

        RecordingHandler h4 = new RecordingHandler();
        TcpEndpoint ep4 = plain(h4);
        ep4.handleDispatchError(new IllegalStateException("boom"));
        assertEquals(1, h4.errors.size());
        assertTrue(h4.events.contains("disconnected"));
    }

    @Test
    public void startTlsPreconditions() throws IOException {
        TcpEndpoint noTls = plain(new RecordingHandler());
        try {
            noTls.startTLS();
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint already = new TcpEndpoint(new RecordingHandler(), cfg, false);
        already.setSelectorLoop(new InlineSelectorLoop());
        already.init();
        already.startTLS();
        try {
            already.startTLS();
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void startTlsUpgradesPlainEndpoint() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        assertTrue(ep.isClientMode());
        ep.startTLS();
        assertTrue(ep.isSecure());
        assertTrue(ep.hasPendingWrite());
        byte[] hello = drain(ep);
        assertTrue(hello.length > 5);
        assertEquals(0x16, hello[0] & 0xff);
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        exerciseSecurityInfo(ep.getSecurityInfo());
    }

    @Test
    public void startTls12UpgradesPlainEndpoint() throws IOException {
        Tls12HandshakeConfig cfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.startTLS();
        byte[] hello = drain(ep);
        assertEquals(0x16, hello[0] & 0xff);
        ep.send(ByteBuffer.wrap(new byte[] {1}));
        exerciseSecurityInfo(ep.getSecurityInfo());
        ep.close();
        assertTrue(ep.isClosing());
    }

    @Test
    public void negotiatingClientEmitsHelloAndWaitsForServerHello() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        Tls12HandshakeConfig cfg12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, cfg12, TlsVersion.NEGOTIATE, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.startTLS();
        byte[] hello = drain(ep);
        assertEquals(0x16, hello[0] & 0xff);
        assertNotNull(ep.getSecurityInfo());

        ByteBuffer in = ep.prepareNetInForRead();
        byte[] garbage = new byte[] {0x63, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5};
        in.put(garbage);
        in.flip();
        ep.processInbound();
        assertTrue(h.errors.isEmpty());
        ep.send(ByteBuffer.wrap(new byte[] {1}));
        ep.close();
        assertTrue(ep.isClosing());
    }

    @Test
    public void tls13ClientRejectsGarbage() throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.startTLS();
        ep.connected();
        ByteBuffer in = ep.prepareNetInForRead();
        in.put(new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 2, 40});
        in.flip();
        ep.processInbound();
        assertTrue(h.events.contains("disconnected"));
    }

    @Test
    public void tls12ClientRejectsGarbage() throws IOException {
        Tls12HandshakeConfig cfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        RecordingHandler h = new RecordingHandler();
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        ep.startTLS();
        ByteBuffer in = ep.prepareNetInForRead();
        in.put(new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 2, 40});
        in.flip();
        ep.processInbound();
        assertTrue(h.events.contains("disconnected"));
    }

    @Test
    public void integrationTraceFlagFollowsSystemProperty() {
        String key = "gumdrop.integration.log.level";
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertFalse(TcpEndpoint.integrationTlsTraceEnabled());
            System.setProperty(key, "");
            assertFalse(TcpEndpoint.integrationTlsTraceEnabled());
            System.setProperty(key, "bogus");
            assertFalse(TcpEndpoint.integrationTlsTraceEnabled());
            System.setProperty(key, "fine");
            assertTrue(TcpEndpoint.integrationTlsTraceEnabled());
            System.setProperty(key, "WARNING");
            assertFalse(TcpEndpoint.integrationTlsTraceEnabled());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }
}
