/*
 * WebSocketClientTest.java
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

package org.bluezoo.gumdrop.websocket.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketFrame;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.junit.After;
import org.junit.Test;

/**
 * Configuration API, connect-time validation and Alt-Svc handling of
 * {@link WebSocketClient}. Real connections are integration territory.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientTest {

    @After
    public void clearAltSvc() {
        AltSvcCache.clear();
    }

    @Test
    public void fluentConfigurationReturnsSameInstance() throws Exception {
        WebSocketClient c = new WebSocketClient();
        assertSame(c, c.host("example.org"));
        assertSame(c, c.host(InetAddress.getLoopbackAddress()));
        assertSame(c, c.port(8443));
        assertSame(c, c.socketPath("/tmp/none.sock"));
        assertSame(c, c.selectorLoop(null));
        assertSame(c, c.dnsResolver(null));
        assertSame(c, c.secure(true));
        assertSame(c, c.trustJvm());
    }

    @Test
    public void plainSettersAccepted() {
        WebSocketClient c = new WebSocketClient("example.org", 443);
        c.setSecure(true);
        c.setClientCredentials(null);
        c.setVerifyPeer(false);
        c.setTrustManager(null);
        c.setKeystoreFile("/tmp/none.p12");
        c.setKeystorePass("pw");
        c.setKeystoreFormat("PKCS12");
        c.setSubprotocol("chat");
        c.setDeflateEnabled(false);
        c.setH3Enabled(true);
        c.setH2Enabled(false);
        c.setH2WithPriorKnowledge(true);
        c.setDnsHttpsRecordEnabled(false);
        c.addExtension(new PerMessageDeflateExtension());
    }

    @Test
    public void alternateConstructors() throws Exception {
        new WebSocketClient(InetAddress.getLoopbackAddress(), 80);
        new WebSocketClient("/tmp/none.sock");
        new WebSocketClient(null, "localhost", 80);
        new WebSocketClient(null, InetAddress.getLoopbackAddress(), 80);
        new WebSocketClient(null, "/tmp/none.sock");
    }

    @Test
    public void nullArgumentsRejected() {
        try {
            new WebSocketClient((String) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // socketPath required
        }
        try {
            new WebSocketClient().host((InetAddress) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // address required
        }
        try {
            new WebSocketClient().socketPath(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // path required
        }
    }

    @Test
    public void connectWithoutTargetReportsError() {
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        new WebSocketClient().connect(null, "/", h);
        assertEquals(1, h.errors.size());
        assertTrue(h.errors.get(0) instanceof IllegalStateException);
    }

    @Test
    public void h3OverUnixSocketReportsError() {
        WebSocketClient c = new WebSocketClient("/tmp/none.sock");
        c.setH3Enabled(true);
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        assertEquals(1, h.errors.size());
    }

    @Test
    public void notOpenBeforeConnect() {
        WebSocketClient c = new WebSocketClient("localhost", 80);
        assertFalse(c.isOpen());
        c.close();
        assertFalse(c.isOpen());
    }

    @Test
    public void altSvcAdvertisementCachedForOrigin() {
        WebSocketClient c = new WebSocketClient("alt.example", 443);
        c.altSvcReceived("h3=\":8443\"; ma=60");
        assertNotNull(AltSvcCache.get("alt.example", 443));
        assertEquals(8443, AltSvcCache.get("alt.example", 443).getH3Port());
    }

    @Test
    public void altSvcWithoutH3Ignored() {
        WebSocketClient c = new WebSocketClient("alt.example", 443);
        c.altSvcReceived("h2=\":443\"");
        assertNull(AltSvcCache.get("alt.example", 443));
    }

    @Test
    public void altSvcIgnoredOverUnixSocket() {
        WebSocketClient c = new WebSocketClient("/tmp/none.sock");
        c.altSvcReceived("h3=\":8443\"");
        assertNull(AltSvcCache.get("localhost", 8443));
    }

    @Test
    public void altSvcKeyedByAddressWhenNoHostname() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        WebSocketClient c = new WebSocketClient(lo, 443);
        c.altSvcReceived("h3=\":9443\"");
        assertNotNull(AltSvcCache.get(lo.getHostAddress(), 443));
    }

    // ── connect flow over an in-memory endpoint ──

    /** Client whose endpoint is an in-memory recorder. */
    private static final class InMemoryClient extends WebSocketClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        WebSocketClientProtocolHandler handler;
        IOException failure;

        InMemoryClient() {
            super("ws.example", 80);
            setDnsHttpsRecordEnabled(false);
            setH2Enabled(false);
        }

        @Override
        void connectEndpointForTesting(WebSocketClientProtocolHandler ph)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            handler = ph;
            ph.connected(endpoint);
        }

        String sentRequest() {
            return new String(endpoint.getAllBytes(),
                    StandardCharsets.US_ASCII);
        }

        String sentKey() {
            String req = sentRequest();
            int i = req.toLowerCase().indexOf("sec-websocket-key:");
            int eol = req.indexOf("\r\n", i);
            return req.substring(i + "sec-websocket-key:".length(), eol)
                    .trim();
        }

        void respond(String status, String extraHeaders) {
            String framing = status.startsWith("101")
                    ? "" : "Content-Length: 0\r\n";
            String r = "HTTP/1.1 " + status + "\r\n" + extraHeaders
                    + framing + "\r\n";
            handler.receive(ByteBuffer.wrap(
                    r.getBytes(StandardCharsets.US_ASCII)));
        }

        /** Accepts the upgrade with frame bytes in the same buffer. */
        void acceptUpgradeWithPipelined(byte[] frame) {
            String head = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: "
                    + WebSocketHandshake.calculateAccept(sentKey())
                    + "\r\n\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
            byte[] all = new byte[headBytes.length + frame.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(frame, 0, all, headBytes.length, frame.length);
            handler.receive(ByteBuffer.wrap(all));
        }

        void acceptUpgrade() {
            respond("101 Switching Protocols",
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: "
                    + WebSocketHandshake.calculateAccept(sentKey()) + "\r\n");
        }
    }

    @Test
    public void connectSendsUpgradeRequestAndOpensOnValidResponse()
            throws Exception {
        InMemoryClient c = new InMemoryClient();
        c.setSubprotocol("chat");
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/socket", h);
        String req = c.sentRequest();
        assertTrue(req, req.startsWith("GET /socket HTTP/1.1"));
        assertTrue(req.toLowerCase().contains("upgrade: websocket"));
        assertTrue(req.toLowerCase().contains("sec-websocket-protocol: chat"));
        assertTrue(req.toLowerCase().contains("permessage-deflate"));
        assertFalse(c.isOpen());

        c.acceptUpgrade();
        assertTrue(h.errors.toString(), h.errors.isEmpty());
        assertEquals(1, h.openedCount);
        assertTrue(c.isOpen());
        c.close();
        assertFalse(c.isOpen());
    }

    @Test
    public void frameSentInSameBufferAsUpgradeResponseIsDelivered()
            throws Exception {
        InMemoryClient c = new InMemoryClient();
        c.setDeflateEnabled(false);
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        ByteBuffer frame = WebSocketFrame.createTextFrame("early", false)
                .encode();
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        c.acceptUpgradeWithPipelined(bytes);
        assertTrue(h.errors.toString(), h.errors.isEmpty());
        assertEquals(1, h.openedCount);
        assertEquals(Arrays.asList("early"), h.texts);
    }

    @Test
    public void deflateOfferOmittedWhenDisabled() {
        InMemoryClient c = new InMemoryClient();
        c.setDeflateEnabled(false);
        c.connect(null, "/", new RecordingWebSocketEventHandler());
        assertFalse(c.sentRequest().toLowerCase()
                .contains("permessage-deflate"));
    }

    @Test
    public void nonUpgradeSuccessReportedAsError() {
        InMemoryClient c = new InMemoryClient();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        c.respond("200 OK", "");
        assertEquals(0, h.openedCount);
        assertEquals(1, h.errors.size());
        assertTrue(h.errors.get(0).getMessage().contains("did not upgrade"));
    }

    @Test
    public void errorStatusReportedAsError() {
        InMemoryClient c = new InMemoryClient();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        c.respond("403 Forbidden", "");
        assertEquals(1, h.errors.size());
        assertTrue(h.errors.get(0).getMessage().contains("upgrade failed"));
    }

    @Test
    public void endpointCreationFailureReportedToHandler() {
        InMemoryClient c = new InMemoryClient();
        c.failure = new IOException("no route");
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        assertEquals(1, h.errors.size());
        assertSame(c.failure, h.errors.get(0));
    }

    @Test
    public void closeSendsCloseFrameAndClosesEndpoint() throws Exception {
        InMemoryClient c = new InMemoryClient();
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        c.acceptUpgrade();
        c.endpoint.clearWrites();
        c.close();
        assertFalse(c.endpoint.getWrites().isEmpty());
        assertFalse(c.endpoint.isOpen());
    }

    @Test
    public void secureH2WithoutPriorKnowledgeRequestsAlpn() {
        InMemoryClient c = new InMemoryClient();
        c.setSecure(true);
        c.setH2Enabled(true);
        RecordingWebSocketEventHandler h = new RecordingWebSocketEventHandler();
        c.connect(null, "/", h);
        assertNotNull(c.handler);
    }
}
