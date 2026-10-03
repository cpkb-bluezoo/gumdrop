/*
 * HttpClientFacadeTest.java
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

package org.bluezoo.gumdrop.http;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpointPool;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.http.client.ConnectIpClientSession;
import org.bluezoo.gumdrop.http.client.ConnectIpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpSession;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpClientProtocolHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Connection set-up, transport negotiation, SSRF protection, pooling,
 * Alt-Svc handling and the HTTP/3-only operations of {@link HttpClient},
 * driven through an in-memory endpoint and a stub DNS resolver.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientFacadeTest {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    /** Client whose endpoint is an in-memory recorder. */
    private static final class TestClient extends HttpClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpClientProtocolHandler handler;
        int connects;
        IOException failure;

        TestClient(String host, int port) {
            super(host, port);
        }

        TestClient(InetAddress address, int port) {
            super(address, port);
        }

        TestClient(String socketPath) {
            super(socketPath);
        }

        @Override
        void connectEndpointForTesting(HttpClientProtocolHandler ph) throws IOException {
            connects++;
            if (failure != null) {
                throw failure;
            }
            handler = ph;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }
    }

    private static final class Events implements HttpClientHandler {
        final List<String> calls = new ArrayList<String>();
        Exception error;

        @Override
        public void onConnected(Endpoint endpoint) {
            calls.add("connected");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            calls.add("secure");
        }

        @Override
        public void onError(Exception cause) {
            calls.add("error");
            error = cause;
        }

        @Override
        public void onDisconnected() {
            calls.add("disconnected");
        }
    }

    /** Resolver answering from canned outcomes, never touching the network. */
    private static final class StubResolver extends DnsResolver {
        InetAddress answer;
        boolean resolveFails;
        boolean httpsFails = true;
        DnsResourceRecord httpsRecord;
        int resolves;
        int queries;

        @Override
        public void resolve(String hostname, ResolveCallback callback) {
            resolves++;
            if (resolveFails) {
                callback.onError("NXDOMAIN");
                return;
            }
            List<InetAddress> list = new ArrayList<InetAddress>();
            list.add(answer);
            callback.onResolved(list);
        }

        @Override
        public void queryHTTPS(String name, DnsQueryCallback callback) {
            queries++;
            if (httpsFails) {
                callback.onError("SERVFAIL");
                return;
            }
            List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
            if (httpsRecord != null) {
                answers.add(httpsRecord);
            }
            DnsMessage msg = new DnsMessage(1, 0x8180, new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(), new ArrayList<DnsResourceRecord>());
            callback.onResponse(msg);
        }
    }

    private static java.util.Map<Integer, byte[]> alpn(String protocol) {
        java.util.Map<Integer, byte[]> params = new java.util.HashMap<Integer, byte[]>();
        List<String> protocols = new ArrayList<String>();
        protocols.add(protocol);
        params.put(Integer.valueOf(DnsResourceRecord.SVCB_PARAM_ALPN),
                DnsResourceRecord.encodeSVCBAlpn(protocols));
        return params;
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    // ---- plain TCP connect and request flow ----

    @Test
    public void plainConnectSendsRequestAndCloses() {
        TestClient client = new TestClient("example.test", 80);
        client.setDnsHttpsRecordEnabled(false);
        client.credentials("user", "pw");
        client.setIdleTimeoutMs(5000L);
        client.setTrace(null);
        Events events = new Events();
        assertNull(client.getVersion());
        assertFalse(client.isOpen());
        client.connect(null, events);
        assertEquals(1, client.connects);
        assertTrue(events.calls.contains("connected"));
        assertTrue(client.isOpen());
        client.setTrace(null);
        HttpRequest request = client.get("/hello", new DefaultHttpResponseHandler());
        assertNotNull(request);
        request.endMessage();
        String wire = new String(client.endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.startsWith("GET /hello HTTP/1.1"));
        client.close();
        assertFalse(client.isOpen());
    }

    @Test
    public void requestFactoryMethodsUseTheirVerbs() {
        TestClient client = new TestClient("example.test", 80);
        client.setDnsHttpsRecordEnabled(false);
        client.connect(null, new Events());
        assertNotNull(client.post("/p", null));
        assertNotNull(client.put("/p", null));
        assertNotNull(client.delete("/p", null));
        assertNotNull(client.head("/p", null));
        assertNotNull(client.options("/p", null));
        assertNotNull(client.patch("/p", null));
        assertNotNull(client.request(HttpMethod.of("PROPFIND"), "/p", null));
    }

    @Test
    public void secureH2ClientAdvertisesAlpnAndPriorKnowledgeIsApplied() {
        TestClient secure = new TestClient("example.test", 443);
        secure.setDnsHttpsRecordEnabled(false);
        secure.setSecure(true);
        secure.setH2Enabled(true);
        secure.connect(null, new Events());
        assertEquals(1, secure.connects);

        TestClient prior = new TestClient("example.test", 80);
        prior.setDnsHttpsRecordEnabled(false);
        prior.setH2WithPriorKnowledge(true);
        prior.setH2cUpgradeEnabled(false);
        prior.setAltSvcEnabled(false);
        prior.connect(null, new Events());
        assertEquals(1, prior.connects);
        assertTrue(prior.endpoint.getAllBytes().length > 0);
    }

    @Test
    public void unixSocketClientConnectsAndIgnoresAltSvc() {
        TestClient client = new TestClient("/tmp/http.sock");
        client.connect(null, new Events());
        assertEquals(1, client.connects);
        client.altSvcReceived("h3=\":443\"; ma=60");
        assertNull(AltSvcCache.get("localhost", 443));
    }

    @Test
    public void endpointCreationFailureIsReported() {
        TestClient client = new TestClient("example.test", 80);
        client.setDnsHttpsRecordEnabled(false);
        client.failure = new IOException("no route");
        Events events = new Events();
        client.connect(null, events);
        assertSame(client.failure, events.error);
    }

    // ---- SSRF protection ----

    private static Events connectBlocked(String address, boolean h3) throws Exception {
        TestClient client = new TestClient(ip(address), 443);
        client.setBlockPrivateAddresses(true);
        client.setH3Enabled(h3);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(0, client.connects);
        return events;
    }

    @Test
    public void privateAddressesAreBlocked() throws Exception {
        String[] blocked = {"127.0.0.1", "169.254.169.254", "10.1.2.3", "192.168.1.1", "0.0.0.0",
            "224.0.0.1", "::1", "fe80::1", "fd00:ec2::254", "fc00::1"};
        for (int i = 0; i < blocked.length; i++) {
            Events tcp = connectBlocked(blocked[i], false);
            assertTrue(blocked[i], tcp.error != null && tcp.error.getMessage().contains("SSRF"));
            Events h3 = connectBlocked(blocked[i], true);
            assertTrue(blocked[i], h3.error != null && h3.error.getMessage().contains("SSRF"));
        }
    }

    @Test
    public void publicAddressIsAllowedWhenBlockingEnabled() throws Exception {
        TestClient client = new TestClient(ip("8.8.8.8"), 80);
        client.setBlockPrivateAddresses(true);
        client.connect(null, new Events());
        assertEquals(1, client.connects);
    }

    @Test
    public void blockingDisabledAllowsLoopback() throws Exception {
        TestClient client = new TestClient(ip("127.0.0.1"), 80);
        client.connect(null, new Events());
        assertEquals(1, client.connects);
    }

    // ---- DNS-driven negotiation ----

    private static TestClient named(StubResolver resolver) {
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.setBlockPrivateAddresses(true);
        return client;
    }

    @Test
    public void h3ForcedResolvesThenFailsOnPrivateAnswer() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = ip("127.0.0.1");
        TestClient client = named(resolver);
        client.setH3Enabled(true);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(1, resolver.resolves);
        assertTrue(events.error.getMessage().contains("SSRF"));
    }

    @Test
    public void h3ForcedReportsDnsFailure() {
        StubResolver resolver = new StubResolver();
        resolver.resolveFails = true;
        TestClient client = named(resolver);
        client.setH3Enabled(true);
        Events events = new Events();
        client.connect(null, events);
        assertTrue(events.error.getMessage().contains("DNS resolution failed for origin.test"));
    }

    @Test
    public void httpsRecordErrorFallsBackToTcp() {
        StubResolver resolver = new StubResolver();
        TestClient client = named(resolver);
        client.connect(null, new Events());
        assertEquals(1, resolver.queries);
        assertEquals(1, client.connects);
    }

    @Test
    public void httpsRecordWithoutH3FallsBackToTcp() {
        StubResolver resolver = new StubResolver();
        resolver.httpsFails = false;
        resolver.httpsRecord = DnsResourceRecord.https("origin.test", 60, 1, ".",
                alpn("h2"));
        TestClient client = named(resolver);
        client.connect(null, new Events());
        assertEquals(1, client.connects);
    }

    @Test
    public void httpsRecordAliasFormIsSkipped() {
        StubResolver resolver = new StubResolver();
        resolver.httpsFails = false;
        resolver.httpsRecord = DnsResourceRecord.https("origin.test", 60, 0, "other.test", null);
        TestClient client = named(resolver);
        client.connect(null, new Events());
        assertEquals(1, client.connects);
    }

    @Test
    public void httpsRecordAdvertisingH3SwitchesToQuicPath() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.httpsFails = false;
        resolver.answer = ip("127.0.0.1");
        java.util.Map<Integer, byte[]> params = alpn("h3");
        params.put(Integer.valueOf(DnsResourceRecord.SVCB_PARAM_PORT),
                DnsResourceRecord.encodeSVCBPort(8443));
        resolver.httpsRecord = DnsResourceRecord.https("origin.test", 60, 1, ".", params);
        TestClient client = named(resolver);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(0, client.connects);
        assertTrue(events.error.getMessage().contains("SSRF"));
    }

    @Test
    public void cachedAltSvcRoutesToQuicPath() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = ip("127.0.0.1");
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        TestClient client = named(resolver);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(0, client.connects);
        assertTrue(events.error.getMessage().contains("SSRF"));

        AltSvcCache.clear();
        AltSvcCache.put("origin.test", 443, "alt.test", 8443, 60L);
        TestClient alt = named(resolver);
        Events altEvents = new Events();
        alt.connect(null, altEvents);
        assertTrue(altEvents.error.getMessage().contains("SSRF"));
    }

    @Test
    public void undiscoverableHostsSkipTheDnsRound() {
        StubResolver resolver = new StubResolver();
        TestClient client = new TestClient("localhost", 80);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.connect(null, new Events());
        assertEquals(0, resolver.queries);
        assertEquals(1, client.connects);
        assertSame(resolver, client.getDnsResolver());
    }

    // ---- Alt-Svc ----

    @Test
    public void altSvcWithoutConnectionOnlyPopulatesTheCache() {
        TestClient client = new TestClient("origin.test", 443);
        client.altSvcReceived("garbage");
        assertNull(AltSvcCache.get("origin.test", 443));
        client.altSvcReceived("h3=\":8443\"; ma=60");
        assertNotNull(AltSvcCache.get("origin.test", 443));
    }

    @Test
    public void altSvcOnLiveConnectionStartsOneUpgrade() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = ip("127.0.0.1");
        TestClient client = named(resolver);
        client.setDnsHttpsRecordEnabled(false);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(1, client.connects);
        client.altSvcReceived("h3=\"alt.test:8443\"; ma=60");
        assertEquals(1, resolver.resolves);
        assertTrue(events.error.getMessage().contains("SSRF"));
        client.altSvcReceived("h3=\"alt.test:8443\"; ma=60");
        assertEquals(1, resolver.resolves);
    }

    @Test
    public void altSvcUpgradeWithoutAltHostResolvesTheOrigin() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.resolveFails = true;
        TestClient client = named(resolver);
        client.setDnsHttpsRecordEnabled(false);
        Events events = new Events();
        client.connect(null, events);
        client.altSvcReceived("h3=\":8443\"; ma=60");
        assertEquals(1, resolver.resolves);
        assertTrue(events.error.getMessage().contains("DNS resolution failed"));
    }

    // ---- pooling ----

    @Test
    public void connectionPoolRegistersAndReleases() throws Exception {
        ClientEndpointPool pool = new ClientEndpointPool();
        try {
            TestClient client = new TestClient(ip("127.0.0.1"), 8080);
            client.setConnectionPool(pool);
            client.selectorLoop(new InlineSelectorLoop());
            Events events = new Events();
            client.connect(null, events);
            assertEquals(1, pool.getTotalEndpointCount());
            assertTrue(events.calls.contains("connected"));
            client.close();
            assertEquals(1, pool.getIdleEndpointCount());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void poolWithUnresolvedTargetRegistersNothing() {
        ClientEndpointPool pool = new ClientEndpointPool();
        try {
            TestClient client = new TestClient("origin.test", 80);
            client.setDnsHttpsRecordEnabled(false);
            client.connectionPool(pool);
            client.connect(null, new Events());
            assertEquals(0, pool.getTotalEndpointCount());
        } finally {
            pool.shutdown();
        }
    }

    // ---- HTTP/3-only operations without an H3 connection ----

    @Test
    public void h3OperationsWithoutH3ReportErrors() {
        HttpClient client = new HttpClient("origin.test", 443);
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        client.connectWebSocket("/ws", null, null, ws);
        final List<String> errors = new ArrayList<String>();
        client.connectUdp("1.2.3.4", 53, new ConnectUdpEventHandler() {
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
                errors.add("udp");
            }
        });
        client.connectIp("*", "*", new ConnectIpEventHandler() {
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
                errors.add("ip");
            }
        });
        assertEquals(2, errors.size());
        assertEquals(1, ws.errors.size());
        assertTrue(ws.errors.get(0) instanceof IllegalStateException);
    }

    // ---- configuration surface ----

    @Test
    public void fluentAndSetterConfigurationRoundTrips() throws Exception {
        TestClient client = new TestClient("example.test", 80);
        TlsConfig other = new TlsConfig();
        assertSame(client, client.importTls(other));
        assertNotNull(client.getTls());
        client.setDnsDiscoveredEchConfigList(new byte[] {1, 2});
        client.setSecure(true);
        client.setH2Enabled(false);
        client.setH2cUpgradeEnabled(false);
        client.setH2WithPriorKnowledge(false);
        client.setH3Enabled(false);
        client.setAltSvcEnabled(false);
        client.setDnsHttpsRecordEnabled(false);
        client.setEarlyDataEnabled(true);
        client.setVerifyPeer(false);
        client.setBlockPrivateAddresses(false);
        client.setIdleTimeoutMs(1L);
        client.setSendAcceptEncodingHeader(false);
        client.setDecodeResponseContentCoding(false);
        client.setEncodeRequestBodyContentCoding(false);
        client.setSendAcceptEncodingHeader(true);
        client.setDecodeResponseContentCoding(true);
        client.setEncodeRequestBodyContentCoding(true);
        client.setKeystorePass("x");
        client.setKeystoreFile(null);
        client.setKeystoreFormat(null);
        client.setCertFile(null);
        client.setKeyFile(null);
        client.setTrustManager(null);
        client.setClientCredentials(null);
        assertSame(client, client.host("h.test").port(8080).secure(true).trustJvm()
                .trace(null).clientCredentials(null).trustManager(null)
                .keystoreFile(null).keystorePass("p").keystoreFormat(null)
                .h2Enabled(true).h2cUpgradeEnabled(true).h2WithPriorKnowledge(false)
                .h3Enabled(false).altSvcEnabled(true).dnsHttpsRecordEnabled(true)
                .earlyDataEnabled(false).certFile(null).keyFile(null).verifyPeer(true)
                .blockPrivateAddresses(false).idleTimeoutMs(0L).sendAcceptEncodingHeader(true)
                .decodeResponseContentCoding(true).encodeRequestBodyContentCoding(true)
                .connectionPool(null).credentials("u", "p"));
        assertSame(client, client.host(ip("127.0.0.1")).socketPath("/tmp/x").selectorLoop(null));
        assertNull(client.getDnsResolver());
    }
}
