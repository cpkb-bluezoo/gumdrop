/*
 * UDPDNSClientTransportTest.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link UdpDnsClientTransport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UDPDNSClientTransportTest {

    @Test
    public void testImplementsInterface() {
        UdpDnsClientTransport transport = new UdpDnsClientTransport();
        assertTrue("Should implement DnsClientTransport",
                transport instanceof DnsClientTransport);
    }

    @Test
    public void testCloseBeforeOpen() {
        UdpDnsClientTransport transport = new UdpDnsClientTransport();
        transport.close();
    }

    private static final class Seen implements DnsClientTransportHandler {
        final List<ByteBuffer> received = new ArrayList<ByteBuffer>();
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void onReceive(ByteBuffer data) {
            received.add(data);
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }
    }

    @Test
    public void testOpenWithoutALoopIsRejected() throws Exception {
        UdpDnsClientTransport transport = new UdpDnsClientTransport();
        try {
            transport.open(InetAddress.getLoopbackAddress(), 53, null, new Seen());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SelectorLoop"));
        }
    }

    @Test
    public void testDatagramHandlerForwardsToTheResolver() throws Exception {
        Seen seen = new Seen();
        Class<?> type = Class.forName(UdpDnsClientTransport.class.getName() + "$UdpProtocolHandler");
        Constructor<?> ctor = type.getDeclaredConstructor(DnsClientTransportHandler.class);
        ctor.setAccessible(true);
        ProtocolHandler handler = (ProtocolHandler) ctor.newInstance(seen);
        handler.connected(null);
        handler.securityEstablished(null);
        ByteBuffer datagram = ByteBuffer.wrap(new byte[] {1, 2});
        handler.receive(datagram);
        IOException cause = new IOException("icmp");
        handler.error(cause);
        handler.disconnected();
        assertEquals(1, seen.received.size());
        assertSame(datagram, seen.received.get(0));
        assertEquals(1, seen.errors.size());
        assertSame(cause, seen.errors.get(0));
    }

}
