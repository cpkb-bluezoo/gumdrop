/*
 * DnsTsigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Collections;

import static org.junit.Assert.*;

public class DnsTsigTest {

    @Test
    public void testSignVerifyRoundTripSha256() throws Exception {
        TsigKey key = TsigKey.fromBase64("update-key.", TsigKey.HMAC_SHA256,
                "c2VjcmV0"); // "secret"
        DnsMessage update = DnsMessage.createDynamicUpdate(77,
                Collections.singletonList(DnsResourceRecord.soa("example.com.", 300,
                        "ns1.example.com.", "host.example.com.", 1, 7200, 3600,
                        1209600, 300)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(DnsResourceRecord.a("x.example.com.", 60,
                        java.net.InetAddress.getByName("192.0.2.5"))));
        long now = System.currentTimeMillis() / 1000L;
        DnsMessage signed = DnsTsig.sign(update, key, now, 300);
        DnsResourceRecord tsig = signed.getTsigRecord();
        assertNotNull(tsig);
        assertEquals(DnsType.TSIG, tsig.getType());
        assertEquals(DnsClass.ANY, tsig.getDNSClass());
        assertEquals(0, tsig.getTTL());
        assertTrue(TsigAlgorithm.matchesKey(key,
                DnsTsigTest.algorithmFromRdata(tsig.getRData())));
        assertTrue(DnsTsig.verify(signed, key, false));
    }

    @Test
    public void testSignVerifyRoundTripMd5WireName() throws Exception {
        TsigKey key = TsigKey.fromBase64("key.", TsigKey.HMAC_MD5, "c2VjcmV0");
        DnsMessage query = DnsMessage.createQuery(42, "example.com.", DnsType.SOA);
        DnsMessage signed = DnsTsig.sign(query, key,
                System.currentTimeMillis() / 1000L, 300);
        assertEquals(TsigAlgorithm.WIRE_HMAC_MD5,
                DnsTsigTest.algorithmFromRdata(signed.getTsigRecord().getRData()));
        assertTrue(DnsTsig.verify(signed, key, false));
    }

    @Test
    public void testVerifyRejectsTamperedMessage() throws Exception {
        TsigKey key = TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "c2VjcmV0");
        DnsMessage signed = DnsTsig.sign(DnsMessage.createQuery(1, "a.test.", DnsType.A),
                key, System.currentTimeMillis() / 1000L, 300);
        DnsQuestion q = signed.getQuestions().get(0);
        DnsMessage tampered = new DnsMessage(signed.getId(), signed.getFlags(),
                Collections.singletonList(new DnsQuestion("evil.test.", q.getType(),
                        q.getDNSClass())),
                signed.getAnswers(), signed.getAuthorities(),
                signed.getAdditionals());
        assertFalse(DnsTsig.verify(tampered, key, false));
    }

    @Test
    public void testUpdateResponseSignVerify() throws Exception {
        TsigKey key = TsigKey.fromBase64("update-key.", TsigKey.HMAC_SHA256,
                "c2VjcmV0");
        DnsMessage update = DnsMessage.createDynamicUpdate(88,
                Collections.singletonList(DnsResourceRecord.soa("example.com.", 300,
                        "ns1.example.com.", "host.example.com.", 1, 7200, 3600,
                        1209600, 300)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(DnsResourceRecord.a("y.example.com.", 60,
                        java.net.InetAddress.getByName("192.0.2.6"))));
        long now = System.currentTimeMillis() / 1000L;
        DnsMessage signedRequest = DnsTsig.sign(update, key, now, 300);
        DnsMessage unsignedResponse = update.createAuthoritativeEmptyResponse(
                DnsMessage.RCODE_NOERROR);
        DnsMessage signedResponse = DnsTsig.signResponse(unsignedResponse, key,
                signedRequest, now + 1, 300);
        assertNotNull(signedResponse.getTsigRecord());
        assertTrue(DnsTsig.verifyResponse(signedResponse, key, signedRequest));
    }

    @Test
    public void testTransferResponseSequenceSignVerify() throws Exception {
        TsigKey key = TsigKey.fromBase64("xfr.", TsigKey.HMAC_SHA256, "c2VjcmV0");
        DnsMessage request = DnsTsig.sign(DnsMessage.createQuery(12, "example.com.",
                DnsType.AXFR), key);
        DnsQuestion q = request.getQuestions().get(0);
        DnsResourceRecord soa = DnsResourceRecord.soa("example.com.", 300,
                "ns1.example.com.", "host.example.com.", 1, 7200, 3600,
                1209600, 300);
        java.util.List<DnsMessage> unsigned = new java.util.ArrayList<DnsMessage>();
        unsigned.add(new DnsMessage(12, DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                java.util.Collections.singletonList(q),
                java.util.Collections.singletonList(soa),
                java.util.Collections.<DnsResourceRecord>emptyList(),
                java.util.Collections.<DnsResourceRecord>emptyList()));
        unsigned.add(new DnsMessage(12, DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                java.util.Collections.singletonList(q),
                java.util.Collections.singletonList(soa),
                java.util.Collections.<DnsResourceRecord>emptyList(),
                java.util.Collections.<DnsResourceRecord>emptyList()));
        java.util.List<DnsMessage> signed = DnsTsig.signResponseSequence(unsigned, key,
                request);
        assertEquals(2, signed.size());
        assertTrue(DnsTsig.verifyResponseSequence(signed, key, request));
    }

    @Test
    public void testTsigRdataFieldOrder() throws Exception {
        TsigKey key = TsigKey.fromBase64("k.", TsigKey.HMAC_SHA256, "YWJj"); // abc
        DnsMessage signed = DnsTsig.sign(DnsMessage.createQuery(5, "z.test.", DnsType.NS),
                key, 1000L, 60);
        ByteBuffer buf = ByteBuffer.wrap(signed.getTsigRecord().getRData());
        ByteBuffer orig = buf.duplicate();
        DnsMessage.decodeName(buf, orig);
        long time = readUint48(buf);
        assertEquals(1000L, time);
        int fudge = buf.getShort() & 0xFFFF;
        assertEquals(60, fudge);
        int macLen = buf.getShort() & 0xFFFF;
        assertTrue(macLen > 0);
        buf.position(buf.position() + macLen);
        assertEquals(5, buf.getShort() & 0xFFFF); // original ID
        assertEquals(0, buf.getShort() & 0xFFFF); // error
        assertEquals(0, buf.getShort() & 0xFFFF); // other len
    }

    static String algorithmFromRdata(byte[] rdata) {
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        ByteBuffer orig = buf.duplicate();
        return TsigAlgorithm.canonicalWire(DnsMessage.decodeName(buf, orig));
    }

    private static long readUint48(ByteBuffer buf) {
        long v = 0;
        for (int i = 0; i < 6; i++) {
            v = (v << 8) | (buf.get() & 0xFF);
        }
        return v;
    }
}
