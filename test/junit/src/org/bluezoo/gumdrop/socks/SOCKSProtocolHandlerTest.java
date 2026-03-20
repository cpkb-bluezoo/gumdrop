package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Unit tests for {@link SOCKSProtocolHandler}.
 *
 * <p>Tests the state machine, version detection, protocol parsing,
 * and reply construction by feeding raw bytes via
 * {@link SOCKSProtocolHandler#receive(ByteBuffer)} and inspecting
 * what is sent back via a {@link StubEndpoint}.
 */
public class SOCKSProtocolHandlerTest {

    private DefaultSOCKSService service;
    private SOCKSListener listener;
    private SOCKSProtocolHandler handler;
    private StubEndpoint endpoint;

    @Before
    public void setUp() {
        service = new DefaultSOCKSService();
        listener = new SOCKSListener();
        listener.setService(service);
        handler = service.createProtocolHandler(listener);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Version detection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testVersionDetectSOCKS4() {
        // SOCKS4 CONNECT to 10.0.0.1:80, userid "test"
        ByteBuffer buf = buildSOCKS4Connect(
                new byte[]{10, 0, 0, 1}, 80, "test");
        handler.receive(buf);

        // Handler transitions past VERSION_DETECT and attempts
        // upstream connect. Without a real SelectorLoop, the connect
        // fails and the handler closes the connection.
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testVersionDetectSOCKS5() {
        // SOCKS5 greeting: VER=5, NMETHODS=1, METHOD=NO_AUTH
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(SOCKS5_VERSION);
        buf.put((byte) 1);         // NMETHODS
        buf.put(SOCKS5_AUTH_NONE);  // offered method
        buf.flip();

        handler.receive(buf);

        assertEquals(1, endpoint.getSentCount());
        byte[] reply = endpoint.getLastSent();
        assertEquals(2, reply.length);
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_AUTH_NONE, reply[1]);
    }

    @Test
    public void testVersionDetectUnsupported() {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0x03); // unsupported version
        buf.flip();

        handler.receive(buf);

        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testVersionDetectEmptyBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        handler.receive(buf);
        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 method negotiation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS5NoAcceptableMethods() {
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(SOCKS5_VERSION);
        buf.put((byte) 1);
        buf.put((byte) 0x99); // unknown method
        buf.flip();

        handler.receive(buf);

        assertEquals(1, endpoint.getSentCount());
        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals((byte) 0xFF, reply[1]); // NO ACCEPTABLE
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS5MultipleMethods() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put(SOCKS5_VERSION);
        buf.put((byte) 3); // 3 methods
        buf.put(SOCKS5_AUTH_GSSAPI);
        buf.put(SOCKS5_AUTH_USERNAME_PASSWORD);
        buf.put(SOCKS5_AUTH_NONE);
        buf.flip();

        handler.receive(buf);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_AUTH_NONE, reply[1]);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 request parsing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS5ConnectIPv4Request() {
        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        // SOCKS5 CONNECT to 10.0.0.1:80
        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00); // RSV
        req.put(SOCKS5_ATYP_IPV4);
        req.put(new byte[]{10, 0, 0, 1});
        req.putShort((short) 80);
        req.flip();

        handler.receive(req);

        // Without a real SelectorLoop, upstream connect fails.
        // Handler catches the error and closes.
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS5ConnectDomainNameRequest() {
        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        String hostname = "example.com";
        byte[] nameBytes = hostname.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer req = ByteBuffer.allocate(
                4 + 1 + nameBytes.length + 2);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put(SOCKS5_ATYP_DOMAINNAME);
        req.put((byte) nameBytes.length);
        req.put(nameBytes);
        req.putShort((short) 443);
        req.flip();

        handler.receive(req);

        // DNS resolution fails (no SelectorLoop/DNSResolver); handler
        // catches the error and closes.
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS5UnsupportedAddressType() {
        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put((byte) 0x99); // unsupported ATYP
        req.put(new byte[]{0, 0, 0, 0, 0, 0});
        req.flip();

        handler.receive(req);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4 request parsing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS4ConnectRequest() {
        ByteBuffer buf = buildSOCKS4Connect(
                new byte[]{8, 8, 8, 8}, 53, "user");
        handler.receive(buf);

        // Without a real SelectorLoop, upstream connect fails.
        // Handler catches the error and closes.
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS4InvalidCommand() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put(SOCKS4_VERSION);
        buf.put((byte) 0x99); // invalid command
        buf.putShort((short) 80);
        buf.put(new byte[]{10, 0, 0, 1});
        buf.put((byte) 0x00); // empty userid + null terminator
        buf.flip();

        handler.receive(buf);

        byte[] reply = endpoint.getLastSent();
        assertEquals(0x00, reply[0]);
        assertEquals(SOCKS4_REPLY_REJECTED, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS4aHostname() {
        // SOCKS4a: IP 0.0.0.1 signals hostname follows after userid
        ByteBuffer buf = ByteBuffer.allocate(50);
        buf.put(SOCKS4_VERSION);
        buf.put(SOCKS4_CMD_CONNECT);
        buf.putShort((short) 80);
        buf.put(new byte[]{0, 0, 0, 1}); // SOCKS4a marker
        buf.put("user".getBytes(StandardCharsets.ISO_8859_1));
        buf.put((byte) 0); // null terminator for userid
        buf.put("example.com".getBytes(StandardCharsets.ISO_8859_1));
        buf.put((byte) 0); // null terminator for hostname
        buf.flip();

        handler.receive(buf);

        // DNS resolution fails (no SelectorLoop/DNSResolver); handler
        // catches the error and closes.
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS4IncompleteRequest() {
        // Only 5 bytes - less than minimum SOCKS4 request (9)
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put(SOCKS4_VERSION);
        buf.put(SOCKS4_CMD_CONNECT);
        buf.putShort((short) 80);
        buf.put((byte) 10);
        buf.flip();

        handler.receive(buf);

        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4 reply format
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS4ReplyFormat() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put(SOCKS4_VERSION);
        buf.put((byte) 0x99); // invalid cmd → rejected
        buf.putShort((short) 80);
        buf.put(new byte[]{10, 0, 0, 1});
        buf.put((byte) 0x00);
        buf.flip();

        handler.receive(buf);

        byte[] reply = endpoint.getLastSent();
        assertEquals(8, reply.length);
        assertEquals(0x00, reply[0]); // VN = 0
        assertEquals(SOCKS4_REPLY_REJECTED, reply[1]); // CD
        // bytes 2-3: DSTPORT (0)
        // bytes 4-7: DSTIP (0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 reply format
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS5ReplyFormatForUnsupportedATYP() {
        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put((byte) 0x99); // bad ATYP
        req.put(new byte[]{0, 0, 0, 0, 0, 0});
        req.flip();

        handler.receive(req);

        byte[] reply = endpoint.getLastSent();
        assertTrue(reply.length >= 10);
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED, reply[1]);
        assertEquals(0x00, reply[2]); // RSV
    }

    // ═══════════════════════════════════════════════════════════════════
    // State transitions
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testDisconnectedInVersionDetect() {
        handler.disconnected();
        // Should not throw
    }

    @Test
    public void testErrorClosesConnection() {
        handler.error(new Exception("test error"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSecurityEstablished() {
        handler.securityEstablished(null);
        // Should not throw, logs only
    }

    // ═══════════════════════════════════════════════════════════════════
    // Relay limiting
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS5ConnectWhenRelayLimitReached() {
        service.setMaxRelays(0);
        // With maxRelays=0, acquireRelay always succeeds (unlimited)
        // Set to 1 and consume it
        service.setMaxRelays(1);
        service.acquireRelay();

        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put(SOCKS5_ATYP_IPV4);
        req.put(new byte[]{10, 0, 0, 1});
        req.putShort((short) 80);
        req.flip();

        handler.receive(req);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Destination filtering
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSOCKS4ConnectBlockedDestination() {
        service.setBlockedDestinations("10.0.0.0/8");

        ByteBuffer buf = buildSOCKS4Connect(
                new byte[]{10, 1, 2, 3}, 80, "user");
        handler.receive(buf);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS4_REPLY_REJECTED, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testSOCKS5ConnectBlockedDestination() {
        service.setBlockedDestinations("192.168.0.0/16");

        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put(SOCKS5_ATYP_IPV4);
        req.put(new byte[]{(byte) 192, (byte) 168, 1, 1});
        req.putShort((short) 80);
        req.flip();

        handler.receive(req);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // ConnectHandler / BindHandler wiring
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testConnectHandlerDeny() {
        handler.setConnectHandler(
                new org.bluezoo.gumdrop.socks.handler.ConnectHandler() {
                    @Override
                    public void handleConnect(
                            org.bluezoo.gumdrop.socks.handler
                                    .ConnectState state,
                            SOCKSRequest request,
                            org.bluezoo.gumdrop.Endpoint clientEp) {
                        state.deny(SOCKS5_REPLY_NOT_ALLOWED);
                    }
                });

        negotiateSOCKS5NoAuth();
        endpoint.clearSent();

        ByteBuffer req = ByteBuffer.allocate(10);
        req.put(SOCKS5_VERSION);
        req.put(SOCKS5_CMD_CONNECT);
        req.put((byte) 0x00);
        req.put(SOCKS5_ATYP_IPV4);
        req.put(new byte[]{8, 8, 8, 8});
        req.putShort((short) 53);
        req.flip();

        handler.receive(req);

        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void negotiateSOCKS5NoAuth() {
        ByteBuffer greeting = ByteBuffer.allocate(3);
        greeting.put(SOCKS5_VERSION);
        greeting.put((byte) 1);
        greeting.put(SOCKS5_AUTH_NONE);
        greeting.flip();
        handler.receive(greeting);
    }

    private ByteBuffer buildSOCKS4Connect(byte[] ip, int port,
                                          String userid) {
        byte[] uidBytes = userid.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(
                1 + 1 + 2 + 4 + uidBytes.length + 1);
        buf.put(SOCKS4_VERSION);
        buf.put(SOCKS4_CMD_CONNECT);
        buf.putShort((short) port);
        buf.put(ip);
        buf.put(uidBytes);
        buf.put((byte) 0); // null terminator
        buf.flip();
        return buf;
    }
}
