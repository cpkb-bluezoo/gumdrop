/*
 * ConnectIpClientFlowTest.java
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
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the TCP transports of {@link ConnectIpClient} (RFC 9484): the
 * HTTP/1.1 Upgrade handshake and HTTP/2 Extended CONNECT over an
 * in-memory endpoint, including every rejection path and the dial
 * configuration applied to the transport factory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpClientFlowTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    private static final class Client extends ConnectIpClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        ConnectIpClientProtocolHandler handler;
        IOException failure;
        int connects;

        Client(String host, int port) {
            super(host, port);
        }

        Client(InetAddress address, int port) {
            super(address, port);
        }

        Client(String socketPath) {
            super(socketPath);
        }

        @Override
        void connectEndpointForTesting(ConnectIpClientProtocolHandler ph) throws IOException {
            connects++;
            if (failure != null) {
                throw failure;
            }
            handler = ph;
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            ph.connected(endpoint);
        }
    }

    private static final class Events implements ConnectIpEventHandler {
        final List<String> calls = new ArrayList<String>();
        final List<String> datagrams = new ArrayList<String>();
        int addresses;
        int routes;
        ConnectIpClientSession session;
        Throwable error;

        @Override
        public void opened(ConnectIpClientSession s) {
            session = s;
            calls.add("opened");
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            byte[] b = new byte[packet.remaining()];
            packet.get(b);
            datagrams.add(new String(b, StandardCharsets.US_ASCII));
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            addresses += assignments.size();
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> advertised) {
            routes += advertised.size();
        }

        @Override
        public void closed() {
            calls.add("closed");
        }

        @Override
        public void error(Throwable cause) {
            calls.add("error");
            error = cause;
        }
    }

    private interface FrameWriter {
        void write(H2Writer writer) throws IOException;
    }

    private static byte[] frames(FrameWriter... writers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        H2Writer writer = new H2Writer(channel);
        for (int i = 0; i < writers.length; i++) {
            writers[i].write(writer);
        }
        writer.flush();
        return out.toByteArray();
    }

    private static byte[] serverSettings(final boolean connectProtocol) throws IOException {
        return frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                Map<Integer, Integer> settings = new LinkedHashMap<Integer, Integer>();
                settings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 100);
                if (connectProtocol) {
                    settings.put(H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
                }
                writer.writeSettings(settings);
            }
        });
    }

    private static ByteBuffer statusHeaders(int status) throws Exception {
        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":status", Integer.toString(status)));
        ByteBuffer buf = ByteBuffer.allocate(128);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static byte[] capsule(String text) {
        ByteBuffer encoded = HttpDatagramContext.encode(HttpDatagramContext.REGISTERED_CONTEXT_ID,
                ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
        byte[] ctx = new byte[encoded.remaining()];
        encoded.get(ctx);
        return Capsule.datagram(ctx).encode();
    }

    private static String wire(Client c) {
        return new String(c.endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
    }

    // ---- HTTP/1.1 Upgrade ----

    @Test
    public void http11UpgradeOpensTunnelAndDeliversDatagrams() throws Exception {
        Client client = new Client("127.0.0.1", 80);
        client.dnsHttpsRecordEnabled(false);
        Events events = new Events();
        assertFalse(client.isOpen());
        client.connect(null, "*", "*", events);
        String sent = wire(client);
        assertTrue(sent, sent.contains("Upgrade: connect-ip") || sent.toLowerCase().contains("upgrade: connect-ip"));
        client.handler.receive(ByteBuffer.wrap((
                "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
                + "Upgrade: connect-ip\r\nCapsule-Protocol: ?1\r\n\r\n").getBytes(StandardCharsets.US_ASCII)));
        assertEquals("[opened]", events.calls.toString());
        assertTrue(client.isOpen());
        client.handler.receive(ByteBuffer.wrap(capsule("pong")));
        assertEquals("[pong]", events.datagrams.toString());
        client.close();
    }

    @Test
    public void http11NonUpgradeResponsesAreReportedAsErrors() {
        Client ok = new Client("127.0.0.1", 80);
        ok.dnsHttpsRecordEnabled(false);
        Events okEvents = new Events();
        ok.connect(null, "*", "*", okEvents);
        ok.handler.receive(ByteBuffer.wrap(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(String.valueOf(okEvents.error), okEvents.error.getMessage().contains("did not upgrade"));

        Client denied = new Client("127.0.0.1", 80);
        denied.dnsHttpsRecordEnabled(false);
        Events deniedEvents = new Events();
        denied.connect(null, "*", "*", deniedEvents);
        denied.handler.receive(ByteBuffer.wrap(
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(String.valueOf(deniedEvents.error), deniedEvents.error.getMessage().contains("upgrade failed"));
    }

    @Test
    public void transportErrorsReachTheEventHandler() {
        Client client = new Client("127.0.0.1", 80);
        client.dnsHttpsRecordEnabled(false);
        client.failure = new IOException("no route");
        Events events = new Events();
        client.connect(null, "*", "*", events);
        assertEquals("no route", events.error.getMessage());

        Client live = new Client("127.0.0.1", 80);
        live.dnsHttpsRecordEnabled(false);
        Events liveEvents = new Events();
        live.connect(null, "*", "*", liveEvents);
        live.handler.error(new IOException("reset"));
        assertEquals("reset", liveEvents.error.getMessage());
    }

    // ---- HTTP/2 Extended CONNECT ----

    private static Client h2Client(Events events) {
        Client client = new Client("127.0.0.1", 80);
        client.dnsHttpsRecordEnabled(false);
        client.h2WithPriorKnowledge(true);
        client.connect(null, "*", "*", events);
        return client;
    }

    @Test
    public void h2ExtendedConnectOpensTunnel() throws Exception {
        Events events = new Events();
        Client client = h2Client(events);
        client.handler.receive(ByteBuffer.wrap(serverSettings(true)));
        final ByteBuffer ok = statusHeaders(200);
        client.handler.receive(ByteBuffer.wrap(frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeHeaders(1, ok, false, true);
            }
        })));
        assertEquals("[opened]", events.calls.toString());
        assertTrue(client.isOpen());
        final byte[] cap = capsule("h2-pong");
        client.handler.receive(ByteBuffer.wrap(frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeData(1, ByteBuffer.wrap(cap), false);
            }
        })));
        assertEquals("[h2-pong]", events.datagrams.toString());
        client.close();
    }

    @Test
    public void h2WithoutConnectProtocolSettingIsRejected() throws Exception {
        Events events = new Events();
        Client client = h2Client(events);
        client.handler.receive(ByteBuffer.wrap(serverSettings(false)));
        assertNotNull(events.error);
        assertTrue(events.error.getMessage().contains("Extended CONNECT"));
    }

    @Test
    public void h2RejectedRequestIsAnError() throws Exception {
        Events events = new Events();
        Client client = h2Client(events);
        client.handler.receive(ByteBuffer.wrap(serverSettings(true)));
        final ByteBuffer denied = statusHeaders(403);
        client.handler.receive(ByteBuffer.wrap(frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeHeaders(1, denied, true, true);
            }
        })));
        assertNotNull(events.error);
        assertTrue(events.session == null);
    }

    // ---- dial configuration ----

    @Test
    public void secureDialConfigurationIsApplied() {
        Client client = new Client("127.0.0.1", 443);
        client.dnsHttpsRecordEnabled(false);
        client.secure(true);
        client.tls(new TlsConfig().verifyPeer(false).keystorePass("changeit")
                .keystoreFormat(KeystoreFormat.PKCS12));
        Events events = new Events();
        client.connect(null, "*", "*", events);
        assertEquals(1, client.connects);

        Client trusted = new Client("127.0.0.1", 443);
        trusted.dnsHttpsRecordEnabled(false);
        trusted.secure(true);
        trusted.tls(new TlsConfig().trustManager(new EmptyX509TrustManager())
                .keystoreFile(Path.of("/nonexistent/keystore.p12")));
        trusted.connect(null, "*", "*", new Events());
        assertEquals(1, trusted.connects);
    }

    @Test
    public void addressAndSocketPathClientsConnect() throws Exception {
        Client byAddress = new Client(InetAddress.getByName("127.0.0.1"), 80);
        byAddress.connect(null, "*", "*", new Events());
        assertEquals(1, byAddress.connects);

        Client byPath = new Client("/tmp/proxy.sock");
        byPath.connect(null, "*", "*", new Events());
        assertEquals(1, byPath.connects);
        byPath.altSvcReceived("h3=\":443\"; ma=60");
        assertEquals(null, AltSvcCache.get("localhost", 80));
    }

    @Test
    public void altSvcReceivedPopulatesTheCache() {
        Client client = new Client("127.0.0.1", 80);
        client.dnsHttpsRecordEnabled(false);
        client.altSvcReceived("garbage");
        client.altSvcReceived("h3=\"alt.example:8443\"; ma=60");
        assertNotNull(AltSvcCache.get("127.0.0.1", 80));
    }

    private static byte[] addressCapsule() throws Exception {
        List<ConnectIpAddress> list = new ArrayList<ConnectIpAddress>();
        list.add(new ConnectIpAddress(1L, InetAddress.getByName("10.0.0.2"), 32));
        ByteBuffer value = ConnectIpAddress.encodeList(list);
        byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        return new Capsule(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, bytes).encode();
    }

    private static byte[] routeCapsule() throws Exception {
        List<ConnectIpRoute> list = new ArrayList<ConnectIpRoute>();
        list.add(new ConnectIpRoute(InetAddress.getByName("10.0.0.0"), InetAddress.getByName("10.0.0.255"), 0));
        ByteBuffer value = ConnectIpRoute.encodeList(list);
        byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        return new Capsule(ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT, bytes).encode();
    }

    @Test
    public void addressAndRouteCapsulesAreDeliveredOnBothTransports() throws Exception {
        Client h1 = new Client("127.0.0.1", 80);
        h1.dnsHttpsRecordEnabled(false);
        Events h1Events = new Events();
        h1.connect(null, "*", "*", h1Events);
        h1.handler.receive(ByteBuffer.wrap((
                "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
                + "Upgrade: connect-ip\r\nCapsule-Protocol: ?1\r\n\r\n").getBytes(StandardCharsets.US_ASCII)));
        h1.handler.receive(ByteBuffer.wrap(addressCapsule()));
        h1.handler.receive(ByteBuffer.wrap(routeCapsule()));
        assertEquals(1, h1Events.addresses);
        assertEquals(1, h1Events.routes);

        Events h2Events = new Events();
        Client h2 = h2Client(h2Events);
        h2.handler.receive(ByteBuffer.wrap(serverSettings(true)));
        final ByteBuffer ok = statusHeaders(200);
        h2.handler.receive(ByteBuffer.wrap(frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeHeaders(1, ok, false, true);
            }
        })));
        final byte[] addr = addressCapsule();
        final byte[] route = routeCapsule();
        h2.handler.receive(ByteBuffer.wrap(frames(new FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeData(1, ByteBuffer.wrap(addr), false);
                writer.writeData(1, ByteBuffer.wrap(route), false);
            }
        })));
        assertEquals(1, h2Events.addresses);
        assertEquals(1, h2Events.routes);
    }
}
