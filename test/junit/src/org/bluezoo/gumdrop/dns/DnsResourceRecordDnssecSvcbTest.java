/*
 * DnsResourceRecordDnssecSvcbTest.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the SVCB/HTTPS, DNSSEC, TLSA, OPT and HINFO record
 * factories and accessors of {@link DnsResourceRecord}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsResourceRecordDnssecSvcbTest {

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(len);
        for (byte[] p : parts) {
            buf.put(p);
        }
        return buf.array();
    }

    @Test
    public void httpsRecordRoundTripsParams() {
        List<String> protocols = new ArrayList<String>();
        protocols.add("h3");
        protocols.add("h2");
        Map<Integer, byte[]> params = new HashMap<Integer, byte[]>();
        params.put(7, DnsResourceRecord.encodeSVCBDohPath("/dns-query{?dns}"));
        params.put(3, DnsResourceRecord.encodeSVCBPort(8443));
        params.put(1, DnsResourceRecord.encodeSVCBAlpn(protocols));
        params.put(5, new byte[] { 1, 2, 3 });

        DnsResourceRecord rr = DnsResourceRecord.https("example.com.", 300, 1, "svc.example.com.", params);
        assertEquals(DnsType.HTTPS, rr.getType());
        assertEquals(1, rr.getSVCBPriority());
        assertFalse(rr.isSVCBAliasForm());
        assertEquals("svc.example.com", rr.getSVCBTargetName());
        assertEquals(protocols, rr.getSVCBAlpnProtocols());
        assertEquals(8443, rr.getSVCBPort());
        assertArrayEquals(new byte[] { 1, 2, 3 }, rr.getSVCBEchConfigList());
        assertEquals("/dns-query{?dns}", rr.getSVCBDohPath());
        assertEquals(4, rr.getSVCBParams().size());
        String text = rr.toString();
        assertTrue(text.contains("alpn=\"h3,h2\""));
        assertTrue(text.contains("port=8443"));
    }

    @Test
    public void svcbAliasFormHasNoParams() {
        DnsResourceRecord rr = DnsResourceRecord.svcb("example.com.", 60, 0, "alias.example.net.", null);
        assertTrue(rr.isSVCBAliasForm());
        assertTrue(rr.getSVCBParams().isEmpty());
        assertTrue(rr.getSVCBAlpnProtocols().isEmpty());
        assertEquals(-1, rr.getSVCBPort());
        assertNull(rr.getSVCBEchConfigList());
        assertNull(rr.getSVCBDohPath());
    }

    @Test(expected = IllegalStateException.class)
    public void svcbAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getSVCBPriority();
    }

    @Test
    public void dnskeyAccessors() {
        byte[] key = new byte[] { 1, 2, 3, 4, 5 };
        DnsResourceRecord rr = DnsResourceRecord.dnskey("example.com.", 3600, 257, 13, key);
        assertEquals(257, rr.getDNSKEYFlags());
        assertEquals(3, rr.getDNSKEYProtocol());
        assertEquals(13, rr.getDNSKEYAlgorithm());
        assertArrayEquals(key, rr.getDNSKEYPublicKey());
        assertTrue(rr.isDNSKEYZoneKey());
        assertTrue(rr.isDNSKEYSecureEntryPoint());
        assertFalse(rr.isDNSKEYRevoked());
        assertTrue(rr.computeKeyTag() >= 0);
        assertTrue(rr.toString().contains("tag="));

        DnsResourceRecord zsk = DnsResourceRecord.dnskey("example.com.", 3600, 256 | 0x80, 8, key);
        assertFalse(zsk.isDNSKEYSecureEntryPoint());
        assertTrue(zsk.isDNSKEYRevoked());
    }

    @Test(expected = IllegalStateException.class)
    public void dnskeyAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getDNSKEYFlags();
    }

    @Test
    public void rrsigAccessors() {
        byte[] sig = new byte[] { 9, 8, 7 };
        DnsResourceRecord rr = DnsResourceRecord.rrsig("example.com.", 300, DnsType.A, 13, 2,
            3600, 2000000000L, 1900000000L, 12345, "example.com.", sig);
        assertEquals(DnsType.A.getValue(), rr.getRRSIGTypeCovered());
        assertEquals(13, rr.getRRSIGAlgorithm());
        assertEquals(2, rr.getRRSIGLabels());
        assertEquals(3600, rr.getRRSIGOriginalTTL());
        assertEquals(2000000000L, rr.getRRSIGExpiration());
        assertEquals(1900000000L, rr.getRRSIGInception());
        assertEquals(12345, rr.getRRSIGKeyTag());
        assertEquals("example.com", rr.getRRSIGSignerName());
        assertArrayEquals(sig, rr.getRRSIGSignature());
        byte[] header = rr.getRRSIGHeaderBytes();
        assertEquals(rr.getRData().length - sig.length, header.length);
        assertTrue(rr.toString().contains("tag=12345"));
    }

    @Test(expected = IllegalStateException.class)
    public void rrsigAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getRRSIGKeyTag();
    }

    @Test
    public void dsAccessorsFromRawRdata() {
        byte[] rdata = new byte[] { 0x30, 0x39, 13, 2, (byte) 0xAA, (byte) 0xBB };
        DnsResourceRecord rr = new DnsResourceRecord("example.com.", DnsType.DS, DnsClass.IN, 60, rdata);
        assertEquals(12345, rr.getDSKeyTag());
        assertEquals(13, rr.getDSAlgorithm());
        assertEquals(2, rr.getDSDigestType());
        assertArrayEquals(new byte[] { (byte) 0xAA, (byte) 0xBB }, rr.getDSDigest());
        assertTrue(rr.toString().contains("12345"));
    }

    @Test(expected = IllegalStateException.class)
    public void dsAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getDSKeyTag();
    }

    @Test
    public void nsecAccessors() {
        byte[] next = DnsMessage.encodeName("next.example.com.");
        byte[] bitmap = new byte[] { 0, 1, 0x60 };
        DnsResourceRecord rr = new DnsResourceRecord("example.com.", DnsType.NSEC, DnsClass.IN, 60,
            concat(next, bitmap));
        assertEquals("next.example.com", rr.getNSECNextDomainName());
        List<Integer> types = rr.getNSECTypeBitMaps();
        assertEquals(2, types.size());
        assertEquals(Integer.valueOf(1), types.get(0));
        assertEquals(Integer.valueOf(2), types.get(1));
        assertTrue(rr.toString().contains("next.example.com"));
    }

    @Test(expected = IllegalStateException.class)
    public void nsecAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getNSECNextDomainName();
    }

    @Test
    public void nsec3Accessors() {
        byte[] salt = new byte[] { 5, 6 };
        byte[] hash = new byte[] { 1, 2, 3 };
        byte[] head = new byte[] { 1, 0, 0, 10, 2, 5, 6, 3, 1, 2, 3 };
        byte[] bitmap = new byte[] { 0, 1, (byte) 0x80 };
        DnsResourceRecord rr = new DnsResourceRecord("h.example.com.", DnsType.NSEC3, DnsClass.IN, 60,
            concat(head, bitmap));
        assertEquals(1, rr.getNSEC3HashAlgorithm());
        assertEquals(0, rr.getNSEC3Flags());
        assertEquals(10, rr.getNSEC3Iterations());
        assertArrayEquals(salt, rr.getNSEC3Salt());
        assertArrayEquals(hash, rr.getNSEC3NextHashedOwner());
        List<Integer> types = rr.getNSEC3TypeBitMaps();
        assertEquals(1, types.size());
        assertEquals(Integer.valueOf(0), types.get(0));
        assertTrue(rr.toString().contains(" 10"));
    }

    @Test
    public void nsec3EmptySalt() {
        byte[] rdata = new byte[] { 1, 1, 0, 0, 0, 1, 9 };
        DnsResourceRecord rr = new DnsResourceRecord("h.", DnsType.NSEC3, DnsClass.IN, 60, rdata);
        assertEquals(0, rr.getNSEC3Salt().length);
        assertEquals(1, rr.getNSEC3NextHashedOwner().length);
    }

    @Test(expected = IllegalStateException.class)
    public void nsec3AccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getNSEC3Flags();
    }

    @Test
    public void nsec3ParamAccessors() {
        byte[] rdata = new byte[] { 1, 0, 0, 5, 2, 7, 8 };
        DnsResourceRecord rr = new DnsResourceRecord("example.com.", DnsType.NSEC3PARAM, DnsClass.IN, 60, rdata);
        assertEquals(1, rr.getNSEC3PARAMHashAlgorithm());
        assertEquals(0, rr.getNSEC3PARAMFlags());
        assertEquals(5, rr.getNSEC3PARAMIterations());
        assertArrayEquals(new byte[] { 7, 8 }, rr.getNSEC3PARAMSalt());
        assertTrue(rr.toString().contains(" 5"));
    }

    @Test(expected = IllegalStateException.class)
    public void nsec3ParamAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getNSEC3PARAMFlags();
    }

    @Test
    public void tlsaAccessors() {
        byte[] data = new byte[] { 1, 2, 3, 4 };
        DnsResourceRecord rr = DnsResourceRecord.tlsa("_443._tcp.example.com.", 60, 3, 1, 1, data);
        assertEquals(3, rr.getTLSACertUsage());
        assertEquals(1, rr.getTLSASelector());
        assertEquals(1, rr.getTLSAMatchingType());
        assertArrayEquals(data, rr.getTLSACertificateAssociationData());
        assertTrue(rr.toString().contains("[4 bytes]"));
    }

    @Test(expected = IllegalStateException.class)
    public void tlsaAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getTLSASelector();
    }

    @Test
    public void optRecordCarriesPayloadSizeAndFlags() {
        DnsResourceRecord plain = DnsResourceRecord.opt(1232);
        assertEquals(1232, plain.getUdpPayloadSize());
        assertEquals(0, plain.getEDNSFlags());
        DnsResourceRecord flagged = DnsResourceRecord.opt(4096, 0x8000, new byte[] { 0, 10, 0, 0 });
        assertEquals(4096, flagged.getUdpPayloadSize());
        assertEquals(0x8000, flagged.getEDNSFlags());
        DnsResourceRecord withData = DnsResourceRecord.opt(512, new byte[] { 1 });
        assertEquals(1, withData.getRData().length);
    }

    @Test(expected = IllegalStateException.class)
    public void optAccessorOnWrongTypeFails() {
        DnsResourceRecord.txt("a.", 1, "x").getEDNSFlags();
    }

    @Test
    public void hinfoEncodesTwoCharacterStrings() {
        DnsResourceRecord rr = DnsResourceRecord.hinfo("h.", 60, "AMD64", "Linux");
        byte[] rdata = rr.getRData();
        assertEquals(5, rdata[0]);
        assertEquals(2 + 5 + 5, rdata.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void hinfoRejectsOverlongString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append('x');
        }
        DnsResourceRecord.hinfo("h.", 60, sb.toString(), "os");
    }

    @Test
    public void cacheFlushCopy() {
        DnsResourceRecord rr = DnsResourceRecord.txt("a.", 1, "x");
        assertFalse(rr.isCacheFlush());
        assertTrue(rr.withCacheFlush().isCacheFlush());
    }

    @Test
    public void unknownTypeToStringShowsRawType() {
        DnsResourceRecord rr = new DnsResourceRecord("a.", null, 65280, DnsClass.IN, 0, 10, new byte[3]);
        String text = rr.toString();
        assertTrue(text.contains("TYPE65280"));
        assertTrue(text.contains("[3 bytes]"));
    }
}
