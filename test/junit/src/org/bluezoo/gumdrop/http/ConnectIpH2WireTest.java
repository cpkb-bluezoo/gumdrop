/*
 * ConnectIpH2WireTest.java
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.http.hpack.Encoder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Wire-level regression test for issue #394: a real {@link
 * HTTPProtocolHandler} parsing a real, HPACK-encoded HTTP/2 Extended
 * CONNECT request (RFC 9113 section 8.5, RFC 9484 section 4: {@code
 * :method: CONNECT}, {@code :protocol: connect-ip}, {@code
 * Capsule-Protocol: ?1}) must reach {@link ConnectIpRequestHandler#headers}
 * and, once the policy allows it, get a real {@code 200} response back
 * over the wire -- closing the gap between {@link ConnectIpRequestHandlerTest}
 * (which drives {@link ConnectIpRequestHandler} directly) and the actual
 * request-parsing/dispatch path in {@link Stream#acceptConnectIp}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpH2WireTest {

    private static final class CountingEndpoint implements Endpoint {
        final java.util.List<byte[]> sent =
                java.util.Collections.synchronizedList(new java.util.ArrayList<byte[]>());
        final CountDownLatch headersFrameLatch = new CountDownLatch(1);
        SelectorLoop loop;
        @Override public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sent.add(bytes);
            // HEADERS frame type (RFC 9113 section 6.2); setUp() already
            // triggers a SETTINGS ACK send, so only signal on the frame
            // this test actually cares about.
            if (bytes.length >= 9 && bytes[3] == 0x01) {
                headersFrameLatch.countDown();
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

    private static final class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() { return null; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public long getHandshakeDurationMs() { return 0; }
        @Override public boolean isSessionResumed() { return false; }
    }

    private HTTPProtocolHandler connection;
    private CountingEndpoint endpoint;
    private SelectorLoop loop;

    @Before
    public void setUp() {
        loop = new SelectorLoop(0);
        loop.start();

        HTTPListener listener = new HTTPListener();
        ConnectIpPolicy permissive = new ConnectIpPolicy() {
            @Override
            public boolean isRequestAllowed(ConnectIpTarget target) {
                return true;
            }
        };
        IpPacketHandler noopPacketHandler = new IpPacketHandler() {
            @Override public void opened(ConnectIpSession session) { }
            @Override public void packetReceived(ConnectIpSession session, ByteBuffer packet) { }
            @Override public void addressRequested(ConnectIpSession session,
                    java.util.List<ConnectIpAddress> requested) { }
            @Override public void closed(ConnectIpSession session) { }
            @Override public void failed(ConnectIpSession session, Exception cause) { }
        };
        listener.setHandlerFactory((state, headers) -> new ConnectIpRequestHandler(permissive, noopPacketHandler));

        connection = new HTTPProtocolHandler(listener);
        endpoint = new CountingEndpoint();
        endpoint.loop = loop;
        connection.connected(endpoint);
        connection.securityEstablished(new StubSecurityInfo());
        connection.settingsFrameReceived(false, Collections.emptyMap());
    }

    @After
    public void tearDown() {
        if (loop != null) {
            loop.shutdown();
            loop.awaitQuiesce(2000);
        }
    }

    private ByteBuffer encodeConnectIpHeaders(String target, String ipProto) throws Exception {
        Encoder encoder = new Encoder(4096, HTTPListener.DEFAULT_MAX_HEADER_LIST_SIZE);
        Headers request = new Headers();
        request.add(new Header(":method", "CONNECT"));
        request.add(new Header(":protocol", "connect-ip"));
        request.add(new Header(":scheme", "https"));
        request.add(new Header(":authority", "proxy.example.test"));
        request.add(new Header(":path", ConnectIpTarget.encode(target, ipProto)));
        request.add(new Header("capsule-protocol", "?1"));
        ByteBuffer buf = ByteBuffer.allocate(256);
        encoder.encode(buf, request);
        buf.flip();
        return buf;
    }

    @Test
    public void testExtendedConnectRequestIsAcceptedWith200() throws Exception {
        connection.headersFrameReceived(1, false, true, 0, false, 16,
                encodeConnectIpHeaders(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD));

        assertTrue("a HEADERS response frame should have been sent within 5s",
                endpoint.headersFrameLatch.await(5, TimeUnit.SECONDS));
        byte[] headersFrame = findHeadersFrame();
        assertTrue("a HEADERS response frame should have been sent", headersFrame != null);
        // H2 frames are binary, but the status code is HPACK-indexed
        // (RFC 7541 static table #8 == ":status: 200") and so appears as
        // a literal byte in the encoded HEADERS frame payload.
        assertTrue("expected the HPACK static-table index for :status 200 in the response frame",
                containsStatus200(headersFrame));
    }

    private byte[] findHeadersFrame() {
        synchronized (endpoint.sent) {
            for (byte[] frame : endpoint.sent) {
                if (frame.length >= 9 && frame[3] == 0x01) {
                    return frame;
                }
            }
        }
        return null;
    }

    private static boolean containsStatus200(byte[] frame) {
        // RFC 7541 section 6.1: a fully-indexed header field is 1000_0000 | index.
        // Static table index 8 is ":status: 200" (RFC 7541 Appendix A).
        for (byte b : frame) {
            if ((b & 0xff) == 0x88) {
                return true;
            }
        }
        return false;
    }
}
