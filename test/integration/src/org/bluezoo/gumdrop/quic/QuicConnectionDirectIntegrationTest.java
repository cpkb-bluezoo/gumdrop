/*
 * ClientConnectApplyTest.java
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

package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link QuicConnection} and {@link QuicEngine} paths that need
 * hand-crafted input: malformed and spoofed packets, Retry and Version
 * Negotiation edge cases, transport parameter validation, connection ID
 * rotation and direct callbacks.
 *
 * <p>Integration test: reads a datagram through a real loopback DatagramChannel
 * engine.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicConnectionDirectIntegrationTest {

    /** Tickets cached by other tests would seed remembered transport parameters. */
    @Before
    public void clearTicketCache() {
        SessionTicketCache.clear();
    }

    @Test
    public void datagramChannelEngineReadsFromSocket() throws Exception {
        QuicEngine engine = new QuicEngine(new QuicTransportFactory(), true);
        engine.setSelectorLoop(new InlineSelectorLoop());
        DatagramChannel dc = DatagramChannel.open();
        dc.configureBlocking(false);
        dc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        engine.init(dc);
        try {
            DatagramSocket sender = new DatagramSocket();
            try {
                byte[] junk = new byte[] {0x40, 1, 2, 3};
                sender.send(new java.net.DatagramPacket(junk, junk.length, dc.socket().getLocalSocketAddress()));
            } finally {
                sender.close();
            }
            for (int i = 0; i < 3; i++) {
                engine.onReadable();
            }
            assertTrue(engine.isOpen());
            assertNotNull(engine.getSelectorLoop());
            assertNull(engine.getSelectionKey());
            engine.setSelectionKey(null);
            assertEquals(org.bluezoo.gumdrop.ChannelHandler.Type.QUIC, engine.getChannelType());
            engine.onWritable();
            engine.close();
            assertFalse(engine.isOpen());
        } finally {
            dc.close();
        }
    }
}
