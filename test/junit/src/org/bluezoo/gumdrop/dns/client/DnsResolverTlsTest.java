/*
 * DnsResolverTlsTest.java
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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.Test;

import javax.net.ssl.X509TrustManager;

/**
 * {@link DnsResolver#tls(TlsConfig)} reaches the encrypted transports the
 * resolver creates.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsResolverTlsTest {

    private static TcpDnsClientTransport dot(DnsResolver resolver) {
        return (TcpDnsClientTransport) resolver.newTransportInstance(
                DnsTransportType.DOT, null);
    }

    @Test
    public void tlsTrustManagerReachesTheDotTransport() throws Exception {
        X509TrustManager trust = TestCertificates.trustAll();
        DnsResolver resolver = new DnsResolver();
        assertSame(resolver, resolver.tls(new TlsConfig().trustManager(trust)));
        TcpTransportFactory factory = dot(resolver).createTransportFactory();
        assertSame(trust, factory.getTrustManager());
    }

    @Test
    public void withoutTlsTheDotTransportUsesTheDefaults() {
        TcpTransportFactory factory = dot(new DnsResolver()).createTransportFactory();
        assertNull(factory.getTrustManager());
    }

    @Test
    public void tlsIsCopiedWhenSet() throws Exception {
        X509TrustManager first = TestCertificates.trustAll();
        TlsConfig tls = new TlsConfig().trustManager(first);
        DnsResolver resolver = new DnsResolver().tls(tls);
        tls.trustManager(TestCertificates.trustNone());
        assertSame(first, dot(resolver).createTransportFactory().getTrustManager());
    }

    @Test
    public void nullTlsClearsTheSettings() throws Exception {
        DnsResolver resolver = new DnsResolver()
                .tls(new TlsConfig().trustManager(TestCertificates.trustAll()));
        resolver.tls(null);
        assertNull(dot(resolver).createTransportFactory().getTrustManager());
    }
}
