/*
 * ClientConnectEchDiscoveryTest.java
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


package org.bluezoo.gumdrop.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.EchConfig;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for DNS HTTPS {@code ech} discovery ahead of an outbound TCP
 * TLS dial ({@link ClientConnect#discoverEch}), and for how a discovered
 * list and a configured file combine in {@link ClientConnect#prepareTls}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientConnectEchDiscoveryTest {

    private static final byte[] PK = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
    private static final int SVCPARAM_ECH = 5;

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] echList(int configId) {
        return EchConfig.encodeList(new EchConfig[] {
                EchConfig.createV13(configId, PK, "public.example", 32) });
    }

    private static final class StubResolver extends DnsResolver {
        final List<String> queried = new ArrayList<String>();
        byte[] echList;
        String error;

        StubResolver() {
            super();
        }

        @Override
        public void queryHTTPS(String name, DnsQueryCallback callback) {
            queried.add(name);
            if (error != null) {
                callback.onError(error);
                return;
            }
            List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
            if (echList != null) {
                Map<Integer, byte[]> params = new HashMap<Integer, byte[]>();
                params.put(SVCPARAM_ECH, echList);
                answers.add(DnsResourceRecord.https(name, 60, 1, ".", params));
            }
            callback.onResponse(new DnsMessage(1, 0x8180, Collections.<DnsQuestion>emptyList(), answers,
                    Collections.<DnsResourceRecord>emptyList(), Collections.<DnsResourceRecord>emptyList()));
        }
    }

    private static final class Capture implements ClientConnect.EchDiscoveryCallback {
        int calls;
        byte[] list;

        @Override
        public void discovered(byte[] echConfigList) {
            calls++;
            list = echConfigList;
        }
    }

    private static ClientDial dial(String host, StubResolver resolver) {
        return ClientDial.withDefaultPort(993).host(host).dnsResolver(resolver);
    }

    private static TlsConfig enabled() {
        return new TlsConfig().clientEchDnsDiscovery(true);
    }

    @Test
    public void discoveryIsOffByDefaultAndNeverQueriesDns() {
        StubResolver resolver = new StubResolver();
        resolver.echList = echList(1);
        Capture capture = new Capture();
        ClientConnect.discoverEch(null, true, dial("mail.example.com", resolver), new TlsConfig(), capture);
        assertEquals(1, capture.calls);
        assertNull(capture.list);
        assertTrue(resolver.queried.isEmpty());
    }

    @Test
    public void enabledSecureDialQueriesTheHostAndReturnsTheEchList() {
        StubResolver resolver = new StubResolver();
        resolver.echList = echList(7);
        Capture capture = new Capture();
        ClientConnect.discoverEch(null, true, dial("mail.example.com", resolver), enabled(), capture);
        assertEquals(1, capture.calls);
        assertEquals(Collections.singletonList("mail.example.com"), resolver.queried);
        assertArrayEquals(echList(7), capture.list);
    }

    @Test
    public void recordWithoutEchOrLookupFailureYieldsNoList() {
        StubResolver resolver = new StubResolver();
        Capture none = new Capture();
        ClientConnect.discoverEch(null, true, dial("mail.example.com", resolver), enabled(), none);
        assertEquals(1, none.calls);
        assertNull(none.list);

        resolver.error = "SERVFAIL";
        Capture failed = new Capture();
        ClientConnect.discoverEch(null, true, dial("mail.example.com", resolver), enabled(), failed);
        assertEquals("the dial must still proceed", 1, failed.calls);
        assertNull(failed.list);
    }

    @Test
    public void cleartextDialsAndUndiscoverableTargetsSkipTheLookup() {
        StubResolver resolver = new StubResolver();
        resolver.echList = echList(1);
        String[] hosts = { "localhost", "127.0.0.1", "::1", "10.1.2.3" };
        for (String host : hosts) {
            Capture capture = new Capture();
            ClientConnect.discoverEch(null, true, dial(host, resolver), enabled(), capture);
            assertEquals(host, 1, capture.calls);
            assertNull(host, capture.list);
        }
        Capture cleartext = new Capture();
        ClientConnect.discoverEch(null, false, dial("mail.example.com", resolver), enabled(), cleartext);
        assertEquals(1, cleartext.calls);
        assertNull(cleartext.list);

        Capture socket = new Capture();
        ClientConnect.discoverEch(null, true,
                ClientDial.withDefaultPort(1).socketPath("/tmp/x.sock").dnsResolver(resolver), enabled(), socket);
        assertEquals(1, socket.calls);
        assertTrue("no DNS query for any of them: " + resolver.queried, resolver.queried.isEmpty());
    }

    @Test
    public void discoveredListReachesTheFactory() throws Exception {
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientConnect.prepareTls(true, enabled(), factory, echList(7));
        assertNotNull(factory.getClientEchConfig());
        assertEquals(7, factory.getClientEchConfig().getConfigId());
    }

    @Test
    public void withoutADiscoveredListTheFactoryHasNoEchConfig() throws Exception {
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientConnect.prepareTls(true, enabled(), factory, null);
        assertNull(factory.getClientEchConfig());
    }

    @Test
    public void dnsListTakesPrecedenceOverTheConfiguredFile() throws Exception {
        Path file = Files.createTempFile("ech-client-list", ".bin");
        file.toFile().deleteOnExit();
        Files.write(file, echList(9));
        TlsConfig tls = enabled().clientEchConfigListFile(file);

        TcpTransportFactory withDns = new TcpTransportFactory();
        ClientConnect.prepareTls(true, tls, withDns, echList(7));
        assertEquals(7, withDns.getClientEchConfig().getConfigId());

        TcpTransportFactory fileOnly = new TcpTransportFactory();
        ClientConnect.prepareTls(true, tls, fileOnly, null);
        assertEquals("the file is the fallback when DNS supplies nothing",
                9, fileOnly.getClientEchConfig().getConfigId());
    }

    @Test
    public void discoverySettingSurvivesDefaultsMergeAndCopy() {
        assertFalse(new TlsConfig().isClientEchDnsDiscoveryEnabled());
        TlsConfig fallback = enabled();
        assertTrue(TlsConfig.effective(new TlsConfig(), fallback).isClientEchDnsDiscoveryEnabled());
        assertTrue(TlsConfig.effective(enabled(), null).isClientEchDnsDiscoveryEnabled());
        assertTrue(new TlsConfig().copyFrom(enabled()).isClientEchDnsDiscoveryEnabled());
    }
}
