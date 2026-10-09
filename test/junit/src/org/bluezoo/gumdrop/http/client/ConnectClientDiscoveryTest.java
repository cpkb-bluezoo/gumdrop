/*
 * ConnectClientDiscoveryTest.java
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


package org.bluezoo.gumdrop.http.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.assertEquals;

/**
 * Transport discovery of {@link ConnectUdpClient} and {@link ConnectIpClient}:
 * the DNS HTTPS record tier with canned answers (errors, empty answers, alias
 * and non-h3 service records, ECH parameters) must always fall back to the
 * TCP transport, and a literal address skips discovery entirely.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectClientDiscoveryTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    /** Resolver mock answering every HTTPS query with canned records or an error. */
    private static final class Resolver extends DnsResolver {
        final boolean fail;
        final List<DnsResourceRecord> answers;
        int queries;

        Resolver(boolean fail, List<DnsResourceRecord> answers) {
            this.fail = fail;
            this.answers = answers;
        }

        @Override
        public void queryHTTPS(String name, DnsQueryCallback callback) {
            queries++;
            if (fail) {
                callback.onError("SERVFAIL");
                return;
            }
            callback.onResponse(new DnsMessage(1, 0x8180, new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(), new ArrayList<DnsResourceRecord>()));
        }
    }

    private static final class UdpClient extends ConnectUdpClient {
        final Resolver resolver;
        int connects;

        UdpClient(String host, Resolver resolver) {
            super(new InlineSelectorLoop(), host, 443);
            this.resolver = resolver;
            dnsHttpsRecordEnabled(true);
        }

        @Override
        DnsResolver resolverFor(SelectorLoop loop) {
            return resolver;
        }

        @Override
        void connectEndpointForTesting(ConnectUdpClientProtocolHandler ph) throws IOException {
            connects++;
        }
    }

    private static final class IpClient extends ConnectIpClient {
        final Resolver resolver;
        int connects;

        IpClient(String host, Resolver resolver) {
            super(new InlineSelectorLoop(), host, 443);
            this.resolver = resolver;
            dnsHttpsRecordEnabled(true);
        }

        @Override
        DnsResolver resolverFor(SelectorLoop loop) {
            return resolver;
        }

        @Override
        void connectEndpointForTesting(ConnectIpClientProtocolHandler ph) throws IOException {
            connects++;
        }
    }

    private static final ConnectUdpEventHandler UDP = new ConnectUdpEventHandler() {
        @Override
        public void opened(ConnectUdpSession session) {
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
        }

        @Override
        public void closed() {
        }

        @Override
        public void error(Throwable cause) {
        }
    };

    private static final ConnectIpEventHandler IP = new ConnectIpEventHandler() {
        @Override
        public void opened(ConnectIpClientSession session) {
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
        }

        @Override
        public void closed() {
        }

        @Override
        public void error(Throwable cause) {
        }
    };

    private static List<DnsResourceRecord> mixedAnswers() throws Exception {
        List<DnsResourceRecord> list = new ArrayList<DnsResourceRecord>();
        list.add(DnsResourceRecord.a("proxy.example", 60, java.net.InetAddress.getLoopbackAddress()));
        list.add(DnsResourceRecord.https("proxy.example", 60, 0, "alias.example", null));
        Map<Integer, byte[]> params = new HashMap<Integer, byte[]>();
        List<String> alpn = new ArrayList<String>();
        alpn.add("h2");
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN, DnsResourceRecord.encodeSVCBAlpn(alpn));
        params.put(DnsResourceRecord.SVCB_PARAM_ECH, new byte[] {1, 2, 3});
        list.add(DnsResourceRecord.https("proxy.example", 60, 1, ".", params));
        return list;
    }

    @Test
    public void udpClientFallsBackToTcpOnDnsErrorEmptyAndNonH3Answers() throws Exception {
        UdpClient failing = new UdpClient("proxy.example", new Resolver(true, null));
        failing.connect(null, "target.example", 53, UDP);
        assertEquals(1, failing.resolver.queries);
        assertEquals(1, failing.connects);
        UdpClient empty = new UdpClient("proxy.example",
                new Resolver(false, new ArrayList<DnsResourceRecord>()));
        empty.connect(null, "target.example", 53, UDP);
        assertEquals(1, empty.connects);
        UdpClient mixed = new UdpClient("proxy.example", new Resolver(false, mixedAnswers()));
        mixed.connect(null, "target.example", 53, UDP);
        assertEquals(1, mixed.resolver.queries);
        assertEquals(1, mixed.connects);
    }

    @Test
    public void ipClientFallsBackToTcpOnDnsErrorEmptyAndNonH3Answers() throws Exception {
        IpClient failing = new IpClient("proxy.example", new Resolver(true, null));
        failing.connect(null, "10.0.0.0/8", "0", IP);
        assertEquals(1, failing.resolver.queries);
        assertEquals(1, failing.connects);
        IpClient empty = new IpClient("proxy.example",
                new Resolver(false, new ArrayList<DnsResourceRecord>()));
        empty.connect(null, "10.0.0.0/8", "0", IP);
        assertEquals(1, empty.connects);
        IpClient mixed = new IpClient("proxy.example", new Resolver(false, mixedAnswers()));
        mixed.connect(null, "10.0.0.0/8", "0", IP);
        assertEquals(1, mixed.resolver.queries);
        assertEquals(1, mixed.connects);
    }

    @Test
    public void undiscoverableHostsSkipTheDnsQuery() {
        UdpClient udp = new UdpClient("localhost", new Resolver(true, null));
        udp.connect(null, "t", 53, UDP);
        assertEquals(0, udp.resolver.queries);
        assertEquals(1, udp.connects);
        IpClient ip = new IpClient("127.0.0.1", new Resolver(true, null));
        ip.connect(null, "10.0.0.0/8", "0", IP);
        assertEquals(0, ip.resolver.queries);
        assertEquals(1, ip.connects);
        BinaryRecordingEndpoint unused = new BinaryRecordingEndpoint();
        assertEquals(0, unused.getAllBytes().length);
    }
}
