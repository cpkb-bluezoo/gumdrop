/*
 * TlsRecordStateEdgeTest.java
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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.junit.Test;

/**
 * Behaviour of the TLS 1.3 and TLS 1.2 record states, driven through a
 * client {@link TcpEndpoint} upgraded with STARTTLS, once the connection is
 * closed (everything further is ignored), while application data is held
 * back during the handshake, and when that held data would exceed the
 * outbound ceiling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsRecordStateEdgeTest {

    private static final byte[] FATAL_ALERT = new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 2, 40};

    private static final class Recorder implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void receive(ByteBuffer data) {
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

    private static TcpEndpoint tls13(Recorder h, int maxOut) throws IOException {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        return upgrade(ep, maxOut);
    }

    private static TcpEndpoint tls12(Recorder h, int maxOut) throws IOException {
        Tls12HandshakeConfig cfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        TcpEndpoint ep = new TcpEndpoint(h, cfg, false);
        return upgrade(ep, maxOut);
    }

    private static TcpEndpoint upgrade(TcpEndpoint ep, int maxOut) throws IOException {
        ep.setClientMode(true);
        ep.setSelectorLoop(new InlineSelectorLoop());
        if (maxOut > 0) {
            TcpTransportFactory f = new TcpTransportFactory();
            f.setMaxNetOutSize(maxOut);
            ep.setFactory(f);
        }
        ep.init();
        ep.startTLS();
        return ep;
    }

    private static void feedAlert(TcpEndpoint ep) throws IOException {
        ByteBuffer in = ep.prepareNetInForRead();
        in.put(FATAL_ALERT);
        in.flip();
        ep.processInbound();
    }

    private void assertClosedStateIgnoresActivity(TcpEndpoint ep, Recorder h) throws IOException {
        feedAlert(ep);
        assertTrue(h.events.contains("disconnected"));
        ep.initiateClientTLSHandshake();
        ep.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        ep.close();
        assertTrue(ep.isClosing());
        assertEquals(1, countOf(h.events, "disconnected"));
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
    public void tls13ClosedStateIgnoresFurtherActivity() throws IOException {
        Recorder h = new Recorder();
        assertClosedStateIgnoresActivity(tls13(h, 0), h);
    }

    @Test
    public void tls12ClosedStateIgnoresFurtherActivity() throws IOException {
        Recorder h = new Recorder();
        assertClosedStateIgnoresActivity(tls12(h, 0), h);
    }

    private void assertHandshakeHoldsApplicationData(TcpEndpoint ep, Recorder h) {
        ep.send(ByteBuffer.wrap(new byte[20000]));
        ep.send(ByteBuffer.wrap(new byte[30000]));
        assertTrue(ep.hasPendingWrite());
        assertTrue(h.errors.isEmpty());
        ep.close();
        assertTrue(ep.isClosing());
        ep.close();
    }

    @Test
    public void tls13HoldsApplicationDataUntilTheHandshakeCompletes() throws IOException {
        Recorder h = new Recorder();
        assertHandshakeHoldsApplicationData(tls13(h, 0), h);
    }

    @Test
    public void tls12HoldsApplicationDataUntilTheHandshakeCompletes() throws IOException {
        Recorder h = new Recorder();
        assertHandshakeHoldsApplicationData(tls12(h, 0), h);
    }

    @Test
    public void tls13HeldDataBeyondTheCeilingClosesTheConnection() throws IOException {
        Recorder h = new Recorder();
        TcpEndpoint ep = tls13(h, 40000);
        ep.send(ByteBuffer.wrap(new byte[100000]));
        assertTrue(h.events.contains("disconnected"));
    }

    @Test
    public void tls12HeldDataBeyondTheCeilingClosesTheConnection() throws IOException {
        Recorder h = new Recorder();
        TcpEndpoint ep = tls12(h, 40000);
        ep.send(ByteBuffer.wrap(new byte[100000]));
        assertTrue(h.events.contains("disconnected"));
    }
}
