/*
 * ConnectIpClientQuicFallbackTest.java
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
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A CONNECT-IP HTTP/3 attempt chosen by discovery falls back to TCP when it
 * fails before it is established, and reports the error otherwise.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpClientQuicFallbackTest {

    /** Internal HTTP/3 client whose attempt is scripted. */
    private static final class ScriptedH3 extends HttpClient {
        boolean commitFirst;
        Exception failure = new IOException("QUIC refused");
        int connects;
        int closes;
        long timeoutMs = -1;
        DnsResolver resolver;

        ScriptedH3() {
            super("origin.test", 443);
        }

        @Override
        public void connect(Gumdrop gumdrop, HttpClientHandler handler) {
            connects++;
            if (commitFirst) {
                handler.onConnected(null);
            }
            handler.onError(failure);
        }

        @Override
        public HttpClient dnsResolver(DnsResolver dnsResolver) {
            resolver = dnsResolver;
            return super.dnsResolver(dnsResolver);
        }

        @Override
        public HttpClient quicHandshakeTimeoutMs(long ms) {
            timeoutMs = ms;
            return super.quicHandshakeTimeoutMs(ms);
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class Client extends ConnectIpClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        final ScriptedH3 h3 = new ScriptedH3();
        int tcpConnects;

        Client() {
            super("origin.test", 443);
            dnsHttpsRecordEnabled(false);
        }

        @Override
        HttpClient createH3ClientForTesting() {
            return h3;
        }

        @Override
        void connectEndpointForTesting(ConnectIpClientProtocolHandler ph) throws IOException {
            tcpConnects++;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }
    }

    private static final class Events implements ConnectIpEventHandler {
        final List<Throwable> errors = new ArrayList<Throwable>();

        public void opened(ConnectIpClientSession s) {
        }

        public void packetReceived(ByteBuffer packet) {
        }

        public void addressAssigned(List<ConnectIpAddress> assignments) {
        }

        public void routeAdvertised(List<ConnectIpRoute> routes) {
        }

        public void closed() {
        }

        public void error(Throwable cause) {
            errors.add(cause);
        }
    }

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    @Test
    public void discoveredHttp3FailureFallsBackToTcp() {
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        Client client = new Client();
        Events events = new Events();
        client.connect(null, "*", "*", events);
        assertEquals(1, client.h3.connects);
        assertEquals(1, client.h3.closes);
        assertEquals(1, client.tcpConnects);
        assertTrue(events.errors.isEmpty());
    }

    @Test
    public void onlyHttp3IsNotFallenBackFromAndReportsTheError() {
        Client client = new Client();
        client.versions(HttpVersion.HTTP_3);
        Events events = new Events();
        client.connect(null, "*", "*", events);
        assertEquals(1, client.h3.connects);
        assertEquals(0, client.tcpConnects);
        assertEquals(1, events.errors.size());
        assertSame(client.h3.failure, events.errors.get(0));
    }

    @Test
    public void failureAfterTheAttemptIsEstablishedIsReported() {
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        Client client = new Client();
        client.h3.commitFirst = true;
        Events events = new Events();
        client.connect(null, "*", "*", events);
        assertEquals(0, client.tcpConnects);
        assertEquals(1, events.errors.size());
    }

    @Test
    public void handshakeTimeoutIsPassedToTheInternalClient() {
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        Client client = new Client();
        assertSame(client, client.quicHandshakeTimeoutMs(1500L));
        client.connect(null, "*", "*", new Events());
        assertEquals(1500L, client.h3.timeoutMs);
    }

    @Test
    public void configuredResolverIsUsedForDiscoveryAndPassedToTheInternalClient() {
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        Client client = new Client();
        DnsResolver resolver = new DnsResolver();
        assertSame(client, client.dnsResolver(resolver));
        assertSame(resolver, client.getDnsResolver());
        assertSame(resolver, client.resolverFor(new InlineSelectorLoop()));
        client.connect(null, "*", "*", new Events());
        assertSame(resolver, client.h3.resolver);
    }

    @Test
    public void defaultResolverIsNotPassedToTheInternalClient() {
        AltSvcCache.put("origin.test", 443, null, 8443, 60L);
        Client client = new Client();
        assertNull(client.getDnsResolver());
        client.connect(null, "*", "*", new Events());
        assertNull(client.h3.resolver);
    }
}
