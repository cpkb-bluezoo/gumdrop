/*
 * DNSResolverTransportPreferenceTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for issue #408: {@link DNSServerCapabilityCache}'s two
 * tiers (permanent seed table, temporary negative cache), and {@link
 * DNSResolver}'s automatic per-server transport preference and
 * fallback that uses it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolverTransportPreferenceTest {

    @Before
    public void setUp() {
        DNSServerCapabilityCache.clear();
    }

    @After
    public void tearDown() {
        DNSServerCapabilityCache.clear();
    }

    // ── DNSServerCapabilityCache ──

    @Test
    public void testUnknownServerHasNoKnownCapabilities() throws Exception {
        InetSocketAddress server = server("203.0.113.1");
        DNSServerCapabilities caps = DNSServerCapabilityCache.get(server);
        assertFalse(caps.isDoqSupported());
        assertFalse(caps.isDotSupported());
        assertFalse(caps.isDohSupported());
    }

    @Test
    public void testWellKnownResolversAreSeeded() throws Exception {
        for (String address : new String[] {
                "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112"}) {
            DNSServerCapabilities caps = DNSServerCapabilityCache.get(server(address));
            assertTrue(address + " should support DoQ", caps.isDoqSupported());
            assertTrue(address + " should support DoT", caps.isDotSupported());
            assertTrue(address + " should support DoH", caps.isDohSupported());
            assertEquals("/dns-query", caps.getDohPath());
        }
    }

    @Test
    public void testMarkUnsupportedRoundTrips() throws Exception {
        InetSocketAddress server = server("8.8.8.8");
        assertFalse(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOQ));
        DNSServerCapabilityCache.markUnsupported(server, DNSTransportType.DOQ);
        assertTrue(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOQ));
        // Marking one transport unsupported doesn't affect another for the same server.
        assertFalse(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOT));
    }

    @Test
    public void testClearResetsNegativeCacheNotWellKnownTable() throws Exception {
        InetSocketAddress server = server("8.8.8.8");
        DNSServerCapabilityCache.markUnsupported(server, DNSTransportType.DOQ);
        DNSServerCapabilityCache.clear();
        assertFalse(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOQ));
        // Seeded knowledge is permanent, unlike runtime-discovered state.
        assertTrue(DNSServerCapabilityCache.get(server).isDoqSupported());
    }

    // ── DNSResolver transport preference/fallback ──

    @Test
    public void testPrefersDoqForWellKnownServer() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.addServer("8.8.8.8");
        resolver.open();

        assertEquals(Collections.singletonList(DNSTransportType.DOQ), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testFallsThroughOnSynchronousOpenFailure() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.transports.put(DNSTransportType.DOQ, new FailingTransport());
        resolver.addServer("8.8.8.8");
        resolver.open();

        assertEquals(Arrays.asList(DNSTransportType.DOQ, DNSTransportType.DOT), resolver.attempted);
        assertTrue("DOQ should be recorded as unsupported after its open() failure",
                DNSServerCapabilityCache.isKnownUnsupported(server("8.8.8.8"), DNSTransportType.DOQ));
        resolver.close();
    }

    @Test
    public void testFallsThroughAllTheWayToPlainWhenEverythingElseFails() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.transports.put(DNSTransportType.DOQ, new FailingTransport());
        resolver.transports.put(DNSTransportType.DOT, new FailingTransport());
        resolver.transports.put(DNSTransportType.DOH, new FailingTransport());
        resolver.addServer("8.8.8.8");
        resolver.open();

        assertEquals(Arrays.asList(DNSTransportType.DOQ, DNSTransportType.DOT,
                DNSTransportType.DOH, DNSTransportType.PLAIN), resolver.attempted);
        InetSocketAddress server = server("8.8.8.8");
        assertTrue(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOQ));
        assertTrue(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOT));
        assertTrue(DNSServerCapabilityCache.isKnownUnsupported(server, DNSTransportType.DOH));
        resolver.close();
    }

    @Test
    public void testUnknownServerGoesStraightToPlain() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.addServer("203.0.113.1"); // not a seeded well-known resolver
        resolver.open();

        assertEquals(Collections.singletonList(DNSTransportType.PLAIN), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testKnownUnsupportedTransportIsSkippedOnNextOpen() throws Exception {
        InetSocketAddress server = server("8.8.8.8");
        DNSServerCapabilityCache.markUnsupported(server, DNSTransportType.DOQ);

        TestableResolver resolver = new TestableResolver();
        resolver.addServer("8.8.8.8");
        resolver.open();

        assertEquals(Collections.singletonList(DNSTransportType.DOT), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testAsyncTransportErrorMarksUnsupportedForFutureOpens() throws Exception {
        RecordingTransport doq = new RecordingTransport();
        TestableResolver resolver = new TestableResolver();
        resolver.transports.put(DNSTransportType.DOQ, doq);
        resolver.addServer("8.8.8.8");
        resolver.open();

        assertNotNull("transport.open() should have captured the resolver's handler", doq.handler);
        doq.handler.onError(new IOException("simulated QUIC handshake failure"));

        assertTrue(DNSServerCapabilityCache.isKnownUnsupported(server("8.8.8.8"), DNSTransportType.DOQ));
        resolver.close();
    }

    /**
     * Exercises the real (non-overridden) DOH branch of {@link
     * DNSResolver#newTransportInstance}, which loads the {@link
     * DoHTransportFactory} SPI -- unlike the other tests here, which
     * override that method entirely and so never touch the SPI lookup.
     * gumdrop-http.jar (with its {@code META-INF/services} registration)
     * is on this test's classpath, so the factory should resolve; a
     * core-only deployment without the HTTP module would instead see
     * this return null, which callers already handle by skipping to the
     * next preference.
     */
    @Test
    public void testDohTransportInstanceResolvesViaServiceLoader() {
        DNSResolver resolver = new DNSResolver();
        DNSServerCapabilities caps = DNSServerCapabilities.of(false, false, "/dns-query");
        DNSClientTransport transport = resolver.newTransportInstance(DNSTransportType.DOH, caps);
        assertNotNull("DoHTransportFactory should be discovered from gumdrop-http.jar on the test classpath",
                transport);
    }

    @Test
    public void testExplicitTransportOverrideSkipsPreferenceSelection() throws Exception {
        RecordingTransport explicit = new RecordingTransport();
        TestableResolver resolver = new TestableResolver();
        resolver.setTransport(explicit);
        resolver.addServer("8.8.8.8");
        resolver.open();

        // newTransportInstance (the preference-selection seam) is never
        // consulted when an explicit transport is configured.
        assertTrue(resolver.attempted.isEmpty());
        assertNotNull(explicit.handler);
        resolver.close();
    }

    // ── Helpers ──

    private static InetSocketAddress server(String address) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(address), 53);
    }

    // ── Test doubles ──

    private static class RecordingTransport implements DNSClientTransport {
        DNSClientTransportHandler handler;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }

        @Override
        public void close() {
        }
    }

    private static class FailingTransport implements DNSClientTransport {
        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DNSClientTransportHandler handler) throws IOException {
            throw new IOException("simulated open failure");
        }

        @Override
        public void send(ByteBuffer data) {
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return null;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Resolver subclass overriding {@link DNSResolver#newTransportInstance}
     * to return pre-configured mocks per type instead of real network
     * transports, and recording which types were attempted, in order.
     */
    private static class TestableResolver extends DNSResolver {
        final Map<DNSTransportType, DNSClientTransport> transports =
                new EnumMap<>(DNSTransportType.class);
        final List<DNSTransportType> attempted = new ArrayList<>();

        @Override
        DNSClientTransport newTransportInstance(DNSTransportType type, DNSServerCapabilities caps) {
            attempted.add(type);
            DNSClientTransport transport = transports.get(type);
            return transport != null ? transport : new RecordingTransport();
        }
    }
}
