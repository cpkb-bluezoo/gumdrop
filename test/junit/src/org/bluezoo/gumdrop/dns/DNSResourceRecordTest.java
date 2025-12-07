/*
 * DNSResourceRecordTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSResourceRecord}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResourceRecordTest {

    @Test
    public void testARecord() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.100");
        DNSResourceRecord record = DNSResourceRecord.a("example.com", 300, ip);
        
        assertEquals("example.com", record.getName());
        assertEquals(DNSType.A, record.getType());
        assertEquals(DNSClass.IN, record.getDNSClass());
        assertEquals(300, record.getTTL());
        assertEquals(4, record.getRData().length);
        
        InetAddress parsed = record.getAddress();
        assertEquals(ip, parsed);
    }
    
    @Test
    public void testAAAARecord() throws Exception {
        InetAddress ip = InetAddress.getByName("2001:db8::1");
        DNSResourceRecord record = DNSResourceRecord.aaaa("example.com", 600, ip);
        
        assertEquals(DNSType.AAAA, record.getType());
        assertEquals(16, record.getRData().length);
        assertEquals(ip, record.getAddress());
    }
    
    @Test
    public void testCNAMERecord() {
        DNSResourceRecord record = DNSResourceRecord.cname("www.example.com", 3600, "example.com");
        
        assertEquals(DNSType.CNAME, record.getType());
        assertEquals("example.com", record.getTargetName());
    }
    
    @Test
    public void testPTRRecord() {
        DNSResourceRecord record = DNSResourceRecord.ptr("100.1.168.192.in-addr.arpa", 3600, "host.example.com");
        
        assertEquals(DNSType.PTR, record.getType());
        assertEquals("host.example.com", record.getTargetName());
    }
    
    @Test
    public void testNSRecord() {
        DNSResourceRecord record = DNSResourceRecord.ns("example.com", 86400, "ns1.example.com");
        
        assertEquals(DNSType.NS, record.getType());
        assertEquals("ns1.example.com", record.getTargetName());
    }
    
    @Test
    public void testMXRecord() {
        DNSResourceRecord record = DNSResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        
        assertEquals(DNSType.MX, record.getType());
        assertEquals(10, record.getMXPreference());
        assertEquals("mail.example.com", record.getMXExchange());
    }
    
    @Test
    public void testMXRecordOrdering() {
        DNSResourceRecord mx1 = DNSResourceRecord.mx("example.com", 3600, 10, "mail1.example.com");
        DNSResourceRecord mx2 = DNSResourceRecord.mx("example.com", 3600, 20, "mail2.example.com");
        
        assertTrue(mx1.getMXPreference() < mx2.getMXPreference());
    }
    
    @Test
    public void testTXTRecord() {
        DNSResourceRecord record = DNSResourceRecord.txt("example.com", 300, "v=spf1 include:_spf.example.com ~all");
        
        assertEquals(DNSType.TXT, record.getType());
        assertEquals("v=spf1 include:_spf.example.com ~all", record.getText());
    }
    
    @Test
    public void testTXTRecordLongText() {
        // Create a long text that exceeds 255 bytes (tests chunking)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("x");
        }
        String longText = sb.toString();
        
        DNSResourceRecord record = DNSResourceRecord.txt("example.com", 300, longText);
        assertEquals(longText, record.getText());
    }
    
    @Test
    public void testSOARecord() {
        DNSResourceRecord record = DNSResourceRecord.soa(
                "example.com", 3600,
                "ns1.example.com",      // mname
                "admin.example.com",    // rname (admin@example.com)
                2024010101,             // serial
                7200,                   // refresh
                3600,                   // retry
                1209600,                // expire
                86400                   // minimum
        );
        
        assertEquals(DNSType.SOA, record.getType());
        assertEquals("example.com", record.getName());
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetAddressOnNonAddressRecord() {
        DNSResourceRecord record = DNSResourceRecord.txt("example.com", 300, "test");
        record.getAddress();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetTargetNameOnNonNameRecord() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DNSResourceRecord record = DNSResourceRecord.a("example.com", 300, ip);
        record.getTargetName();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetTextOnNonTXTRecord() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DNSResourceRecord record = DNSResourceRecord.a("example.com", 300, ip);
        record.getText();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetMXPreferenceOnNonMXRecord() {
        DNSResourceRecord record = DNSResourceRecord.txt("example.com", 300, "test");
        record.getMXPreference();
    }
    
    @Test
    public void testRDataDefensiveCopy() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DNSResourceRecord record = DNSResourceRecord.a("example.com", 300, ip);
        
        byte[] rdata1 = record.getRData();
        byte[] rdata2 = record.getRData();
        
        // Should be equal
        assertArrayEquals(rdata1, rdata2);
        
        // But not same reference
        assertNotSame(rdata1, rdata2);
        
        // Modifying one shouldn't affect the other
        rdata1[0] = 99;
        assertNotEquals(rdata1[0], record.getRData()[0]);
    }
    
    @Test
    public void testEquals() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DNSResourceRecord record1 = DNSResourceRecord.a("example.com", 300, ip);
        DNSResourceRecord record2 = DNSResourceRecord.a("example.com", 300, ip);
        DNSResourceRecord record3 = DNSResourceRecord.a("example.com", 600, ip);
        
        assertEquals(record1, record2);
        assertNotEquals(record1, record3); // Different TTL
    }
    
    @Test
    public void testHashCode() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DNSResourceRecord record1 = DNSResourceRecord.a("example.com", 300, ip);
        DNSResourceRecord record2 = DNSResourceRecord.a("example.com", 300, ip);
        
        assertEquals(record1.hashCode(), record2.hashCode());
    }
    
    @Test
    public void testToStringA() throws Exception {
        InetAddress ip = InetAddress.getByName("93.184.216.34");
        DNSResourceRecord record = DNSResourceRecord.a("example.com", 300, ip);
        
        String str = record.toString();
        assertTrue(str.contains("example.com"));
        assertTrue(str.contains("300"));
        assertTrue(str.contains("IN"));
        assertTrue(str.contains("A"));
        assertTrue(str.contains("93.184.216.34"));
    }
    
    @Test
    public void testToStringMX() {
        DNSResourceRecord record = DNSResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        
        String str = record.toString();
        assertTrue(str.contains("MX"));
        assertTrue(str.contains("10"));
        assertTrue(str.contains("mail.example.com"));
    }
}

