/*
 * ConnectTunnelLoopbackIntegrationTest.java
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

import java.io.ByteArrayOutputStream;
import org.bluezoo.gumdrop.http.HttpVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.testsupport.RefusingTransportFactory;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HttpDatagramContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link ConnectUdpClient} and {@link ConnectIpClient} through a
 * loopback proxy that speaks the HTTP/1.1 Upgrade handshake: tunnel
 * acceptance, refusal, datagram and packet exchange and close notification.
 *
 * <p>Integration test: connects the clients to a loopback proxy on real sockets
 * through a live loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectTunnelLoopbackIntegrationTest {

    private static final int TIMEOUT_MS = 10000;

    private Gumdrop gumdrop;
    private ClientEndpoint keeper;
    private ServerSocket proxy;
    private Socket accepted;
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<byte[]>());
    private final CountDownLatch opened = new CountDownLatch(1);
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        keeper = new ClientEndpoint(new TcpTransportFactory(), gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        proxy = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        proxy.setSoTimeout(TIMEOUT_MS);
    }

    @After
    public void tearDown() throws Exception {
        if (accepted != null) {
            accepted.close();
        }
        proxy.close();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private String acceptAndReadRequest() throws IOException {
        accepted = proxy.accept();
        accepted.setSoTimeout(TIMEOUT_MS);
        InputStream in = accepted.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("eof after: " + head.size() + " bytes; events=" + events);
            }
            head.write(b);
            if ((state % 2 == 0 && b == '\r') || (state % 2 == 1 && b == '\n')) {
                state++;
            } else if (b == '\r') {
                state = 1;
            } else {
                state = 0;
            }
        }
        return new String(head.toByteArray(), StandardCharsets.US_ASCII);
    }

    private void reply(String text) throws IOException {
        OutputStream out = accepted.getOutputStream();
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out; events=" + events, latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private static final String SWITCH_UDP = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Connection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n\r\n";
    private static final String SWITCH_IP = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Connection: Upgrade\r\nUpgrade: connect-ip\r\nCapsule-Protocol: ?1\r\n\r\n";

    private static byte[] datagramCapsule(String text) {
        byte[] payload = text.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer encoded = HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID,
                ByteBuffer.wrap(payload));
        byte[] ctx = new byte[encoded.remaining()];
        encoded.get(ctx);
        return Capsule.datagram(ctx).encode();
    }

    private final class UdpEvents implements ConnectUdpEventHandler {
        volatile ConnectUdpSession session;

        @Override
        public void opened(ConnectUdpSession s) {
            session = s;
            events.add("opened");
            opened.countDown();
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            byte[] b = new byte[payload.remaining()];
            payload.get(b);
            payloads.add(b);
            received.countDown();
        }

        @Override
        public void closed() {
            events.add("closed");
            finished.countDown();
        }

        @Override
        public void error(Throwable cause) {
            events.add("error:" + cause.getMessage());
            finished.countDown();
        }
    }

    private final class IpEvents implements ConnectIpEventHandler {
        volatile ConnectIpClientSession session;

        @Override
        public void opened(ConnectIpClientSession s) {
            session = s;
            events.add("opened");
            opened.countDown();
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            byte[] b = new byte[packet.remaining()];
            packet.get(b);
            payloads.add(b);
            received.countDown();
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            events.add("addresses");
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
            events.add("routes");
        }

        @Override
        public void closed() {
            events.add("closed");
            finished.countDown();
        }

        @Override
        public void error(Throwable cause) {
            events.add("error:" + cause.getMessage());
            finished.countDown();
        }
    }

    // ── CONNECT-UDP ──

    @Test
    public void udpTunnelOpensExchangesDatagramsAndCloses() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        UdpEvents handler = new UdpEvents();
        client.connect(gumdrop, "target.example", 5353, handler);
        String request = acceptAndReadRequest();
        assertTrue(request, request.startsWith("GET /.well-known/masque/udp/target.example/5353/ HTTP/1.1\r\n"));
        assertTrue(request, request.toLowerCase().contains("upgrade: connect-udp\r\n"));
        assertTrue(request, request.toLowerCase().contains("capsule-protocol: ?1\r\n"));
        reply(SWITCH_UDP);
        await(opened);
        assertTrue(client.isOpen());
        reply("");
        accepted.getOutputStream().write(datagramCapsule("pong"));
        accepted.getOutputStream().flush();
        await(received);
        assertEquals("pong", new String(payloads.get(0), StandardCharsets.US_ASCII));

        handler.session.sendDatagram(ByteBuffer.wrap("ping".getBytes(StandardCharsets.US_ASCII)));
        byte[] expected = datagramCapsule("ping");
        byte[] got = new byte[expected.length];
        int off = 0;
        InputStream in = accepted.getInputStream();
        while (off < got.length) {
            int n = in.read(got, off, got.length - off);
            assertTrue(n > 0);
            off += n;
        }
        assertEquals(new String(expected, StandardCharsets.ISO_8859_1), new String(got, StandardCharsets.ISO_8859_1));

        accepted.close();
        await(finished);
        assertTrue(events.toString(), events.contains("closed"));
        client.close();
    }

    @Test
    public void udpDatagramsPipelinedWithTheUpgradeResponseAreDelivered() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        UdpEvents handler = new UdpEvents();
        client.connect(gumdrop, "10.0.0.1", 53, handler);
        acceptAndReadRequest();
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(SWITCH_UDP.getBytes(StandardCharsets.US_ASCII));
        both.write(datagramCapsule("early"));
        accepted.getOutputStream().write(both.toByteArray());
        accepted.getOutputStream().flush();
        await(received);
        assertEquals("opened comes before the first datagram", "opened", events.get(0));
        assertEquals("early", new String(payloads.get(0), StandardCharsets.US_ASCII));
        client.close();
        assertNotNull(handler.session);
    }

    @Test
    public void udpUpgradeRefusalIsReportedAsAnError() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        client.connect(gumdrop, "h", 1, new UdpEvents());
        acceptAndReadRequest();
        reply("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n");
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:CONNECT-UDP upgrade failed"));
        assertFalse(client.isOpen());
        client.close();
    }

    @Test
    public void udpProxyThatDoesNotUpgradeIsReportedAsAnError() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        client.connect(gumdrop, "h", 1, new UdpEvents());
        acceptAndReadRequest();
        reply("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:Proxy did not upgrade"));
        client.close();
    }

    @Test
    public void udpConnectionRefusedIsReportedAsAnError() throws Exception {
        final RefusingTransportFactory refusing = new RefusingTransportFactory();
        ConnectUdpClient client = new ConnectUdpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort()) {
            @Override
            TcpTransportFactory newTransportFactory() {
                return refusing;
            }
        };
        client.dnsHttpsRecordEnabled(false);
        client.versions(HttpVersion.HTTP_1_1);
        client.connect(gumdrop, "h", 1, new UdpEvents());
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:"));
        assertEquals(1, refusing.attempts());
    }

    @Test
    public void udpConfigurationSettersAreAccepted() throws Exception {
        ConnectUdpClient client = new ConnectUdpClient("proxy.example", 443);
        client.secure(true);
        client.tls(new TlsConfig().verifyPeer(false).keystorePass("x"));
        client.versions(HttpVersion.HTTP_2_0);
        client.h2WithPriorKnowledge(true);
        client.dnsHttpsRecordEnabled(false);
        assertFalse(client.isOpen());
        client.close();
    }

    // ── CONNECT-IP ──

    @Test
    public void ipTunnelOpensAndExchangesPackets() throws Exception {
        ConnectIpClient client = new ConnectIpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        IpEvents handler = new IpEvents();
        client.connect(gumdrop, "*", "*", handler);
        String request = acceptAndReadRequest();
        assertTrue(request, request.startsWith("GET /.well-known/masque/ip/"));
        assertTrue(request, request.toLowerCase().contains("upgrade: connect-ip\r\n"));
        reply(SWITCH_IP);
        await(opened);
        assertTrue(client.isOpen());
        accepted.getOutputStream().write(datagramCapsule("pkt"));
        accepted.getOutputStream().flush();
        await(received);
        assertEquals("pkt", new String(payloads.get(0), StandardCharsets.US_ASCII));
        handler.session.sendPacket(ByteBuffer.wrap("out".getBytes(StandardCharsets.US_ASCII)));
        byte[] expected = datagramCapsule("out");
        byte[] got = new byte[expected.length];
        int off = 0;
        InputStream in = accepted.getInputStream();
        while (off < got.length) {
            int n = in.read(got, off, got.length - off);
            assertTrue(n > 0);
            off += n;
        }
        accepted.close();
        await(finished);
        assertTrue(events.toString(), events.contains("closed"));
        client.close();
    }

    @Test
    public void ipUpgradeRefusalIsReportedAsAnError() throws Exception {
        ConnectIpClient client = new ConnectIpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        client.connect(gumdrop, "*", "*", new IpEvents());
        acceptAndReadRequest();
        reply("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:"));
        client.close();
    }

    @Test
    public void ipProxyThatDoesNotUpgradeIsReportedAsAnError() throws Exception {
        ConnectIpClient client = new ConnectIpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort());
        client.connect(gumdrop, "*", "*", new IpEvents());
        acceptAndReadRequest();
        reply("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:"));
        client.close();
    }

    @Test
    public void ipConnectionRefusedIsReportedAsAnError() throws Exception {
        final RefusingTransportFactory refusing = new RefusingTransportFactory();
        ConnectIpClient client = new ConnectIpClient(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort()) {
            @Override
            TcpTransportFactory newTransportFactory() {
                return refusing;
            }
        };
        client.dnsHttpsRecordEnabled(false);
        client.connect(gumdrop, "*", "*", new IpEvents());
        await(finished);
        assertTrue(events.toString(), events.get(0).startsWith("error:"));
        assertEquals(1, refusing.attempts());
    }

    @Test
    public void ipH3OverUnixSocketIsRejected() throws Exception {
        ConnectIpClient client = new ConnectIpClient("/tmp/connect-ip.sock");
        client.versions(HttpVersion.HTTP_3);
        client.connect(gumdrop, "*", "*", new IpEvents());
        assertTrue(events.toString(), events.get(0).startsWith("error:"));
        assertTrue(events.toString(), events.get(0).contains("HTTP/3"));
    }

    @Test
    public void ipConfigurationSettersAreAccepted() {
        ConnectIpClient client = new ConnectIpClient("proxy.example", 443);
        client.secure(true);
        client.tls(new TlsConfig().verifyPeer(false).keystorePass("x"));
        client.versions(HttpVersion.HTTP_2_0);
        client.h2WithPriorKnowledge(true);
        client.dnsHttpsRecordEnabled(false);
        assertFalse(client.isOpen());
        client.close();
        client.altSvcReceived("h3=\":443\"");
        client.altSvcReceived("nonsense");
        AltSvcCache.clear();
    }
}
