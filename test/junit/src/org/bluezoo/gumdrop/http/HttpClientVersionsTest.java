/*
 * HttpClientVersionsTest.java
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
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpClientProtocolHandler;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The permitted-versions list of {@link HttpClient}: which versions the
 * automatic negotiation may choose, forcing HTTP/3 with a one-element list,
 * and falling back to TCP when a QUIC attempt chosen by discovery fails.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientVersionsTest {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    /** Client with an in-memory TCP endpoint and a scripted QUIC attempt. */
    private static final class TestClient extends HttpClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        int tcpConnects;
        int quicAttempts;
        IOException quicFailure;
        Runnable quicDeadline;
        long deadlineMs = -1;

        TestClient(String host, int port) {
            super(host, port);
        }

        @Override
        void connectEndpointForTesting(HttpClientProtocolHandler ph) throws IOException {
            tcpConnects++;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }

        @Override
        QuicEngine openQuicForTesting(InetAddress target, int targetPort,
                QuicEngine.ConnectionAcceptedHandler accepted,
                QuicEngine.EarlyDataHandler early, SelectorLoop loop,
                String serverName) throws IOException {
            quicAttempts++;
            if (quicFailure != null) {
                throw quicFailure;
            }
            return null;
        }

        @Override
        TimerHandle scheduleQuicDeadlineForTesting(SelectorLoop loop, long delayMs,
                Runnable task) {
            deadlineMs = delayMs;
            quicDeadline = task;
            return null;
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

    private static final class StubResolver extends DnsResolver {
        InetAddress answer;
        boolean httpsFails = true;
        DnsResourceRecord httpsRecord;
        int resolves;
        int queries;

        @Override
        public void resolve(String hostname, ResolveCallback callback) {
            resolves++;
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
            callback.onResponse(new DnsMessage(1, 0x8180, new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(), new ArrayList<DnsResourceRecord>()));
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

    private static TestClient advertisingH3(StubResolver resolver) throws Exception {
        resolver.httpsFails = false;
        resolver.answer = InetAddress.getByName("8.8.8.8");
        resolver.httpsRecord = DnsResourceRecord.https("origin.test", 60, 1, ".", alpn("h3"));
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        return client;
    }

    @Test
    public void defaultIsEveryVersionTheClientSpeaks() {
        TestClient client = new TestClient("origin.test", 443);
        assertTrue(client.getVersions().contains(HttpVersion.HTTP_3));
        assertTrue(client.getVersions().contains(HttpVersion.HTTP_2_0));
        assertTrue(client.getVersions().contains(HttpVersion.HTTP_1_1));
        assertEquals(3, client.getVersions().size());
    }

    @Test
    public void versionsIsFluentAndOrderDoesNotMatter() {
        TestClient client = new TestClient("origin.test", 443);
        assertSame(client, client.versions(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2_0));
        assertEquals(2, client.getVersions().size());
        assertTrue(client.getVersions().contains(HttpVersion.HTTP_2_0));
        assertTrue(client.getVersions().contains(HttpVersion.HTTP_1_1));
    }

    @Test
    public void versionsRejectsWhatTheClientCannotSpeak() {
        TestClient client = new TestClient("origin.test", 443);
        HttpVersion[][] bad = {
            new HttpVersion[0],
            new HttpVersion[] {HttpVersion.HTTP_1_0},
            new HttpVersion[] {HttpVersion.UNKNOWN},
            new HttpVersion[] {HttpVersion.HTTP_2_0, null},
        };
        for (int i = 0; i < bad.length; i++) {
            try {
                client.versions(bad[i]);
                fail("accepted " + i);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        try {
            client.versions((HttpVersion[]) null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(3, client.getVersions().size());
    }

    @Test
    public void getVersionsCannotBeUsedToChangeTheClient() {
        TestClient client = new TestClient("origin.test", 443);
        try {
            client.getVersions().clear();
            fail();
        } catch (UnsupportedOperationException expected) {
            assertEquals(3, client.getVersions().size());
        }
    }

    @Test
    public void onlyHttp3ForcesQuicWithoutDiscoveryOrFallback() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = InetAddress.getByName("8.8.8.8");
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.versions(HttpVersion.HTTP_3);
        client.quicFailure = new IOException("no route");
        Events events = new Events();
        client.connect(null, events);
        assertEquals(0, resolver.queries);
        assertEquals(1, resolver.resolves);
        assertEquals(1, client.quicAttempts);
        assertEquals(0, client.tcpConnects);
        assertSame(client.quicFailure, events.error);
        assertNull(client.quicDeadline);
    }

    @Test
    public void withoutHttp3NeitherTheHttpsRecordNorAltSvcCanChooseQuic() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        client.versions(HttpVersion.HTTP_2_0, HttpVersion.HTTP_1_1);
        client.connect(null, new Events());
        assertEquals(0, client.quicAttempts);
        assertEquals(1, client.tcpConnects);
    }

    @Test
    public void withoutHttp3TheHttpsRecordIsStillReadForItsOtherParameters() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        client.versions(HttpVersion.HTTP_1_1);
        client.connect(null, new Events());
        assertEquals(1, resolver.queries);
        assertEquals(1, client.tcpConnects);
    }

    @Test
    public void discoveredQuicFailureFallsBackToTcp() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        client.quicFailure = new IOException("no route");
        Events events = new Events();
        client.connect(null, events);
        assertEquals(1, client.quicAttempts);
        assertEquals(1, client.tcpConnects);
        assertNull(events.error);
        assertTrue(events.calls.contains("connected"));
    }

    @Test
    public void discoveredQuicFailureWithNoTcpVersionIsAnError() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        client.versions(HttpVersion.HTTP_3);
        client.quicFailure = new IOException("no route");
        Events events = new Events();
        client.connect(null, events);
        assertEquals(0, client.tcpConnects);
        assertSame(client.quicFailure, events.error);
    }

    @Test
    public void quicHandshakeDeadlineFallsBackToTcp() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        client.quicHandshakeTimeoutMs(1234L);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(1, client.quicAttempts);
        assertEquals(0, client.tcpConnects);
        assertEquals(1234L, client.deadlineMs);
        assertNotNull(client.quicDeadline);

        client.quicDeadline.run();
        assertEquals(1, client.tcpConnects);
        assertNull(events.error);
    }

    @Test
    public void zeroTimeoutDisablesTheDeadline() throws Exception {
        StubResolver resolver = new StubResolver();
        TestClient client = advertisingH3(resolver);
        client.quicHandshakeTimeoutMs(0L);
        client.connect(null, new Events());
        assertEquals(1, client.quicAttempts);
        assertNull(client.quicDeadline);
    }

    @Test
    public void forcedHttp3ReportsAnErrorWhenTheHandshakeDeadlinePasses() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = InetAddress.getByName("8.8.8.8");
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.versions(HttpVersion.HTTP_3);
        client.quicHandshakeTimeoutMs(2500L);
        Events events = new Events();
        client.connect(null, events);
        assertEquals(1, client.quicAttempts);
        assertEquals(2500L, client.deadlineMs);
        assertNull(events.error);

        client.quicDeadline.run();
        assertEquals(0, client.tcpConnects);
        assertNotNull(events.error);
        assertTrue(events.error.getMessage().contains("QUIC handshake"));
    }

    @Test
    public void forcedHttp3WithZeroTimeoutHasNoDeadline() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = InetAddress.getByName("8.8.8.8");
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.versions(HttpVersion.HTTP_3);
        client.quicHandshakeTimeoutMs(0L);
        client.connect(null, new Events());
        assertEquals(1, client.quicAttempts);
        assertNull(client.quicDeadline);
    }

    @Test
    public void fallbackConnectsToTheOriginNotTheAlternativeHost() throws Exception {
        StubResolver resolver = new StubResolver();
        resolver.answer = InetAddress.getByName("8.8.8.8");
        AltSvcCache.put("origin.test", 443, "alt.test", 8443, 60L);
        TestClient client = new TestClient("origin.test", 443);
        client.selectorLoop(new InlineSelectorLoop());
        client.dnsResolver(resolver);
        client.dnsHttpsRecordEnabled(false);
        client.quicFailure = new IOException("no route");
        client.connect(null, new Events());
        assertEquals(1, client.quicAttempts);
        assertEquals(1, client.tcpConnects);
    }
}
