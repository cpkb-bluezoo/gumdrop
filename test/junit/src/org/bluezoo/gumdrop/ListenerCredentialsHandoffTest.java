/*
 * ListenerCredentialsHandoffTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.util.ArrayList;

import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

/**
 * What a {@link Listener} hands to each kind of transport factory when it
 * holds ready-made server credentials, and the bookkeeping that depends on
 * telemetry, rate limiting and non-IP peers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ListenerCredentialsHandoffTest {

    private static final class Plain extends Listener {
        @Override
        public String getDescription() {
            return "plain";
        }

        boolean metrics() {
            return isMetricsEnabled();
        }
    }

    @Test
    public void credentialsReachTheTcpFactory() throws Exception {
        ServerCredentials creds = TestCertificates.ec256().credentials();
        Plain l = new Plain();
        l.setServerCredentials(creds);
        assertSame(creds, l.getServerCredentials());
        TcpTransportFactory f = new TcpTransportFactory();
        l.configureTransportFactory(f);
        assertSame(creds, f.getServerCredentials());
    }

    @Test
    public void credentialsReachTheUdpAndQuicFactories() throws Exception {
        ServerCredentials creds = TestCertificates.ec256().credentials();
        Plain l = new Plain();
        l.setServerCredentials(creds);
        UdpTransportFactory udp = new UdpTransportFactory();
        l.configureTransportFactory(udp);
        udp.setSecure(true);
        udp.start();
        assertNotNull(udp.getSharedServerConfig());
        QuicTransportFactory quic = new QuicTransportFactory();
        l.configureTransportFactory(quic);
        assertFalse(quic.isSecure());
    }

    @Test
    public void telemetryEnablesMetricsOnlyWhenAskedFor() {
        Plain l = new Plain();
        TelemetryConfig telemetry = new TelemetryConfig();
        telemetry.metricsEnabled(false);
        Gumdrop g = TestGumdrop.create();
        g.telemetryConfig(telemetry);
        l.start(g);
        assertFalse(l.metrics());
        telemetry.metricsEnabled(true);
        assertTrue(l.metrics());
        assertSame(telemetry, l.getTelemetryConfig());
        TcpTransportFactory f = new TcpTransportFactory();
        l.configureTransportFactory(f);
        assertSame(telemetry, f.getTelemetryConfig());
    }

    @Test
    public void rateLimitedListenerTracksOnlyInetPeers() throws Exception {
        Plain l = new Plain();
        l.setRateLimit("5/60s");
        SocketAddress inet = new InetSocketAddress(InetAddress.getByName("10.1.1.1"), 99);
        SocketAddress unix = UnixDomainSocketAddress.of("/tmp/never-created.sock");
        l.connectionOpened(inet);
        l.connectionOpened(unix);
        assertEquals(2, l.getActiveConnectionCount());
        l.connectionClosed(unix);
        l.connectionClosed(inet);
        assertEquals(0, l.getActiveConnectionCount());
        l.connectionClosed(inet);
        assertEquals(0, l.getActiveConnectionCount());
    }

    @Test
    public void emptyAllowedNetworkListMeansEveryoneNotBlocked() throws Exception {
        Plain l = new Plain();
        l.setAllowedNetworks(new ArrayList<CidrNetwork>());
        assertTrue(l.acceptConnection(new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 1)));
    }
}
