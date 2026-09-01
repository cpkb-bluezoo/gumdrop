/*
 * ConnectUdpH1WireTest.java
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

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Wire-level regression test for issue #393: a real {@link
 * HTTPProtocolHandler} parsing a real HTTP/1.1 Upgrade request (RFC 9110
 * section 7.8, RFC 9298 section 3: {@code Upgrade: connect-udp}, {@code
 * Capsule-Protocol: ?1}) must reach {@link ConnectUdpRequestHandler#headers}
 * and, once the target resolves and the policy allows it, get a real
 * {@code 101 Switching Protocols} response back over the wire, then hand
 * the connection's remaining bytes to the stream via {@link
 * HTTPConnectionLike#switchToStreamTunnelMode} -- unlike HTTP/2 (covered by
 * {@link ConnectUdpH2WireTest}), this dispatch path ({@link
 * Stream#acceptConnectUdp}'s HTTP/1.1 branch) is new code not otherwise
 * exercised by any pre-existing test.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpH1WireTest {

    private static final class CountingEndpoint implements Endpoint {
        final java.util.List<byte[]> sent =
                java.util.Collections.synchronizedList(new java.util.ArrayList<byte[]>());
        final CountDownLatch statusLineLatch = new CountDownLatch(1);
        SelectorLoop loop;
        @Override public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sent.add(bytes);
            if (new String(bytes, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 ")) {
                statusLineLatch.countDown();
            }
        }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return loop; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
    }

    private HTTPProtocolHandler connection;
    private CountingEndpoint endpoint;
    private SelectorLoop loop;

    @Before
    public void setUp() {
        loop = new SelectorLoop(0);
        loop.start();

        HTTPListener listener = new HTTPListener();
        ConnectUdpPolicy permissive = new ConnectUdpPolicy() {
            @Override
            public boolean isTargetAllowed(InetAddress address, int port) {
                return true;
            }
        };
        listener.setHandlerFactory((state, headers) -> new ConnectUdpRequestHandler(permissive) { });

        connection = new HTTPProtocolHandler(listener);
        endpoint = new CountingEndpoint();
        endpoint.loop = loop;
        connection.connected(endpoint);
    }

    @After
    public void tearDown() {
        if (loop != null) {
            loop.shutdown();
            loop.awaitQuiesce(2000);
        }
    }

    @Test
    public void testUpgradeRequestIsAcceptedWith101() throws Exception {
        String request = "GET /.well-known/masque/udp/127.0.0.1/4433/ HTTP/1.1\r\n"
                + "Host: proxy.example.test\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: connect-udp\r\n"
                + "Capsule-Protocol: ?1\r\n"
                + "\r\n";
        connection.receive(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));

        assertTrue("a status line response should have been sent within 5s",
                endpoint.statusLineLatch.await(5, TimeUnit.SECONDS));
        String responseText = findStatusLine();
        assertTrue("a 101 Switching Protocols response should have been sent",
                responseText != null && responseText.startsWith("HTTP/1.1 101"));
    }

    private String findStatusLine() {
        synchronized (endpoint.sent) {
            for (byte[] frame : endpoint.sent) {
                String text = new String(frame, StandardCharsets.US_ASCII);
                if (text.startsWith("HTTP/1.1 ")) {
                    return text;
                }
            }
        }
        return null;
    }
}
