/*
 * HTTP3AutoNegotiationIntegrationTest.java
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

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TestCertificateManager;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.h3.HTTP3Listener;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.*;

/**
 * Proves the second tier of {@link HTTPClient}'s automatic transport
 * negotiation (a cached {@link AltSvcCache} h3 discovery) actually drives a
 * real connection to the production HTTP/3 stack -- not merely that the
 * cache/parsing logic is correct in isolation.
 *
 * <p>The first tier (a DNS HTTPS record) is exercised only at the unit
 * level ({@code DNSResourceRecordTest}, the {@code discoverAndConnect}
 * branch itself is a straightforward continuation of the same
 * already-proven {@code resolveAndConnectH3} call this test also drives),
 * since standing up a real/stub authoritative DNS server for one more end
 * -to-end path is disproportionate to what it would additionally prove.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP3AutoNegotiationIntegrationTest {

    private static final int H3_PORT = 18448;
    // The client is constructed against this port (no listener here at
    // all) -- if the AltSvcCache tier didn't fire, connecting would fail
    // outright rather than quietly falling back, making this a strict test.
    private static final int ORIGIN_PORT = 18449;
    private static final String TEST_HOST = "localhost";
    private static final int ASYNC_TIMEOUT_SECONDS = 8;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(ASYNC_TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private static Gumdrop gumdrop;
    private static HTTP3Listener listener;

    @BeforeClass
    public static void startServer() throws Exception {
        File certsDir = new File("test/integration/certs");
        if (!certsDir.exists()) {
            certsDir.mkdirs();
        }
        File caKeystore = new File(certsDir, "ca-keystore.p12");
        if (caKeystore.exists()) {
            caKeystore.delete();
        }
        TestCertificateManager certManager = new TestCertificateManager(certsDir);
        certManager.generateCA("Test CA", 365);
        certManager.generateServerCertificate("localhost", 365);
        File pemCert = new File(certsDir, "auto-negotiation-server-chain.pem");
        File pemKey = new File(certsDir, "auto-negotiation-server-key.pem");
        certManager.saveServerPem(pemCert, pemKey);

        System.setProperty("gumdrop.workers", "2");

        listener = new HTTP3Listener();
        listener.setPort(H3_PORT);
        listener.setAddresses(TEST_HOST);
        listener.setCertFile(pemCert.getAbsolutePath());
        listener.setKeyFile(pemKey.getAbsolutePath());
        listener.setHandlerFactory(new EchoHandlerFactory());

        gumdrop = Gumdrop.getInstance();
        gumdrop.addListener(listener);
        gumdrop.start();

        Thread.sleep(1000);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        if (gumdrop != null) {
            gumdrop.shutdown();
            try {
                gumdrop.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gumdrop = null;
        }
    }

    @Test
    public void testCachedAltSvcDrivesAutomaticH3Connection() throws Exception {
        // Simulate a prior connection's Alt-Svc discovery: this origin
        // (TEST_HOST:ORIGIN_PORT) advertises h3 on H3_PORT.
        AltSvcCache.put(TEST_HOST, ORIGIN_PORT, null, H3_PORT, 3600);
        try {
            HTTPClient client = new HTTPClient(TEST_HOST, ORIGIN_PORT);
            // Neither setH3Enabled(true) nor any manual transport choice --
            // this is exactly the "just connect" application code path.
            client.setVerifyPeer(false);

            final CountDownLatch connected = new CountDownLatch(1);
            final AtomicReference<Exception> error = new AtomicReference<>();
            client.connect(new HTTPClientHandler() {
                @Override
                public void onConnected(Endpoint endpoint) {
                }

                @Override
                public void onSecurityEstablished(SecurityInfo info) {
                    connected.countDown();
                }

                @Override
                public void onError(Exception cause) {
                    error.set(cause);
                    connected.countDown();
                }

                @Override
                public void onDisconnected() {
                }
            });

            assertTrue("Connection driven by cached Alt-Svc discovery timed out",
                    connected.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Connection failed: " + error.get(), error.get());
            assertEquals("A cached h3 Alt-Svc entry should have driven an "
                    + "automatic HTTP/3 connection with no explicit setH3Enabled(true)",
                    HTTPVersion.HTTP_3, client.getVersion());

            client.close();
        } finally {
            AltSvcCache.clear();
        }
    }
}
