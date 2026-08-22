/*
 * AdGuardDnsClientIntegrationTest.java
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

package org.bluezoo.gumdrop.dns.adguard;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.http.doh.DoHClientTransport;
import org.bluezoo.gumdrop.dns.client.DoQClientTransport;
import org.bluezoo.gumdrop.dns.client.TCPDNSClientTransport;
import org.bluezoo.gumdrop.dns.client.UDPDNSClientTransport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end tests of gumdrop's DNS client against a real, locally-running
 * AdGuard Home instance, covering every transport it supports: plain UDP,
 * plain TCP, DoT, DoH, and DoQ -- not run in CI, see {@link
 * AdGuardTestSupport}.
 *
 * <p>Same rationale as every other suite in this session: an independent
 * implementation on the other end of the wire catches bugs a
 * same-lineage fake server can't. Writing the DoH and DoQ cases surfaced
 * a real gap -- neither {@code DoHClientTransport} nor {@code
 * DoQClientTransport} exposed any way to configure TLS trust (no
 * {@code setTrustManager}/{@code setSSLContext}/{@code
 * setPinnedCertFingerprint} equivalent), even though the transport
 * layers underneath both already supported it. Without that, gumdrop's
 * DNS client could never be used against any DoH/DoQ server whose
 * certificate isn't from a public CA -- including this very test
 * server. Fixed by adding the missing passthroughs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AdGuardDnsClientIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    private DNSResolver resolver;

    @Before
    public void checkReachable() {
        assumeTrue(AdGuardTestSupport.NOT_REACHABLE_MESSAGE, AdGuardTestSupport.isReachable());
    }

    @After
    public void tearDown() {
        if (resolver != null) {
            resolver.close();
        }
    }

    private SelectorLoop loop() {
        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.start();
        return gumdrop.nextWorkerLoop();
    }

    private void assertResolvesToTestAnswer(DNSResolver resolver) throws Exception {
        this.resolver = resolver;
        resolver.open();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<String> resolvedAddress = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        resolver.queryA(AdGuardTestSupport.TEST_HOSTNAME, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                for (DNSResourceRecord record : response.getAnswers()) {
                    InetAddress addr = record.getAddress();
                    if (addr != null) {
                        resolvedAddress.set(addr.getHostAddress());
                        doneLatch.countDown();
                        return;
                    }
                }
                error.set("no A record in response: " + response);
                doneLatch.countDown();
            }

            @Override
            public void onError(String errorMessage) {
                error.set(errorMessage);
                doneLatch.countDown();
            }
        });

        assertTrue("did not complete within timeout", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals(AdGuardTestSupport.TEST_ANSWER, resolvedAddress.get());
    }

    // ── Plain UDP (the resolver's default transport) ──

    @Test
    public void testPlainUdpResolves() throws Exception {
        DNSResolver resolver = new DNSResolver();
        resolver.setSelectorLoop(loop());
        resolver.setTransport(new UDPDNSClientTransport());
        resolver.addServer(InetAddress.getByName(AdGuardTestSupport.HOST), AdGuardTestSupport.PLAIN_PORT);
        assertResolvesToTestAnswer(resolver);
    }

    // ── Plain TCP ──

    @Test
    public void testPlainTcpResolves() throws Exception {
        DNSResolver resolver = new DNSResolver();
        resolver.setSelectorLoop(loop());
        resolver.setTransport(new TCPDNSClientTransport());
        resolver.addServer(InetAddress.getByName(AdGuardTestSupport.HOST), AdGuardTestSupport.PLAIN_PORT);
        assertResolvesToTestAnswer(resolver);
    }

    // ── DoT (TCP + TLS), SPKI-pinned per RFC 7858's Strict usage profile ──

    @Test
    public void testDotResolves() throws Exception {
        X509Certificate serverCert = AdGuardTestSupport.loadServerCertificate();
        String pin = AdGuardTestSupport.computeSpkiSha256Pin(serverCert);

        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        transport.setSecure(true);
        transport.setPinnedSPKIFingerprints(Collections.singleton(pin));

        DNSResolver resolver = new DNSResolver();
        resolver.setSelectorLoop(loop());
        resolver.setTransport(transport);
        resolver.addServer(InetAddress.getByName(AdGuardTestSupport.HOST), AdGuardTestSupport.DOT_DOQ_PORT);
        assertResolvesToTestAnswer(resolver);
    }

    // ── DoH ──

    @Test
    public void testDohResolves() throws Exception {
        X509Certificate serverCert = AdGuardTestSupport.loadServerCertificate();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[] { pinningTrustManager(serverCert) }, null);

        DoHClientTransport transport = new DoHClientTransport();
        transport.setSSLContext(sslContext);

        DNSResolver resolver = new DNSResolver();
        resolver.setSelectorLoop(loop());
        resolver.setTransport(transport);
        resolver.addServer(InetAddress.getByName(AdGuardTestSupport.HOST), AdGuardTestSupport.DOH_PORT);
        assertResolvesToTestAnswer(resolver);
    }

    // ── DoQ ──

    @Test
    public void testDoqResolves() throws Exception {
        Path caFile = AdGuardTestSupport.writeServerCertificatePemFile();

        DoQClientTransport transport = new DoQClientTransport();
        transport.setCaFile(caFile);

        DNSResolver resolver = new DNSResolver();
        resolver.setSelectorLoop(loop());
        resolver.setTransport(transport);
        resolver.addServer(InetAddress.getByName(AdGuardTestSupport.HOST), AdGuardTestSupport.DOT_DOQ_PORT);
        assertResolvesToTestAnswer(resolver);
    }

    private X509TrustManager pinningTrustManager(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("dns-test", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("no X509TrustManager produced for the pinned dns-test cert");
    }
}
