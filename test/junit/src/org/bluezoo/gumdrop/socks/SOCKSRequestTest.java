package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Unit tests for {@link SOCKSRequest}.
 */
public class SOCKSRequestTest {

    @Test
    public void testSOCKS4ConnectWithAddress() throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        SOCKSRequest req = new SOCKSRequest(
                SOCKS4_VERSION, SOCKS4_CMD_CONNECT,
                addr, null, 80, "myuser", null);

        assertEquals(SOCKS4_VERSION, req.getVersion());
        assertEquals(SOCKS4_CMD_CONNECT, req.getCommand());
        assertEquals(addr, req.getAddress());
        assertNull(req.getHost());
        assertEquals(80, req.getPort());
        assertEquals("myuser", req.getUserid());
        assertNull(req.getAuthenticatedUser());
        assertFalse(req.isSOCKS4a());
    }

    @Test
    public void testSOCKS4aConnectWithHostname() {
        SOCKSRequest req = new SOCKSRequest(
                SOCKS4_VERSION, SOCKS4_CMD_CONNECT,
                null, "example.com", 443, "user1", null);

        assertTrue(req.isSOCKS4a());
        assertNull(req.getAddress());
        assertEquals("example.com", req.getHost());
    }

    @Test
    public void testSOCKS5Connect() throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(
                new byte[]{(byte) 192, (byte) 168, 1, 1});
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_CONNECT,
                addr, null, 8080, null, "alice");

        assertEquals(SOCKS5_VERSION, req.getVersion());
        assertEquals(SOCKS5_CMD_CONNECT, req.getCommand());
        assertEquals(addr, req.getAddress());
        assertEquals(8080, req.getPort());
        assertNull(req.getUserid());
        assertEquals("alice", req.getAuthenticatedUser());
        assertFalse(req.isSOCKS4a());
    }

    @Test
    public void testSOCKS5ConnectWithHostname() {
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_CONNECT,
                null, "host.example.org", 22, null, null);

        assertFalse(req.isSOCKS4a());
        assertEquals("host.example.org", req.getHost());
    }

    // ── toString() ──

    @Test
    public void testToStringSOCKS4Connect() throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        SOCKSRequest req = new SOCKSRequest(
                SOCKS4_VERSION, SOCKS4_CMD_CONNECT,
                addr, null, 80, "bob", null);

        String s = req.toString();
        assertTrue(s.startsWith("SOCKS4 CONNECT"));
        assertTrue(s.contains(":80"));
        assertTrue(s.contains("userid=bob"));
    }

    @Test
    public void testToStringSOCKS4aBind() {
        SOCKSRequest req = new SOCKSRequest(
                SOCKS4_VERSION, SOCKS4_CMD_BIND,
                null, "ftp.example.com", 21, "user", null);

        String s = req.toString();
        assertTrue(s.startsWith("SOCKS4a BIND"));
        assertTrue(s.contains("ftp.example.com:21"));
    }

    @Test
    public void testToStringSOCKS5Connect() throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_CONNECT,
                addr, null, 53, null, "alice");

        String s = req.toString();
        assertTrue(s.startsWith("SOCKS5 CONNECT"));
        assertTrue(s.contains("user=alice"));
    }

    @Test
    public void testToStringSOCKS5Bind() throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(new byte[]{1, 2, 3, 4});
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_BIND,
                addr, null, 4000, null, null);

        String s = req.toString();
        assertTrue(s.startsWith("SOCKS5 BIND"));
        assertTrue(s.contains(":4000"));
    }

    @Test
    public void testToStringSOCKS5UDPAssociate() {
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_UDP_ASSOCIATE,
                null, null, 0, null, null);

        String s = req.toString();
        assertTrue(s, s.contains("UDP ASSOCIATE"));
    }

    @Test
    public void testToStringNoAddressNoHost() {
        SOCKSRequest req = new SOCKSRequest(
                SOCKS5_VERSION, SOCKS5_CMD_CONNECT,
                null, null, 9090, null, null);

        String s = req.toString();
        assertTrue(s.contains(":9090"));
        assertFalse(s.contains("user="));
        assertFalse(s.contains("userid="));
    }
}
