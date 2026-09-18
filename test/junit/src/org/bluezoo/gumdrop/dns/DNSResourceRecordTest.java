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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnsResourceRecord}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResourceRecordTest {

    @Test
    public void testARecord() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.100");
        DnsResourceRecord record = DnsResourceRecord.a("example.com", 300, ip);
        
        assertEquals("example.com", record.getName());
        assertEquals(DnsType.A, record.getType());
        assertEquals(DnsClass.IN, record.getDNSClass());
        assertEquals(300, record.getTTL());
        assertEquals(4, record.getRData().length);
        
        InetAddress parsed = record.getAddress();
        assertEquals(ip, parsed);
    }
    
    @Test
    public void testAAAARecord() throws Exception {
        InetAddress ip = InetAddress.getByName("2001:db8::1");
        DnsResourceRecord record = DnsResourceRecord.aaaa("example.com", 600, ip);
        
        assertEquals(DnsType.AAAA, record.getType());
        assertEquals(16, record.getRData().length);
        assertEquals(ip, record.getAddress());
    }
    
    @Test
    public void testCNAMERecord() {
        DnsResourceRecord record = DnsResourceRecord.cname("www.example.com", 3600, "example.com");
        
        assertEquals(DnsType.CNAME, record.getType());
        assertEquals("example.com", record.getTargetName());
    }
    
    @Test
    public void testPTRRecord() {
        DnsResourceRecord record = DnsResourceRecord.ptr("100.1.168.192.in-addr.arpa", 3600, "host.example.com");
        
        assertEquals(DnsType.PTR, record.getType());
        assertEquals("host.example.com", record.getTargetName());
    }
    
    @Test
    public void testNSRecord() {
        DnsResourceRecord record = DnsResourceRecord.ns("example.com", 86400, "ns1.example.com");
        
        assertEquals(DnsType.NS, record.getType());
        assertEquals("ns1.example.com", record.getTargetName());
    }
    
    @Test
    public void testMXRecord() {
        DnsResourceRecord record = DnsResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        
        assertEquals(DnsType.MX, record.getType());
        assertEquals(10, record.getMXPreference());
        assertEquals("mail.example.com", record.getMXExchange());
    }
    
    @Test
    public void testMXRecordOrdering() {
        DnsResourceRecord mx1 = DnsResourceRecord.mx("example.com", 3600, 10, "mail1.example.com");
        DnsResourceRecord mx2 = DnsResourceRecord.mx("example.com", 3600, 20, "mail2.example.com");
        
        assertTrue(mx1.getMXPreference() < mx2.getMXPreference());
    }
    
    @Test
    public void testTXTRecord() {
        DnsResourceRecord record = DnsResourceRecord.txt("example.com", 300, "v=spf1 include:_spf.example.com ~all");
        
        assertEquals(DnsType.TXT, record.getType());
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
        
        DnsResourceRecord record = DnsResourceRecord.txt("example.com", 300, longText);
        assertEquals(longText, record.getText());
    }
    
    @Test
    public void testSOARecord() {
        DnsResourceRecord record = DnsResourceRecord.soa(
                "example.com", 3600,
                "ns1.example.com",      // mname
                "admin.example.com",    // rname (admin@example.com)
                2024010101,             // serial
                7200,                   // refresh
                3600,                   // retry
                1209600,                // expire
                86400                   // minimum
        );
        
        assertEquals(DnsType.SOA, record.getType());
        assertEquals("example.com", record.getName());
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetAddressOnNonAddressRecord() {
        DnsResourceRecord record = DnsResourceRecord.txt("example.com", 300, "test");
        record.getAddress();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetTargetNameOnNonNameRecord() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord record = DnsResourceRecord.a("example.com", 300, ip);
        record.getTargetName();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetTextOnNonTXTRecord() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord record = DnsResourceRecord.a("example.com", 300, ip);
        record.getText();
    }
    
    @Test(expected = IllegalStateException.class)
    public void testGetMXPreferenceOnNonMXRecord() {
        DnsResourceRecord record = DnsResourceRecord.txt("example.com", 300, "test");
        record.getMXPreference();
    }
    
    @Test
    public void testRDataDefensiveCopy() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord record = DnsResourceRecord.a("example.com", 300, ip);
        
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
        DnsResourceRecord record1 = DnsResourceRecord.a("example.com", 300, ip);
        DnsResourceRecord record2 = DnsResourceRecord.a("example.com", 300, ip);
        DnsResourceRecord record3 = DnsResourceRecord.a("example.com", 600, ip);
        
        assertEquals(record1, record2);
        assertNotEquals(record1, record3); // Different TTL
    }
    
    @Test
    public void testHashCode() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord record1 = DnsResourceRecord.a("example.com", 300, ip);
        DnsResourceRecord record2 = DnsResourceRecord.a("example.com", 300, ip);
        
        assertEquals(record1.hashCode(), record2.hashCode());
    }
    
    @Test
    public void testToStringA() throws Exception {
        InetAddress ip = InetAddress.getByName("93.184.216.34");
        DnsResourceRecord record = DnsResourceRecord.a("example.com", 300, ip);
        
        String str = record.toString();
        assertTrue(str.contains("example.com"));
        assertTrue(str.contains("300"));
        assertTrue(str.contains("IN"));
        assertTrue(str.contains("A"));
        assertTrue(str.contains("93.184.216.34"));
    }
    
    @Test
    public void testToStringMX() {
        DnsResourceRecord record = DnsResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        
        String str = record.toString();
        assertTrue(str.contains("MX"));
        assertTrue(str.contains("10"));
        assertTrue(str.contains("mail.example.com"));
    }

    // -- SRV record tests (RFC 2782) --

    @Test
    public void testSRVRecord() {
        DnsResourceRecord srv = DnsResourceRecord.srv(
                "_sip._tcp.example.com", 3600, 10, 60, 5060, "sip.example.com");

        assertEquals(DnsType.SRV, srv.getType());
        assertEquals("_sip._tcp.example.com", srv.getName());
        assertEquals(10, srv.getSRVPriority());
        assertEquals(60, srv.getSRVWeight());
        assertEquals(5060, srv.getSRVPort());
        assertEquals("sip.example.com", srv.getSRVTarget());
    }

    @Test
    public void testSRVRecordToString() {
        DnsResourceRecord srv = DnsResourceRecord.srv(
                "_http._tcp.example.com", 300, 0, 5, 80, "www.example.com");

        String str = srv.toString();
        assertTrue(str.contains("SRV"));
        assertTrue(str.contains("80"));
        assertTrue(str.contains("www.example.com"));
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSRVPriorityOnNonSRV() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSRVPriority();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSRVWeightOnNonSRV() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSRVWeight();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSRVPortOnNonSRV() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSRVPort();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSRVTargetOnNonSRV() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSRVTarget();
    }

    // -- EDNS0 OPT record tests (RFC 6891) --

    @Test
    public void testOptRecord() {
        DnsResourceRecord opt = DnsResourceRecord.opt(4096);

        assertEquals(DnsType.OPT, opt.getType());
        assertEquals("", opt.getName());
        assertEquals(4096, opt.getUdpPayloadSize());
        assertEquals(0, opt.getTTL());
        assertEquals(0, opt.getRData().length);
    }

    @Test
    public void testOptRecordWithOptionData() {
        byte[] optionData = { 0, 10, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8 };
        DnsResourceRecord opt = DnsResourceRecord.opt(4096, optionData);

        assertEquals(DnsType.OPT, opt.getType());
        assertEquals(4096, opt.getUdpPayloadSize());
        assertArrayEquals(optionData, opt.getRData());
    }

    @Test
    public void testOptRecordRawClassIsPayloadSize() {
        DnsResourceRecord opt = DnsResourceRecord.opt(1232);
        assertEquals(1232, opt.getRawClass());
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUdpPayloadSizeOnNonOptRecord() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord a = DnsResourceRecord.a("example.com", 300, ip);
        a.getUdpPayloadSize();
    }

    // -- SVCB/HTTPS record tests (RFC 9460) --

    @Test
    public void testHttpsRecordServiceForm() {
        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.encodeSVCBAlpn(Arrays.asList("h3", "h2")));
        DnsResourceRecord https = DnsResourceRecord.https(
                "example.com", 3600, 1, ".", params);

        assertEquals(DnsType.HTTPS, https.getType());
        assertEquals("example.com", https.getName());
        assertEquals(1, https.getSVCBPriority());
        assertFalse(https.isSVCBAliasForm());
        // "." (root, "same as owner name") round-trips as "" -- matches
        // this codebase's DnsMessage.decodeName convention for the root name.
        assertEquals("", https.getSVCBTargetName());
        assertEquals(Arrays.asList("h3", "h2"), https.getSVCBAlpnProtocols());
        assertEquals(-1, https.getSVCBPort());
    }

    @Test
    public void testHttpsRecordAliasForm() {
        DnsResourceRecord alias = DnsResourceRecord.https(
                "example.com", 3600, 0, "target.example.net", null);

        assertTrue(alias.isSVCBAliasForm());
        assertEquals("target.example.net", alias.getSVCBTargetName());
        assertTrue(alias.getSVCBAlpnProtocols().isEmpty());
    }

    @Test
    public void testHttpsRecordPortParam() {
        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.encodeSVCBAlpn(Arrays.asList("h3")));
        params.put(DnsResourceRecord.SVCB_PARAM_PORT,
                DnsResourceRecord.encodeSVCBPort(8443));
        DnsResourceRecord https = DnsResourceRecord.https(
                "example.com", 3600, 1, ".", params);

        assertEquals(8443, https.getSVCBPort());
        assertEquals(Arrays.asList("h3"), https.getSVCBAlpnProtocols());
    }

    @Test
    public void testSvcbRecordDohPathParam() {
        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.encodeSVCBAlpn(Arrays.asList("h2")));
        params.put(DnsResourceRecord.SVCB_PARAM_DOHPATH,
                DnsResourceRecord.encodeSVCBDohPath("/dns-query{?dns}"));
        DnsResourceRecord svcb = DnsResourceRecord.svcb(
                "_dns.resolver.arpa", 300, 1, ".", params);

        assertEquals("/dns-query{?dns}", svcb.getSVCBDohPath());
        assertEquals(Arrays.asList("h2"), svcb.getSVCBAlpnProtocols());
    }

    @Test
    public void testSvcbRecordDohPathAbsent() {
        DnsResourceRecord svcb = DnsResourceRecord.svcb(
                "_dns.resolver.arpa", 300, 1, ".", null);
        assertNull(svcb.getSVCBDohPath());
    }

    @Test
    public void testSvcbRecordType() {
        DnsResourceRecord svcb = DnsResourceRecord.svcb(
                "example.com", 3600, 1, ".", null);
        assertEquals(DnsType.SVCB, svcb.getType());
    }

    @Test
    public void testHttpsRecordParamsInIncreasingKeyOrder() {
        // RFC 9460 section 2.2: SvcParams must be written in strictly
        // increasing key order regardless of insertion order.
        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DnsResourceRecord.SVCB_PARAM_PORT,
                DnsResourceRecord.encodeSVCBPort(443));
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.encodeSVCBAlpn(Arrays.asList("h3")));
        DnsResourceRecord https = DnsResourceRecord.https(
                "example.com", 3600, 1, ".", params);

        Map<Integer, byte[]> parsed = https.getSVCBParams();
        List<Integer> keys = new java.util.ArrayList<>(parsed.keySet());
        assertEquals(Arrays.asList(
                DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.SVCB_PARAM_PORT), keys);
    }

    @Test
    public void testHttpsRecordNoParams() {
        DnsResourceRecord https = DnsResourceRecord.https(
                "example.com", 3600, 1, ".", null);
        assertTrue(https.getSVCBParams().isEmpty());
        assertEquals(-1, https.getSVCBPort());
        assertTrue(https.getSVCBAlpnProtocols().isEmpty());
    }

    @Test
    public void testToStringHttps() {
        Map<Integer, byte[]> params = new LinkedHashMap<>();
        params.put(DnsResourceRecord.SVCB_PARAM_ALPN,
                DnsResourceRecord.encodeSVCBAlpn(Arrays.asList("h3")));
        DnsResourceRecord https = DnsResourceRecord.https(
                "example.com", 3600, 1, ".", params);

        String str = https.toString();
        assertTrue(str.contains("HTTPS"));
        assertTrue(str.contains("h3"));
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSVCBPriorityOnNonSVCB() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSVCBPriority();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSVCBTargetNameOnNonSVCB() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSVCBTargetName();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetSVCBParamsOnNonSVCB() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord.a("example.com", 300, ip).getSVCBParams();
    }

    @Test
    public void testCacheFlushDefaultsFalse() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord rr = DnsResourceRecord.a("example.com", 300, ip);

        assertFalse(rr.isCacheFlush());
    }

    @Test
    public void testCacheFlushBitSetViaRawClass() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        int rawClass = DnsClass.IN.getValue() | DnsResourceRecord.CACHE_FLUSH_BIT;
        DnsResourceRecord rr = new DnsResourceRecord("example.local", DnsType.A,
                DnsType.A.getValue(), DnsClass.IN, rawClass, 120, ip.getAddress());

        assertTrue(rr.isCacheFlush());
        assertEquals(DnsClass.IN, rr.getDNSClass());
    }

    @Test
    public void testWithCacheFlushSetsOnlyTheBit() throws Exception {
        InetAddress ip = InetAddress.getByName("1.2.3.4");
        DnsResourceRecord rr = DnsResourceRecord.a("example.local", 300, ip);

        DnsResourceRecord flushed = rr.withCacheFlush();

        assertFalse(rr.isCacheFlush());
        assertTrue(flushed.isCacheFlush());
        assertEquals(rr.getName(), flushed.getName());
        assertEquals(rr.getType(), flushed.getType());
        assertEquals(rr.getTTL(), flushed.getTTL());
        assertArrayEquals(rr.getRData(), flushed.getRData());
    }

    @Test
    public void testMultiStringTxt() {
        DnsResourceRecord rr = DnsResourceRecord.txt("_http._tcp.local", 4500,
                Arrays.asList("path=/", "version=1.0"));

        assertEquals(DnsType.TXT, rr.getType());
        byte[] rdata = rr.getRData();
        // "path=/" (6 bytes) + "version=1.0" (11 bytes), each its own
        // length-prefixed character-string
        assertEquals(1 + 6 + 1 + 11, rdata.length);
        assertEquals(6, rdata[0] & 0xFF);
        assertEquals(11, rdata[7] & 0xFF);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMultiStringTxtRejectsOverlongString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append('a');
        }
        DnsResourceRecord.txt("example.local", 4500, Arrays.asList(sb.toString()));
    }

    @Test
    public void testTLSARecord() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0xAB);
        DnsResourceRecord rr = DnsResourceRecord.tlsa(
                "_25._tcp.mail.example.com", 3600, 3, 1, 1, hash);

        assertEquals(DnsType.TLSA, rr.getType());
        assertEquals(3, rr.getTLSACertUsage());
        assertEquals(1, rr.getTLSASelector());
        assertEquals(1, rr.getTLSAMatchingType());
        assertArrayEquals(hash, rr.getTLSACertificateAssociationData());
        assertEquals(3 + hash.length, rr.getRData().length);
    }

    @Test
    public void testToStringTLSA() {
        byte[] hash = new byte[32];
        DnsResourceRecord rr = DnsResourceRecord.tlsa(
                "_443._tcp.example.com", 3600, 2, 0, 2, hash);
        String s = rr.toString();
        assertTrue(s.contains("2 0 2"));
        assertTrue(s.contains("[32 bytes]"));
    }

    @Test(expected = IllegalStateException.class)
    public void testGetTLSACertUsageOnNonTLSA() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.1");
        DnsResourceRecord.a("example.com", 300, ip).getTLSACertUsage();
    }
}

