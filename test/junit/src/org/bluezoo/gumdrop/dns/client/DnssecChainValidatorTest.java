/*
 * DnssecChainValidatorTest.java
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
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.*;
import static org.junit.Assert.*;

/**
 * Tests for {@link DnssecChainValidator} using signed data and a canned resolver.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnssecChainValidatorTest {

    private CannedResolver resolver;
    private DnssecTrustAnchor anchors;
    private DnssecChainValidator validator;
    private DnssecStatus status;
    private DnsMessage seen;
    private int calls;

    private final DnssecValidationCallback callback = new DnssecValidationCallback() {
        @Override
        public void onValidated(DnssecStatus s, DnsMessage response) {
            status = s;
            seen = response;
            calls++;
        }
    };

    @Before
    public void setUp() {
        resolver = new CannedResolver();
        anchors = new DnssecTrustAnchor();
        anchors.clear();
        validator = new DnssecChainValidator(resolver, anchors);
    }

    private static List<DnsResourceRecord> a(String name) throws Exception {
        return list(DnsResourceRecord.a(name, 300, InetAddress.getByName("192.0.2.1")));
    }

    private static List<DnsResourceRecord> concat(List<DnsResourceRecord> x,
                                                  DnsResourceRecord... more) {
        List<DnsResourceRecord> out = new ArrayList<DnsResourceRecord>(x);
        for (int i = 0; i < more.length; i++) {
            out.add(more[i]);
        }
        return out;
    }

    private DnsMessage run(List<DnsResourceRecord> answers,
                           List<DnsResourceRecord> authorities) {
        DnsMessage m = message(answers, authorities);
        validator.validate(m, callback);
        return m;
    }

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    @Test
    public void testEmptyResponseIsInsecure() {
        DnsMessage m = run(none(), none());
        assertEquals(DnssecStatus.INSECURE, status);
        assertSame(m, seen);
    }

    @Test
    public void testUnknownTypeIsInsecure() {
        DnsResourceRecord unknown = new DnsResourceRecord("x.example.com.", null, 65280,
                null, 1, 30, new byte[] {1});
        run(list(unknown), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testNoRrsigIsInsecure() throws Exception {
        run(a("www.example.com."), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testExpiredSignatureIsBogus() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 256);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        long n = now();
        DnsResourceRecord sig = sign(rrset, zsk, "example.com.", n - 7200, n - 3600);
        run(concat(rrset, sig), none());
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testSecureWithKeysInAnswerAndDirectAnchor() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", ksk.dnskey);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.SECURE, status);
        assertEquals(1, calls);
        assertTrue(resolver.asked.isEmpty());
    }

    @Test
    public void testNoMatchingKeyIsBogus() throws Exception {
        TestKey signer = ecdsaP256("example.com.", 257);
        TestKey other = ed25519("example.com.", 257);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, signer, "example.com.");
        run(concat(rrset, sig, other.dnskey), none());
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testBadSignatureIsBogus() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        List<DnsResourceRecord> signed = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(signed, zsk, "example.com.");
        List<DnsResourceRecord> forged = list(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("203.0.113.5")));
        run(concat(forged, sig, zsk.dnskey), none());
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testFetchesDnskeyWhenAbsent() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", ksk.dnskey);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY,
                message(list(ksk.dnskey), none()));
        run(concat(rrset, sig), none());
        assertEquals(DnssecStatus.SECURE, status);
        assertEquals("example.com/DNSKEY", resolver.asked.get(0));
    }

    @Test
    public void testDnskeyFetchErrorIsIndeterminate() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        resolver.putError("example.com.", DnsType.DNSKEY, "timeout");
        run(concat(rrset, sig), none());
        assertEquals(DnssecStatus.INDETERMINATE, status);
    }

    @Test
    public void testEmptyDnskeyResponseIsInsecure() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY, message(none(), none()));
        run(concat(rrset, sig), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    /** Builds zone example.com signed by KSK, with DS in parent "com." signed by a trusted key. */
    private List<DnsResourceRecord> setUpTwoLevelChain(TestKey ksk, TestKey parentKey)
            throws Exception {
        DnsResourceRecord ds = ds(ksk, 2);
        DnsResourceRecord dsSig = signCurrent(list(ds), parentKey, "com.");
        resolver.put("example.com.", DnsType.DS, message(list(ds, dsSig), none()));
        resolver.put("com.", DnsType.DNSKEY, message(list(parentKey.dnskey), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        return concat(rrset, sig, ksk.dnskey);
    }

    @Test
    public void testSecureViaDsChain() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey parent = ecdsaP256("com.", 257);
        anchors.addDNSKEYAnchor("com.", parent.dnskey);
        List<DnsResourceRecord> answers = setUpTwoLevelChain(ksk, parent);
        run(answers, none());
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testDsMismatchIsBogus() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey parent = ecdsaP256("com.", 257);
        TestKey wrong = ecdsaP256("example.com.", 257);
        DnsResourceRecord ds = ds(wrong, 2);
        DnsResourceRecord dsSig = signCurrent(list(ds), parent, "com.");
        resolver.put("example.com.", DnsType.DS, message(list(ds, dsSig), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testNoDsIsInsecure() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        resolver.put("example.com.", DnsType.DS, message(none(), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testDsFetchErrorIsIndeterminate() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        resolver.putError("example.com.", DnsType.DS, "refused");
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.INDETERMINATE, status);
    }

    @Test
    public void testDsWithoutRrsigIsInsecureWhenKeyUntrusted() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        DnsResourceRecord ds = ds(ksk, 2);
        resolver.put("example.com.", DnsType.DS, message(list(ds), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testParentDnskeyFetchErrorIsIndeterminate() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey parent = ecdsaP256("com.", 257);
        List<DnsResourceRecord> answers = setUpTwoLevelChain(ksk, parent);
        resolver.putError("com.", DnsType.DNSKEY, "timeout");
        run(answers, none());
        assertEquals(DnssecStatus.INDETERMINATE, status);
    }

    @Test
    public void testParentWithNoKeysIsInsecure() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey parent = ecdsaP256("com.", 257);
        List<DnsResourceRecord> answers = setUpTwoLevelChain(ksk, parent);
        resolver.put("com.", DnsType.DNSKEY, message(none(), none()));
        run(answers, none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testChainDepthLimitIsIndeterminate() throws Exception {
        // The zone's DS is signed by the zone's own (untrusted) key, so the
        // walk never reaches an anchor and must stop at the depth limit.
        TestKey ksk = ecdsaP256("example.com.", 257);
        DnsResourceRecord ds = ds(ksk, 2);
        DnsResourceRecord dsSig = signCurrent(list(ds), ksk, "example.com.");
        resolver.put("example.com.", DnsType.DS, message(list(ds, dsSig), none()));
        resolver.put("example.com.", DnsType.DNSKEY, message(list(ksk.dnskey), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey), none());
        assertEquals(DnssecStatus.INDETERMINATE, status);
        assertEquals(1, calls);
    }

    @Test
    public void testNegativeResponseWithoutNsecIsInsecure() {
        run(none(), none());
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testNsecWithoutRrsigIsInsecure() {
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.", new int[] {1});
        run(none(), list(nsec));
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testNsec3WithoutRrsigIsInsecure() {
        DnsResourceRecord n3 = nsec3("AAAA.example.com.", 1, 0, new byte[0],
                new byte[20], new int[] {1});
        run(none(), list(n3));
        assertEquals(DnssecStatus.INSECURE, status);
    }

    @Test
    public void testNsecExpiredIsBogus() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.", new int[] {1});
        long n = now();
        DnsResourceRecord sig = sign(list(nsec), zsk, "example.com.", n - 7200, n - 3600);
        run(none(), list(nsec, sig));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testNsecSecure() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.", new int[] {1});
        DnsResourceRecord sig = signCurrent(list(nsec), zsk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY, message(list(zsk.dnskey), none()));
        run(none(), list(nsec, sig));
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testNsec3Secure() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        DnsResourceRecord n3 = nsec3("AAAA.example.com.", 1, 0, new byte[0],
                new byte[20], new int[] {1});
        DnsResourceRecord sig = signCurrent(list(n3), zsk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY, message(list(zsk.dnskey), none()));
        run(none(), list(n3, sig));
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testParentZone() {
        assertEquals(".", DnssecChainValidator.parentZone(null));
        assertEquals(".", DnssecChainValidator.parentZone(""));
        assertEquals(".", DnssecChainValidator.parentZone("."));
        assertEquals(".", DnssecChainValidator.parentZone("com"));
        assertEquals(".", DnssecChainValidator.parentZone("com."));
        assertEquals("example.com.", DnssecChainValidator.parentZone("www.example.com."));
        assertEquals("com", DnssecChainValidator.parentZone("example.com"));
    }
}
