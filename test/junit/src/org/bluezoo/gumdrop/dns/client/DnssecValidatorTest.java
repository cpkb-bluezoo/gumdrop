/*
 * DnssecValidatorTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.*;
import static org.junit.Assert.*;

/**
 * Tests for {@link DnssecValidator}: RRSIG verification with real keys,
 * DS digests, NSEC/NSEC3 denial of existence and the helper functions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnssecValidatorTest {

    private static List<DnsResourceRecord> aRrset() throws Exception {
        List<DnsResourceRecord> rrset = new ArrayList<DnsResourceRecord>();
        rrset.add(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1")));
        rrset.add(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.2")));
        return rrset;
    }

    private void roundTrip(TestKey key) throws Exception {
        List<DnsResourceRecord> rrset = aRrset();
        DnsResourceRecord rrsig = signCurrent(rrset, key, "example.com.");
        assertTrue(DnssecValidator.verifyRRSIG(rrset, rrsig, key.dnskey));
        // tampered data must not verify
        List<DnsResourceRecord> tampered = new ArrayList<DnsResourceRecord>();
        tampered.add(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.9")));
        assertFalse(DnssecValidator.verifyRRSIG(tampered, rrsig, key.dnskey));
    }

    @Test
    public void testVerifyEcdsaP256() throws Exception {
        roundTrip(ecdsaP256("example.com.", 257));
    }

    @Test
    public void testVerifyEcdsaP384() throws Exception {
        roundTrip(ecdsaP384("example.com.", 257));
    }

    @Test
    public void testVerifyEd25519() throws Exception {
        roundTrip(ed25519("example.com.", 256));
    }

    @Test
    public void testVerifyEd25519BothXParities() throws Exception {
        // Half of all Ed25519 keys have the x-sign bit set in the last
        // wire byte (RFC 8032 section 5.1.2); both kinds must verify.
        boolean sawOdd = false;
        boolean sawEven = false;
        for (int i = 0; i < 200 && !(sawOdd && sawEven); i++) {
            TestKey key = ed25519("example.com.", 256);
            byte[] pub = key.dnskey.getDNSKEYPublicKey();
            boolean odd = (pub[31] & 0x80) != 0;
            if (odd && sawOdd) {
                continue;
            }
            if (!odd && sawEven) {
                continue;
            }
            if (odd) {
                sawOdd = true;
            } else {
                sawEven = true;
            }
            List<DnsResourceRecord> rrset = aRrset();
            DnsResourceRecord rrsig = signCurrent(rrset, key, "example.com.");
            assertTrue("x odd=" + odd,
                    DnssecValidator.verifyRRSIG(rrset, rrsig, key.dnskey));
        }
        assertTrue(sawOdd);
        assertTrue(sawEven);
    }

    @Test
    public void testVerifyRsaSha256() throws Exception {
        roundTrip(rsaSha256("example.com.", 256));
    }

    @Test
    public void testVerifyWithWrongKeyFails() throws Exception {
        TestKey a = ecdsaP256("example.com.", 257);
        TestKey b = ecdsaP256("example.com.", 257);
        List<DnsResourceRecord> rrset = aRrset();
        DnsResourceRecord rrsig = signCurrent(rrset, a, "example.com.");
        assertFalse(DnssecValidator.verifyRRSIG(rrset, rrsig, b.dnskey));
    }

    @Test
    public void testVerifyUnsupportedAlgorithm() throws Exception {
        List<DnsResourceRecord> rrset = aRrset();
        DnsResourceRecord rrsig = DnsResourceRecord.rrsig("www.example.com.", 300,
                DnsType.A, 99, 3, 300, now() + 100, now() - 100, 1,
                "example.com.", new byte[4]);
        TestKey key = ecdsaP256("example.com.", 257);
        assertFalse(DnssecValidator.verifyRRSIG(rrset, rrsig, key.dnskey));
    }

    @Test
    public void testVerifyWithMalformedKeyFails() throws Exception {
        TestKey key = ecdsaP256("example.com.", 257);
        List<DnsResourceRecord> rrset = aRrset();
        DnsResourceRecord rrsig = signCurrent(rrset, key, "example.com.");
        DnsResourceRecord shortKey = DnsResourceRecord.dnskey("example.com.", 300, 257,
                key.algorithm, new byte[5]);
        assertFalse(DnssecValidator.verifyRRSIG(rrset, rrsig, shortKey));
        DnsResourceRecord noKey = DnsResourceRecord.dnskey("example.com.", 300, 257,
                DnssecAlgorithm.ED25519.getNumber(), new byte[0]);
        DnsResourceRecord edSig = DnsResourceRecord.rrsig("www.example.com.", 300,
                DnsType.A, DnssecAlgorithm.ED25519.getNumber(), 3, 300,
                now() + 100, now() - 100, 1, "example.com.", new byte[64]);
        assertFalse(DnssecValidator.verifyRRSIG(rrset, edSig, noKey));
    }

    @Test
    public void testBuildPublicKeyUnsupportedAndCurves() throws Exception {
        TestKey ed = ed25519("example.com.", 256);
        assertNotNull(DnssecValidator.buildPublicKey(ed.dnskey, DnssecAlgorithm.ED25519));
        TestKey rsa = rsaSha256("example.com.", 256);
        assertNotNull(DnssecValidator.buildPublicKey(rsa.dnskey, DnssecAlgorithm.RSASHA256));
    }

    @Test
    public void testIsRRSIGCurrent() {
        long n = now();
        DnsResourceRecord current = DnsResourceRecord.rrsig("a.example.", 1, DnsType.A, 13, 2,
                1, n + 1000, n - 1000, 1, "example.", new byte[1]);
        DnsResourceRecord expired = DnsResourceRecord.rrsig("a.example.", 1, DnsType.A, 13, 2,
                1, n - 10, n - 1000, 1, "example.", new byte[1]);
        DnsResourceRecord future = DnsResourceRecord.rrsig("a.example.", 1, DnsType.A, 13, 2,
                1, n + 2000, n + 1000, 1, "example.", new byte[1]);
        assertTrue(DnssecValidator.isRRSIGCurrent(current));
        assertFalse(DnssecValidator.isRRSIGCurrent(expired));
        assertFalse(DnssecValidator.isRRSIGCurrent(future));
    }

    @Test
    public void testVerifyDs() throws Exception {
        TestKey key = ecdsaP256("example.com.", 257);
        DnsResourceRecord ds256 = ds(key, 2);
        assertTrue(DnssecValidator.verifyDS(key.dnskey, ds256));
        DnsResourceRecord ds384 = ds(key, 4);
        assertTrue(DnssecValidator.verifyDS(key.dnskey, ds384));
        DnsResourceRecord ds1 = ds(key, 1);
        assertTrue(DnssecValidator.verifyDS(key.dnskey, ds1));
        TestKey other = ecdsaP256("example.com.", 257);
        assertFalse(DnssecValidator.verifyDS(other.dnskey, ds256));
        DnsResourceRecord badDigest = rawDs("example.com.", key.keyTag, key.algorithm, 77,
                new byte[32]);
        assertFalse(DnssecValidator.verifyDS(key.dnskey, badDigest));
    }

    @Test
    public void testNsecTypeAbsent() {
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.",
                new int[] {DnsType.A.getValue(), DnsType.NSEC.getValue()});
        List<DnsResourceRecord> list = list(nsec);
        // name exists, type AAAA absent
        assertTrue(DnssecValidator.verifyNSEC("a.example.com.", DnsType.AAAA, list));
        assertEquals(DnsMessage.RCODE_NOERROR,
                DnssecValidator.nsecDenialRcode("a.example.com.", list));
        // type present: not denied (name not in gap either)
        assertFalse(DnssecValidator.verifyNSEC("a.example.com.", DnsType.A, list));
    }

    @Test
    public void testNsecNameInGap() {
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.",
                new int[] {DnsType.A.getValue()});
        List<DnsResourceRecord> list = list(nsec);
        assertTrue(DnssecValidator.verifyNSEC("b.example.com.", DnsType.A, list));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsecDenialRcode("b.example.com.", list));
        assertFalse(DnssecValidator.verifyNSEC("d.example.com.", DnsType.A, list));
    }

    @Test
    public void testNsecWrapAround() {
        DnsResourceRecord last = nsec("z.example.com.", "example.com.",
                new int[] {DnsType.A.getValue()});
        List<DnsResourceRecord> list = list(last);
        assertTrue(DnssecValidator.verifyNSEC("zz.example.com.", DnsType.A, list));
    }

    @Test
    public void testNsecSkipsNonNsecAndCnameBlocksDenial() throws Exception {
        DnsResourceRecord a = DnsResourceRecord.a("a.example.com.", 1,
                InetAddress.getByName("192.0.2.1"));
        DnsResourceRecord cnameNsec = nsec("a.example.com.", "c.example.com.",
                new int[] {DnsType.CNAME.getValue()});
        List<DnsResourceRecord> list = list(a, cnameNsec);
        assertFalse(DnssecValidator.verifyNSEC("a.example.com.", DnsType.AAAA, list));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsecDenialRcode("q.example.com.", list(a)));
    }

    @Test
    public void testNsec3Hash() {
        // RFC 5155 appendix A: H(example) with salt aabbccdd, 12 iterations
        byte[] salt = new byte[] {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd};
        byte[] hash = DnssecValidator.nsec3Hash("example", 1, 12, salt);
        assertEquals("0P9MHAVEQVM6T7VBL5LOP2U3T2RP3TOM",
                DnssecValidator.base32HexEncode(hash));
        hash = DnssecValidator.nsec3Hash("a.example", 1, 12, salt);
        assertEquals("35MTHGPGCU1QG68FAB165KLNSNK3DPVL",
                DnssecValidator.base32HexEncode(hash));
        assertNull(DnssecValidator.nsec3Hash("example", 2, 12, salt));
    }

    @Test
    public void testNsec3IterationsAboveLimitAreNotHashed() {
        // RFC 9276 section 3.2: above the limit the proof is not evaluated
        byte[] salt = new byte[] {1, 2};
        int iterations = DnssecValidator.MAX_NSEC3_ITERATIONS + 1;
        byte[] high = new byte[20];
        for (int i = 0; i < 20; i++) {
            high[i] = (byte) 0xFF;
        }
        String lowOwner = DnssecValidator.base32HexEncode(new byte[20]) + ".example.com.";
        DnsResourceRecord covering = nsec3(lowOwner, 1, iterations, salt, high,
                new int[] {DnsType.A.getValue()});
        List<DnsResourceRecord> list = list(covering);
        assertFalse(DnssecValidator.verifyNSEC3("nope.example.com.", DnsType.A, list));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsec3DenialRcode("nope.example.com.", list));
        assertNull(DnssecValidator.nsec3Hash("nope.example.com.", 1, iterations, salt));
        assertTrue(DnssecValidator.exceedsNsec3IterationLimit(list));
        assertFalse(DnssecValidator.exceedsNsec3IterationLimit(
                list(nsec3(lowOwner, 1, DnssecValidator.MAX_NSEC3_ITERATIONS, salt, high,
                        new int[] {1}))));
    }

    @Test
    public void testBase32Hex() {
        assertEquals("", DnssecValidator.base32HexEncode(new byte[0]));
        assertEquals("CO", DnssecValidator.base32HexEncode("f".getBytes()));
        assertEquals("CPNG", DnssecValidator.base32HexEncode("fo".getBytes()));
        assertEquals("CPNMU", DnssecValidator.base32HexEncode("foo".getBytes()));
        assertEquals("CPNMUOG", DnssecValidator.base32HexEncode("foob".getBytes()));
        assertEquals("CPNMUOJ1", DnssecValidator.base32HexEncode("fooba".getBytes()));
        assertEquals("CPNMUOJ1E8", DnssecValidator.base32HexEncode("foobar".getBytes()));
    }

    @Test
    public void testNsec3Denial() {
        byte[] salt = new byte[] {1, 2};
        byte[] qhash = DnssecValidator.nsec3Hash("nope.example.com.", 1, 2, salt);
        String qb32 = DnssecValidator.base32HexEncode(qhash);
        byte[] low = new byte[20];
        byte[] high = new byte[20];
        for (int i = 0; i < 20; i++) {
            high[i] = (byte) 0xFF;
        }
        String lowOwner = DnssecValidator.base32HexEncode(low) + ".example.com.";
        DnsResourceRecord covering = nsec3(lowOwner, 1, 2, salt, high,
                new int[] {DnsType.A.getValue()});
        List<DnsResourceRecord> list = list(covering);
        assertTrue(DnssecValidator.verifyNSEC3("nope.example.com.", DnsType.A, list));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsec3DenialRcode("nope.example.com.", list));

        // matching hash: NODATA when type is absent
        DnsResourceRecord matching = nsec3(qb32 + ".example.com.", 1, 2, salt, low,
                new int[] {DnsType.A.getValue()});
        List<DnsResourceRecord> m = list(matching);
        assertTrue(DnssecValidator.verifyNSEC3("nope.example.com.", DnsType.MX, m));
        assertEquals(DnsMessage.RCODE_NOERROR,
                DnssecValidator.nsec3DenialRcode("nope.example.com.", m));
        assertFalse(DnssecValidator.verifyNSEC3("nope.example.com.", DnsType.A, m));
    }

    @Test
    public void testNsec3DegenerateInputs() {
        List<DnsResourceRecord> empty = new ArrayList<DnsResourceRecord>();
        assertFalse(DnssecValidator.verifyNSEC3("a.example.", DnsType.A, empty));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsec3DenialRcode("a.example.", empty));
        DnsResourceRecord unsupported = nsec3("AAAA.example.", 7, 0, new byte[0],
                new byte[20], new int[] {1});
        List<DnsResourceRecord> list = list(unsupported);
        assertFalse(DnssecValidator.verifyNSEC3("a.example.", DnsType.A, list));
        assertEquals(DnsMessage.RCODE_NXDOMAIN,
                DnssecValidator.nsec3DenialRcode("a.example.", list));
    }

    @Test
    public void testCanonicalNames() {
        assertEquals("", DnssecValidator.canonicalizeName(null));
        assertEquals("www.example.com", DnssecValidator.canonicalizeName("WWW.Example.COM."));
        assertEquals("a", DnssecValidator.canonicalizeName("a"));
        assertTrue(DnssecValidator.compareCanonical("a.example.", "b.example.") < 0);
        assertTrue(DnssecValidator.compareCanonical("z.a.example.", "b.example.") < 0);
        assertTrue(DnssecValidator.compareCanonical("a.b.example.", "b.example.") > 0);
        assertEquals(0, DnssecValidator.compareCanonical("A.EXAMPLE.", "a.example"));
        assertTrue(DnssecValidator.compareCanonical("", "a.") < 0);
    }

    @Test
    public void testIsNameBetween() {
        assertTrue(DnssecValidator.isNameBetween("a.example", "b.example", "c.example"));
        assertFalse(DnssecValidator.isNameBetween("a.example", "d.example", "c.example"));
        assertFalse(DnssecValidator.isNameBetween("a.example", "a.example", "c.example"));
        // wrap-around
        assertTrue(DnssecValidator.isNameBetween("z.example", "zz.example", "a.example"));
        assertTrue(DnssecValidator.isNameBetween("z.example", "0.example", "a.example"));
        assertFalse(DnssecValidator.isNameBetween("z.example", "m.example", "a.example"));
    }

    @Test
    public void testEcdsaRawToDer() {
        byte[] raw = new byte[64];
        raw[0] = (byte) 0x80;
        raw[63] = 1;
        byte[] der = DnssecValidator.ecdsaRawToDER(raw, 32);
        assertEquals(0x30, der[0] & 0xFF);
        assertEquals(der.length - 2, der[1] & 0xFF);
        assertEquals(0x02, der[2]);
        // r has leading zero byte for the sign bit
        assertEquals(33, der[3]);
        assertEquals(0, der[4]);
    }

    @Test
    public void testFindHelpers() throws Exception {
        TestKey key = ecdsaP256("example.com.", 257);
        TestKey other = ed25519("example.com.", 256);
        List<DnsResourceRecord> rrset = aRrset();
        DnsResourceRecord rrsig = signCurrent(rrset, key, "example.com.");
        List<DnsResourceRecord> keys = list(other.dnskey, rrsig, key.dnskey);
        DnsResourceRecord found = DnssecValidator.findMatchingDNSKEY(rrsig, keys);
        assertSame(key.dnskey, found);
        assertNull(DnssecValidator.findMatchingDNSKEY(rrsig, list(other.dnskey)));
        List<DnsResourceRecord> mixed = list(rrset.get(0), rrsig);
        assertEquals(1, DnssecValidator.findRRSIGs(mixed, DnsType.A.getValue()).size());
        assertEquals(0, DnssecValidator.findRRSIGs(mixed, DnsType.MX.getValue()).size());
        assertEquals(1, DnssecValidator.filterByType(mixed, DnsType.RRSIG).size());
    }
}
