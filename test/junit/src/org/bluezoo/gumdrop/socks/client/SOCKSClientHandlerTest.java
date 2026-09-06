package org.bluezoo.gumdrop.socks.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SOCKSClientHandler}'s CONNECT, BIND, and UDP
 * ASSOCIATE handshake logic, driven directly against a {@link
 * StubEndpoint} rather than a real socket.
 */
public class SOCKSClientHandlerTest {

    private StubEndpoint endpoint;
    private RecordingInnerHandler innerHandler;
    private RecordingBindListener bindListener;
    private RecordingUDPAssociateListener udpListener;

    @Before
    public void setUp() {
        endpoint = new StubEndpoint();
        innerHandler = new RecordingInnerHandler();
        bindListener = new RecordingBindListener();
        udpListener = new RecordingUDPAssociateListener();
    }

    // ── CONNECT (regression coverage for the shared reply parser) ──

    @Test
    public void testConnectSocks5NoAuthSuccess() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "example.com", 80, new SOCKSClientConfig(), innerHandler);
        handler.connected(endpoint);

        handler.receive(methodSelection((byte) 0x00));
        handler.receive(socks5Reply((byte) 0x00, "10.0.0.1", 1234));

        assertSame(endpoint, innerHandler.connectedEndpoint);
        assertNull(innerHandler.error);
    }

    @Test
    public void testConnectSocks4Success() {
        SOCKSClientConfig config = new SOCKSClientConfig().setVersion(SOCKSClientConfig.Version.SOCKS4);
        SOCKSClientHandler handler = new SOCKSClientHandler("192.168.0.5", 25, config, innerHandler);
        handler.connected(endpoint);

        handler.receive(socks4Reply((byte) 0x5a, "192.168.0.5", 25));

        assertSame(endpoint, innerHandler.connectedEndpoint);
        assertNull(innerHandler.error);
    }

    // ── BIND, SOCKS5 ──

    @Test
    public void testBindSocks5FullSequence() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "ftp.example.com", 21, new SOCKSClientConfig(), bindListener, innerHandler);
        handler.connected(endpoint);

        handler.receive(methodSelection((byte) 0x00));
        // First reply: proxy reports its own listening address.
        handler.receive(socks5Reply((byte) 0x00, "203.0.113.9", 40000));
        assertEquals(new InetSocketAddress("203.0.113.9", 40000), bindListener.boundAddress);
        assertNull(innerHandler.connectedEndpoint);

        // Second reply: a peer has connected.
        handler.receive(socks5Reply((byte) 0x00, "198.51.100.7", 55555));
        assertSame(endpoint, innerHandler.connectedEndpoint);
        assertNull(innerHandler.error);
    }

    @Test
    public void testBindSocks5Reply1Rejected() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "ftp.example.com", 21, new SOCKSClientConfig(), bindListener, innerHandler);
        handler.connected(endpoint);

        handler.receive(methodSelection((byte) 0x00));
        handler.receive(socks5Reply((byte) 0x02, "0.0.0.0", 0)); // 0x02 = not allowed

        assertNull(bindListener.boundAddress);
        assertNotNull(innerHandler.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testBindSocks5Reply2Rejected() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "ftp.example.com", 21, new SOCKSClientConfig(), bindListener, innerHandler);
        handler.connected(endpoint);

        handler.receive(methodSelection((byte) 0x00));
        handler.receive(socks5Reply((byte) 0x00, "203.0.113.9", 40000));
        assertNotNull(bindListener.boundAddress);

        handler.receive(socks5Reply((byte) 0x05, "0.0.0.0", 0)); // 0x05 = connection refused
        assertNull(innerHandler.connectedEndpoint);
        assertNotNull(innerHandler.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testBindReply1SplitAcrossReads() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "ftp.example.com", 21, new SOCKSClientConfig(), bindListener, innerHandler);
        handler.connected(endpoint);
        handler.receive(methodSelection((byte) 0x00));

        ByteBuffer full = socks5Reply((byte) 0x00, "203.0.113.9", 40000);
        byte[] all = new byte[full.remaining()];
        full.get(all);

        // Split into two reads: nothing should fire until the whole reply is in.
        handler.receive(ByteBuffer.wrap(all, 0, 5));
        assertNull(bindListener.boundAddress);
        handler.receive(ByteBuffer.wrap(all, 5, all.length - 5));
        assertEquals(new InetSocketAddress("203.0.113.9", 40000), bindListener.boundAddress);
    }

    // ── BIND, SOCKS4 ──

    @Test
    public void testBindSocks5Reply1DomainNameParsedCorrectly() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "ftp.example.com", 21, new SOCKSClientConfig(), bindListener, innerHandler);
        handler.connected(endpoint);
        handler.receive(methodSelection((byte) 0x00));

        handler.receive(socks5ReplyDomainName((byte) 0x00, "proxy-external.example.com", 40000));

        assertEquals(InetSocketAddress.createUnresolved("proxy-external.example.com", 40000),
                bindListener.boundAddress);
    }

    @Test
    public void testBindSocks4FullSequence() {
        SOCKSClientConfig config = new SOCKSClientConfig().setVersion(SOCKSClientConfig.Version.SOCKS4);
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "10.0.0.1", 21, config, bindListener, innerHandler);
        handler.connected(endpoint);

        handler.receive(socks4Reply((byte) 0x5a, "203.0.113.9", 40000));
        assertEquals(new InetSocketAddress("203.0.113.9", 40000), bindListener.boundAddress);
        assertNull(innerHandler.connectedEndpoint);

        handler.receive(socks4Reply((byte) 0x5a, "198.51.100.7", 55555));
        assertSame(endpoint, innerHandler.connectedEndpoint);
    }

    @Test
    public void testBindSocks4Reply1Rejected() {
        SOCKSClientConfig config = new SOCKSClientConfig().setVersion(SOCKSClientConfig.Version.SOCKS4);
        SOCKSClientHandler handler = new SOCKSClientHandler(
                "10.0.0.1", 21, config, bindListener, innerHandler);
        handler.connected(endpoint);

        handler.receive(socks4Reply((byte) 0x5b, "0.0.0.0", 0)); // 0x5b = rejected

        assertNull(bindListener.boundAddress);
        assertNotNull(innerHandler.error);
        assertFalse(endpoint.isOpen());
    }

    // ── Constructor validation ──

    @Test
    public void testBindConstructorRejectsNullHost() {
        try {
            new SOCKSClientHandler(null, 21, new SOCKSClientConfig(), bindListener, innerHandler);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testBindConstructorRejectsNullBindListener() {
        try {
            new SOCKSClientHandler("ftp.example.com", 21, new SOCKSClientConfig(), null, innerHandler);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testBindConstructorRejectsNullInnerHandler() {
        try {
            new SOCKSClientHandler("ftp.example.com", 21, new SOCKSClientConfig(), bindListener, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testUDPAssociateConstructorRejectsNullFactory() {
        try {
            new SOCKSClientHandler(new SOCKSClientConfig(), null, udpListener);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testUDPAssociateConstructorRejectsNullListener() {
        try {
            new SOCKSClientHandler(new SOCKSClientConfig(), new org.bluezoo.gumdrop.UDPTransportFactory(), null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }

    // ── UDP ASSOCIATE (request/reply handling that doesn't require a real UDP socket) ──

    @Test
    public void testUDPAssociateRequestBytes() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                new SOCKSClientConfig(), new org.bluezoo.gumdrop.UDPTransportFactory(), udpListener);
        handler.connected(endpoint);
        handler.receive(methodSelection((byte) 0x00));

        byte[] sent = endpoint.getLastSent();
        // VER(5) CMD(3=UDP ASSOCIATE) RSV(0) ATYP(1=IPv4) 0.0.0.0 PORT(0)
        assertArrayEquals(new byte[] {
                0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0
        }, sent);
    }

    @Test
    public void testUDPAssociateReplyRejected() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                new SOCKSClientConfig(), new org.bluezoo.gumdrop.UDPTransportFactory(), udpListener);
        handler.connected(endpoint);
        handler.receive(methodSelection((byte) 0x00));

        handler.receive(socks5Reply((byte) 0x02, "0.0.0.0", 0)); // 0x02 = not allowed

        assertNotNull(udpListener.error);
        assertNull(udpListener.associatedAddress);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testUDPAssociateReplyUnresolvedDomainNameRejected() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                new SOCKSClientConfig(), new org.bluezoo.gumdrop.UDPTransportFactory(), udpListener);
        handler.connected(endpoint);
        handler.receive(methodSelection((byte) 0x00));

        handler.receive(socks5ReplyDomainName((byte) 0x00, "relay.example.com", 1234));

        assertNotNull(udpListener.error);
        assertNull(udpListener.associatedAddress);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testUDPAssociateConstructorRejectsSocks4() {
        SOCKSClientConfig config = new SOCKSClientConfig().setVersion(SOCKSClientConfig.Version.SOCKS4);
        try {
            new SOCKSClientHandler(config, new org.bluezoo.gumdrop.UDPTransportFactory(), udpListener);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected: RFC 1928 §7 has no SOCKS4 equivalent
        }
    }

    @Test
    public void testSendDatagramBeforeAssociationThrows() {
        SOCKSClientHandler handler = new SOCKSClientHandler(
                new SOCKSClientConfig(), new org.bluezoo.gumdrop.UDPTransportFactory(), udpListener);
        try {
            handler.sendDatagram(new InetSocketAddress("example.com", 53), ByteBuffer.allocate(4));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected: association not yet established
        }
    }

    // ── Wire helpers ──

    private ByteBuffer methodSelection(byte method) {
        return ByteBuffer.wrap(new byte[] { 0x05, method });
    }

    private ByteBuffer socks4Reply(byte cd, String ip, int port) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.put((byte) 0x00);
            buf.put(cd);
            buf.putShort((short) port);
            buf.put(java.net.InetAddress.getByName(ip).getAddress());
            buf.flip();
            return buf;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ByteBuffer socks5Reply(byte rep, String ip, int port) {
        try {
            byte[] addr = java.net.InetAddress.getByName(ip).getAddress();
            ByteBuffer buf = ByteBuffer.allocate(6 + addr.length);
            buf.put((byte) 0x05);
            buf.put(rep);
            buf.put((byte) 0x00);
            buf.put((byte) 0x01); // ATYP IPv4
            buf.put(addr);
            buf.putShort((short) port);
            buf.flip();
            return buf;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ByteBuffer socks5ReplyDomainName(byte rep, String host, int port) {
        byte[] hostBytes = host.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(7 + hostBytes.length);
        buf.put((byte) 0x05);
        buf.put(rep);
        buf.put((byte) 0x00);
        buf.put((byte) 0x03); // ATYP DOMAINNAME
        buf.put((byte) hostBytes.length);
        buf.put(hostBytes);
        buf.putShort((short) port);
        buf.flip();
        return buf;
    }

    // ── Test doubles ──

    private static class RecordingInnerHandler implements ProtocolHandler {
        Endpoint connectedEndpoint;
        Exception error;
        final List<ByteBuffer> received = new ArrayList<>();

        @Override
        public void connected(Endpoint endpoint) {
            this.connectedEndpoint = endpoint;
        }

        @Override
        public void receive(ByteBuffer data) {
            received.add(data);
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            this.error = cause;
        }
    }

    private static class RecordingBindListener implements SOCKSClientHandler.BindListener {
        InetSocketAddress boundAddress;

        @Override
        public void bound(InetSocketAddress boundAddress) {
            this.boundAddress = boundAddress;
        }
    }

    private static class RecordingUDPAssociateListener implements SOCKSClientHandler.UDPAssociateListener {
        InetSocketAddress associatedAddress;
        Exception error;
        final List<ByteBuffer> received = new ArrayList<>();

        @Override
        public void associated(InetSocketAddress relayAddress) {
            this.associatedAddress = relayAddress;
        }

        @Override
        public void receive(InetSocketAddress source, ByteBuffer payload) {
            received.add(payload);
        }

        @Override
        public void error(Exception cause) {
            this.error = cause;
        }
    }
}
