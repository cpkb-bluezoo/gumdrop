/*
 * DNSResolverDDRTest.java
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
import org.bluezoo.gumdrop.dns.DNSClass;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQuestion;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for issue #410: {@link DNSResolver}'s RFC 9462 Discovery
 * of Designated Resolvers (DDR) support, built on the transport
 * preference/fallback machinery from issue #408.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolverDDRTest {

    @Before
    public void setUp() {
        DNSServerCapabilityCache.clear();
    }

    @After
    public void tearDown() {
        DNSServerCapabilityCache.clear();
    }

    @Test
    public void testDdrDisabledByDefault() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.addServer("203.0.113.1"); // not a seeded well-known resolver
        resolver.open();

        assertNull("DDR must not run unless explicitly enabled", resolver.ddrTransport.handler);
        resolver.close();
    }

    @Test
    public void testDdrQueriesResolverArpaSvcb() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        assertNotNull("DDR should have opened its own transport", resolver.ddrTransport.handler);
        assertNotNull("DDR should have sent a query", resolver.ddrTransport.lastSent);

        DNSMessage sent = DNSMessage.parse(resolver.ddrTransport.lastSent);
        assertEquals(1, sent.getQuestions().size());
        DNSQuestion question = sent.getQuestions().get(0);
        assertEquals("_dns.resolver.arpa", question.getName());
        assertEquals(DNSType.SVCB, question.getType());
        resolver.close();
    }

    @Test
    public void testSuccessfulDdrLearnsCapabilitiesAndUpgradesTransport() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        assertEquals(Collections.singletonList(DNSTransportType.PLAIN), resolver.attempted);

        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DNSResourceRecord.SVCB_PARAM_ALPN,
                DNSResourceRecord.encodeSVCBAlpn(Arrays.asList("doq", "dot")));
        DNSResourceRecord svcb = DNSResourceRecord.svcb("_dns.resolver.arpa", 300, 1, ".", params);
        resolver.ddrTransport.handler.onReceive(ddrResponse(svcb).serialize());

        InetSocketAddress server = server("203.0.113.1");
        DNSServerCapabilities caps = DNSServerCapabilityCache.get(server);
        assertTrue(caps.isDoqSupported());
        assertTrue(caps.isDotSupported());
        assertFalse(caps.isDohSupported());

        // The upgrade re-runs transport selection; DOQ (first preference) wins.
        assertEquals(Arrays.asList(DNSTransportType.PLAIN, DNSTransportType.DOQ), resolver.attempted);
        assertTrue("the discovery transport should be closed once handled", resolver.ddrTransport.closed);
        resolver.close();
    }

    @Test
    public void testDohDiscoveryStripsUriTemplateAndDefaultsPath() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        Map<Integer, byte[]> withPath = new LinkedHashMap<>();
        withPath.put(DNSResourceRecord.SVCB_PARAM_ALPN,
                DNSResourceRecord.encodeSVCBAlpn(Arrays.asList("h2")));
        withPath.put(DNSResourceRecord.SVCB_PARAM_DOHPATH,
                DNSResourceRecord.encodeSVCBDohPath("/custom-doh{?dns}"));
        DNSResourceRecord svcb = DNSResourceRecord.svcb("_dns.resolver.arpa", 300, 1, ".", withPath);
        resolver.ddrTransport.handler.onReceive(ddrResponse(svcb).serialize());

        DNSServerCapabilities caps = DNSServerCapabilityCache.get(server("203.0.113.1"));
        assertTrue(caps.isDohSupported());
        assertEquals("the {?dns} URI Template suffix is not usable by DoHClientTransport's POST-only client",
                "/custom-doh", caps.getDohPath());
        resolver.close();
    }

    @Test
    public void testNxdomainResponseFailsOpen() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        List<DNSQuestion> questions = Collections.singletonList(
                new DNSQuestion("_dns.resolver.arpa", DNSType.SVCB, DNSClass.IN));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA
                | DNSMessage.RCODE_NXDOMAIN;
        DNSMessage response = new DNSMessage(1, flags, questions,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
        resolver.ddrTransport.handler.onReceive(response.serialize());

        assertEquals(DNSServerCapabilities.UNKNOWN, describeUnknown(server("203.0.113.1")));
        // No upgrade attempted -- resolver stays on its original PLAIN transport.
        assertEquals(Collections.singletonList(DNSTransportType.PLAIN), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testAliasFormRecordIsIgnored() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        // SvcPriority 0 (AliasForm) carries no usable SvcParams.
        DNSResourceRecord alias = DNSResourceRecord.svcb(
                "_dns.resolver.arpa", 300, 0, "target.example.net", null);
        resolver.ddrTransport.handler.onReceive(ddrResponse(alias).serialize());

        assertFalse(DNSServerCapabilityCache.get(server("203.0.113.1")).isDoqSupported());
        assertEquals(Collections.singletonList(DNSTransportType.PLAIN), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testDdrTimeoutFailsOpenWithoutAffectingCache() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("203.0.113.1");
        resolver.open();

        assertNotNull(resolver.ddrTransport.onTimeoutCallback);
        resolver.ddrTransport.onTimeoutCallback.run();

        assertFalse(DNSServerCapabilityCache.get(server("203.0.113.1")).isDoqSupported());
        assertTrue(resolver.ddrTransport.closed);
        assertEquals(Collections.singletonList(DNSTransportType.PLAIN), resolver.attempted);
        resolver.close();
    }

    @Test
    public void testDdrNotAttemptedForWellKnownServer() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.addServer("8.8.8.8"); // already known-good; nothing to discover
        resolver.open();

        assertNull("DDR should not run when a transport preference was already known",
                resolver.ddrTransport.handler);
        resolver.close();
    }

    @Test
    public void testDdrNotAttemptedWithExplicitTransportOverride() throws Exception {
        TestableResolver resolver = new TestableResolver();
        resolver.setDdrEnabled(true);
        resolver.setTransport(new RecordingTransport());
        resolver.addServer("203.0.113.1");
        resolver.open();

        assertNull("an explicit setTransport() override disables all automatic behavior",
                resolver.ddrTransport.handler);
        resolver.close();
    }

    // ── Helpers ──

    private static InetSocketAddress server(String address) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(address), 53);
    }

    private static DNSServerCapabilities describeUnknown(InetSocketAddress server) {
        return DNSServerCapabilityCache.get(server);
    }

    private static DNSMessage ddrResponse(DNSResourceRecord... answers) {
        List<DNSQuestion> questions = Collections.singletonList(
                new DNSQuestion("_dns.resolver.arpa", DNSType.SVCB, DNSClass.IN));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA;
        return new DNSMessage(1, flags, questions, Arrays.asList(answers),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
    }

    // ── Test doubles ──

    private static class RecordingTransport implements DNSClientTransport {
        DNSClientTransportHandler handler;
        ByteBuffer lastSent;
        boolean closed;
        Runnable onTimeoutCallback;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            lastSent = ByteBuffer.allocate(data.remaining());
            lastSent.put(data.duplicate());
            lastSent.flip();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, final Runnable callback) {
            this.onTimeoutCallback = callback;
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Resolver subclass overriding both {@link DNSResolver#newTransportInstance}
     * (from issue #408) and {@link DNSResolver#createDdrTransport} to
     * inject mocks instead of real network transports.
     */
    private static class TestableResolver extends DNSResolver {
        final Map<DNSTransportType, DNSClientTransport> transports =
                new EnumMap<>(DNSTransportType.class);
        final List<DNSTransportType> attempted = new ArrayList<>();
        final RecordingTransport ddrTransport = new RecordingTransport();

        @Override
        DNSClientTransport newTransportInstance(DNSTransportType type, DNSServerCapabilities caps) {
            attempted.add(type);
            DNSClientTransport transport = transports.get(type);
            return transport != null ? transport : new RecordingTransport();
        }

        @Override
        DNSClientTransport createDdrTransport() {
            return ddrTransport;
        }
    }
}
