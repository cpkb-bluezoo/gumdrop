/*
 * ClientEndpointFallbackTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.junit.Test;

/**
 * Checks that {@link ClientEndpoint} falls back to the next resolved
 * address when connecting to the first fails (RFC 8305), and that a handler
 * sees only the outcome of the final attempt.
 *
 * <p>The first candidate is {@code ::1} with nothing listening (connection
 * refused) and the second is {@code 127.0.0.1} with a listener on the same
 * port. The test is skipped when the host has no IPv6 loopback.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientEndpointFallbackTest {

    /** Resolves every name to a fixed address list. */
    private static final class FixedResolver extends DnsResolver {
        private final List<InetAddress> addresses;

        FixedResolver(List<InetAddress> addresses) {
            this.addresses = addresses;
        }

        @Override
        public void resolve(String hostname, ResolveCallback callback) {
            callback.onResolved(addresses);
        }
    }

    private static final class Recorder implements ProtocolHandler {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean connected;
        volatile Exception error;
        volatile int errors;

        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void connected(Endpoint endpoint) {
            connected = true;
            done.countDown();
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
            error = cause;
            errors++;
            done.countDown();
        }
    }

    private static boolean hasIpv6Loopback() {
        try {
            ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("::1"));
            probe.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Recorder connectThrough(List<InetAddress> addresses, int port)
            throws Exception {
        Gumdrop gumdrop = Gumdrop.boot();
        try {
            TcpTransportFactory factory = new TcpTransportFactory();
            factory.start();
            ClientEndpoint client = new ClientEndpoint(factory, "fallback.test", port);
            client.setDnsResolver(new FixedResolver(addresses));
            Recorder handler = new Recorder();
            client.connect(gumdrop, handler);
            assertTrue("no outcome", handler.done.await(10, TimeUnit.SECONDS));
            return handler;
        } finally {
            gumdrop.shutdownNow();
        }
    }

    @Test
    public void refusedFirstAddressFallsBackToTheSecond() throws Exception {
        assumeNotNull(hasIpv6Loopback() ? Boolean.TRUE : null);
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        try {
            Recorder handler = connectThrough(Arrays.asList(
                    InetAddress.getByName("::1"),
                    InetAddress.getByName("127.0.0.1")), server.getLocalPort());
            assertNull("fallback should hide the first failure", handler.error);
            assertTrue(handler.connected);
            Socket accepted = server.accept();
            accepted.close();
        } finally {
            server.close();
        }
    }

    @Test
    public void errorIsReportedOnceWhenEveryAddressFails() throws Exception {
        assumeNotNull(hasIpv6Loopback() ? Boolean.TRUE : null);
        // Reserve a port, then release it so both loopback addresses refuse.
        ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = reserve.getLocalPort();
        reserve.close();
        Recorder handler = connectThrough(Arrays.asList(
                InetAddress.getByName("::1"),
                InetAddress.getByName("127.0.0.1")), port);
        assertNotNull(handler.error);
        assertEquals(1, handler.errors);
    }
}
