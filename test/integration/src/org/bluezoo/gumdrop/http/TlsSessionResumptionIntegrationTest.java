/*
 * TlsSessionResumptionIntegrationTest.java
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
import org.bluezoo.gumdrop.IntegrationTestHosts;
import org.bluezoo.gumdrop.ListenerBindCheck;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TestTlsFiles;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import static org.junit.Assert.assertEquals;

/**
 * A TLS client that comes back to a TCP listener resumes its session
 * instead of repeating the full handshake, whose certificate signature is
 * most of the cost of a new connection. The client here is the JDK's, so
 * this also checks that the tickets Gumdrop issues, and the way it verifies
 * them, are those another implementation uses.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsSessionResumptionIntegrationTest {

    private Gumdrop gumdrop;
    private final List<String> sessions = Collections.synchronizedList(new ArrayList<String>());

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
    public void testTls13ClientResumesItsSession() throws Exception {
        connectTwice("TLSv1.3");
        assertEquals("[TLSv1.3 full, TLSv1.3 resumed]", sessions.toString());
    }

    @Test
    public void testTls12ClientResumesItsSession() throws Exception {
        connectTwice("TLSv1.2");
        assertEquals("[TLSv1.2 full, TLSv1.2 resumed]", sessions.toString());
    }

    private void connectTwice(String protocol) throws Exception {
        Http2Listener listener = new Http2Listener()
                .port(0)
                .addresses(IntegrationTestHosts.loopbackAddress())
                .secure(true)
                .tls(TestTlsFiles.serverTlsConfig());
        final List<String> seen = sessions;
        HttpServer server = HttpServer.compose()
                .listener(listener)
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(final HttpResponse response) {
                        return new DefaultHttpRequestHandler() {
                            @Override
                            public void endHeaders() {
                                SecurityInfo info = response.getSecurityInfo();
                                seen.add(info.getProtocol() + (info.isSessionResumed() ? " resumed" : " full"));
                                response.status(200);
                                response.longHeader("Content-Length", 2L);
                                response.bodyContent(ByteBuffer.wrap(new byte[] { 'o', 'k' }));
                                response.endMessage();
                            }
                        };
                    }
                })
                .server();
        gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);
        ListenerBindCheck.assertBound(gumdrop);
        int port = listener.getPort();

        // one context for both connections: it holds the client's session cache
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { TestTlsFiles.trustManager() }, new SecureRandom());
        for (int i = 0; i < 2; i++) {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(IntegrationTestHosts.LOOPBACK, port), 5000);
            plain.setSoTimeout(10000);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(
                    plain, TestTlsFiles.SERVER_NAME, port, true);
            try {
                socket.setEnabledProtocols(new String[] { protocol });
                socket.startHandshake();
                OutputStream out = socket.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: " + TestTlsFiles.SERVER_NAME
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // read to the end: the ticket follows the handshake and is
                // taken in while the response is read
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                StringBuilder response = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) {
                    response.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                }
                assertEquals("HTTP/1.1 200", response.substring(0, 12));
            } finally {
                socket.close();
            }
        }
    }
}
