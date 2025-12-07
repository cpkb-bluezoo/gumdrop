/*
 * CIDRNetworkTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.util;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CIDRNetwork}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CIDRNetworkTest {

    // IPv4 Tests
    
    @Test
    public void testIPv4SingleHost() throws Exception {
        CIDRNetwork network = new CIDRNetwork("192.168.1.100/32");
        
        assertTrue(network.isIPv4());
        assertFalse(network.isIPv6());
        
        assertTrue(network.matches(InetAddress.getByName("192.168.1.100")));
        assertFalse(network.matches(InetAddress.getByName("192.168.1.99")));
        assertFalse(network.matches(InetAddress.getByName("192.168.1.101")));
    }
    
    @Test
    public void testIPv4Slash24() throws Exception {
        CIDRNetwork network = new CIDRNetwork("192.168.1.0/24");
        
        assertTrue(network.matches(InetAddress.getByName("192.168.1.0")));
        assertTrue(network.matches(InetAddress.getByName("192.168.1.1")));
        assertTrue(network.matches(InetAddress.getByName("192.168.1.100")));
        assertTrue(network.matches(InetAddress.getByName("192.168.1.255")));
        
        assertFalse(network.matches(InetAddress.getByName("192.168.0.1")));
        assertFalse(network.matches(InetAddress.getByName("192.168.2.1")));
    }
    
    @Test
    public void testIPv4Slash16() throws Exception {
        CIDRNetwork network = new CIDRNetwork("172.16.0.0/16");
        
        assertTrue(network.matches(InetAddress.getByName("172.16.0.1")));
        assertTrue(network.matches(InetAddress.getByName("172.16.255.255")));
        
        assertFalse(network.matches(InetAddress.getByName("172.15.0.1")));
        assertFalse(network.matches(InetAddress.getByName("172.17.0.1")));
    }
    
    @Test
    public void testIPv4Slash8() throws Exception {
        CIDRNetwork network = new CIDRNetwork("10.0.0.0/8");
        
        assertTrue(network.matches(InetAddress.getByName("10.0.0.1")));
        assertTrue(network.matches(InetAddress.getByName("10.255.255.255")));
        
        assertFalse(network.matches(InetAddress.getByName("11.0.0.1")));
        assertFalse(network.matches(InetAddress.getByName("9.255.255.255")));
    }
    
    @Test
    public void testIPv4Slash0() throws Exception {
        CIDRNetwork network = new CIDRNetwork("0.0.0.0/0");
        
        // /0 matches everything
        assertTrue(network.matches(InetAddress.getByName("0.0.0.0")));
        assertTrue(network.matches(InetAddress.getByName("255.255.255.255")));
        assertTrue(network.matches(InetAddress.getByName("192.168.1.1")));
    }
    
    // IPv6 Tests
    
    @Test
    public void testIPv6SingleHost() throws Exception {
        CIDRNetwork network = new CIDRNetwork("2001:db8::1/128");
        
        assertFalse(network.isIPv4());
        assertTrue(network.isIPv6());
        
        assertTrue(network.matches(InetAddress.getByName("2001:db8::1")));
        assertFalse(network.matches(InetAddress.getByName("2001:db8::2")));
    }
    
    @Test
    public void testIPv6Slash64() throws Exception {
        CIDRNetwork network = new CIDRNetwork("2001:db8:85a3::/64");
        
        assertTrue(network.matches(InetAddress.getByName("2001:db8:85a3::1")));
        assertTrue(network.matches(InetAddress.getByName("2001:db8:85a3::ffff:ffff:ffff:ffff")));
        
        assertFalse(network.matches(InetAddress.getByName("2001:db8:85a4::1")));
    }
    
    @Test
    public void testIPv6Slash32() throws Exception {
        CIDRNetwork network = new CIDRNetwork("2001:db8::/32");
        
        assertTrue(network.matches(InetAddress.getByName("2001:db8::1")));
        assertTrue(network.matches(InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")));
        
        assertFalse(network.matches(InetAddress.getByName("2001:db9::1")));
    }
    
    @Test
    public void testIPv6LinkLocal() throws Exception {
        CIDRNetwork network = new CIDRNetwork("fe80::/10");
        
        assertTrue(network.matches(InetAddress.getByName("fe80::1")));
        assertTrue(network.matches(InetAddress.getByName("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff")));
        
        assertFalse(network.matches(InetAddress.getByName("fec0::1")));
    }
    
    @Test
    public void testIPv6Loopback() throws Exception {
        CIDRNetwork network = new CIDRNetwork("::1/128");
        
        assertTrue(network.matches(InetAddress.getByName("::1")));
        assertFalse(network.matches(InetAddress.getByName("::2")));
    }
    
    // Type mismatch tests
    
    @Test
    public void testIPv4NetworkDoesNotMatchIPv6() throws Exception {
        CIDRNetwork network = new CIDRNetwork("192.168.1.0/24");
        
        // Pure IPv6 addresses should not match an IPv4 network
        assertFalse(network.matches(InetAddress.getByName("2001:db8::1")));
    }
    
    @Test
    public void testIPv6NetworkDoesNotMatchIPv4() throws Exception {
        CIDRNetwork network = new CIDRNetwork("2001:db8::/32");
        
        assertFalse(network.matches(InetAddress.getByName("192.168.1.1")));
    }
    
    // Error cases
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRNoPrefixLength() {
        new CIDRNetwork("192.168.1.0");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRBadPrefix() {
        new CIDRNetwork("192.168.1.0/abc");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRPrefixTooLargeIPv4() {
        new CIDRNetwork("192.168.1.0/33");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRPrefixTooLargeIPv6() {
        new CIDRNetwork("2001:db8::/129");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRNegativePrefix() {
        new CIDRNetwork("192.168.1.0/-1");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCIDRBadAddress() {
        new CIDRNetwork("not.an.ip/24");
    }
    
    // Utility tests
    
    @Test
    public void testGetOriginalCIDR() {
        CIDRNetwork network = new CIDRNetwork("192.168.1.0/24");
        assertEquals("192.168.1.0/24", network.getOriginalCIDR());
    }
    
    @Test
    public void testToString() {
        CIDRNetwork ipv4 = new CIDRNetwork("192.168.1.0/24");
        assertTrue(ipv4.toString().contains("IPv4"));
        
        CIDRNetwork ipv6 = new CIDRNetwork("2001:db8::/32");
        assertTrue(ipv6.toString().contains("IPv6"));
    }
    
    @Test
    public void testEquals() {
        CIDRNetwork n1 = new CIDRNetwork("192.168.1.0/24");
        CIDRNetwork n2 = new CIDRNetwork("192.168.1.0/24");
        CIDRNetwork n3 = new CIDRNetwork("192.168.2.0/24");
        
        assertEquals(n1, n2);
        assertNotEquals(n1, n3);
    }
    
    @Test
    public void testHashCode() {
        CIDRNetwork n1 = new CIDRNetwork("192.168.1.0/24");
        CIDRNetwork n2 = new CIDRNetwork("192.168.1.0/24");
        
        assertEquals(n1.hashCode(), n2.hashCode());
    }
    
    @Test
    public void testMatchesAny() throws Exception {
        List<CIDRNetwork> networks = Arrays.asList(
            new CIDRNetwork("192.168.1.0/24"),
            new CIDRNetwork("10.0.0.0/8"),
            new CIDRNetwork("172.16.0.0/12")
        );
        
        assertTrue(CIDRNetwork.matchesAny(InetAddress.getByName("192.168.1.50"), networks));
        assertTrue(CIDRNetwork.matchesAny(InetAddress.getByName("10.1.2.3"), networks));
        assertTrue(CIDRNetwork.matchesAny(InetAddress.getByName("172.20.1.1"), networks));
        
        assertFalse(CIDRNetwork.matchesAny(InetAddress.getByName("8.8.8.8"), networks));
    }
    
    @Test
    public void testParseList() throws Exception {
        List<CIDRNetwork> networks = CIDRNetwork.parseList("192.168.1.0/24, 10.0.0.0/8, 2001:db8::/32");
        
        assertEquals(3, networks.size());
        assertTrue(networks.get(0).isIPv4());
        assertTrue(networks.get(1).isIPv4());
        assertTrue(networks.get(2).isIPv6());
    }
    
    @Test
    public void testParseListEmpty() {
        List<CIDRNetwork> networks = CIDRNetwork.parseList("");
        assertTrue(networks.isEmpty());
        
        networks = CIDRNetwork.parseList(null);
        assertTrue(networks.isEmpty());
    }
    
    @Test
    public void testParseListWithExtraWhitespace() {
        List<CIDRNetwork> networks = CIDRNetwork.parseList("  192.168.1.0/24  ,  10.0.0.0/8  ");
        assertEquals(2, networks.size());
    }
}

