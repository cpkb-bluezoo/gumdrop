/*
 * KeystoreTlsIntegrationTest.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * Loopback HTTPS using Java keystores (PKCS#12 and JKS), not PEM paths on the
 * listener. PEM fixtures in {@code etc/tls/} remain the source of truth;
 * {@code ant tls-keystore} builds {@code keystore.p12} from them. The client
 * trusts {@code ca.pem} only (see {@link TestTlsFiles}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class KeystoreTlsIntegrationTest {

    private static final int PORT_PKCS12 = 18447;
    private static final int PORT_JKS = 18448;

    private Gumdrop gumdrop;

    @BeforeClass
    public static void requirePemFixtures() {
        TestTlsFiles.assumeAvailable();
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
            gumdrop.join();
        }
        gumdrop = null;
    }

    @Test
    public void testHttpsWithPkcs12Keystore() throws Exception {
        TestTlsFiles.assumeKeystoreAvailable();
        runOneGet(TlsConfig.keystore(TestTlsFiles.keystoreFile(), TestTlsFiles.KEYSTORE_PASSWORD),
                PORT_PKCS12);
    }

    @Test
    public void testHttpsWithJksKeystore() throws Exception {
        Path jks = TestTlsFiles.writeTemporaryJksServerKeystore();
        try {
            runOneGet(TlsConfig.keystore(jks, TestTlsFiles.KEYSTORE_PASSWORD, "JKS"), PORT_JKS);
        } finally {
            Files.deleteIfExists(jks);
        }
    }

    private void runOneGet(TlsConfig serverTls, int port) throws Exception {
        HttpServer server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(port)
                        .addresses(InetAddress.getByName("::1"))
                        .secure(true)
                        .tls(serverTls))
                .server();

        gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);

        String request = "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        HTTPClientHelper.HttpResponse response =
                HTTPClientHelper.sendRequest("::1", port, request, true, 10000);
        assertEquals(404, response.statusCode);
    }
}
