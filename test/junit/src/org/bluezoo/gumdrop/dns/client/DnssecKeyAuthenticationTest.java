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
 * Tests that the chain validator authenticates the zone key that signs an
 * answer (the DNSKEY RRset must be signed by a key the DS record or trust
 * anchor vouches for), tries every RRSIG rather than only the first, and
 * treats a zone signed only with an unsupported algorithm as insecure only
 * once the parent's DS RRset has been authenticated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnssecKeyAuthenticationTest {

    private static final int FAKE_ALGORITHM = 5; // RSASHA1: not supported

    private CannedResolver resolver;
    private DnssecTrustAnchor anchors;
    private DnssecChainValidator validator;
    private DnssecStatus status;

    private final DnssecValidationCallback callback = new DnssecValidationCallback() {
        @Override
        public void onValidated(DnssecStatus s, DnsMessage response) {
            status = s;
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

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    private void run(List<DnsResourceRecord> answers) {
        validator.validate(message(answers, none()), callback);
    }

    /** Installs "com." as a trusted parent that signs the DS for example.com. */
    private TestKey parentSigning(DnsResourceRecord... ds) throws Exception {
        TestKey parent = ecdsaP256("com.", 257);
        anchors.addDNSKEYAnchor("com.", parent.dnskey);
        List<DnsResourceRecord> dsSet = list(ds);
        DnsResourceRecord dsSig = signCurrent(dsSet, parent, "com.");
        resolver.put("example.com.", DnsType.DS, message(concat(dsSet, dsSig), none()));
        resolver.put("com.", DnsType.DNSKEY, message(list(parent.dnskey), none()));
        return parent;
    }

    // ---- the zone key that signs an answer must be authenticated ----

    @Test
    public void testSplitKskZskChainIsSecure() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey zsk = ecdsaP256("example.com.", 256);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> keys = list(ksk.dnskey, zsk.dnskey);
        DnsResourceRecord keySig = signCurrent(keys, ksk, "example.com.");
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, zsk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey, zsk.dnskey, keySig));
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testForgedZoneKeyBesideGenuineKskIsBogus() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey forgedZsk = ecdsaP256("example.com.", 256);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, forgedZsk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey, forgedZsk.dnskey));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testDnskeyRrsetSignedByForgedKskIsBogus() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey forgedKsk = ecdsaP256("example.com.", 257);
        TestKey forgedZsk = ecdsaP256("example.com.", 256);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> keys = list(ksk.dnskey, forgedKsk.dnskey, forgedZsk.dnskey);
        DnsResourceRecord keySig = signCurrent(keys, forgedKsk, "example.com.");
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, forgedZsk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey, forgedKsk.dnskey, forgedZsk.dnskey, keySig));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testExpiredDnskeyRrsetSignatureIsBogus() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey zsk = ecdsaP256("example.com.", 256);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> keys = list(ksk.dnskey, zsk.dnskey);
        long n = now();
        DnsResourceRecord keySig = sign(keys, ksk, "example.com.", n - 7200, n - 3600);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, zsk, "example.com.");
        run(concat(rrset, sig, ksk.dnskey, zsk.dnskey, keySig));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testSplitKskZskChainWithKeysFetchedSeparately() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey zsk = ecdsaP256("example.com.", 256);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> keys = list(ksk.dnskey, zsk.dnskey);
        DnsResourceRecord keySig = signCurrent(keys, ksk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY,
                message(concat(keys, keySig), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, zsk, "example.com.");
        run(concat(rrset, sig));
        assertEquals(DnssecStatus.SECURE, status);
    }

    // ---- every RRSIG is considered, not only the first ----

    private DnsResourceRecord unusableSignature(List<DnsResourceRecord> rrset, int algorithm,
                                                int keyTag) {
        long n = now();
        return DnsResourceRecord.rrsig(rrset.get(0).getName(), 300, DnsType.A, algorithm, 3,
                300, n + 3600, n - 3600, keyTag, "example.com.", new byte[64]);
    }

    @Test
    public void testSecondRrsigIsUsedWhenFirstHasNoMatchingKey() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord first = unusableSignature(rrset, FAKE_ALGORITHM, 4242);
        DnsResourceRecord good = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, first, good, ksk.dnskey));
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testSecondRrsigIsUsedWhenFirstDoesNotVerify() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        // right key tag and algorithm, garbage signature
        DnsResourceRecord first = unusableSignature(rrset, ksk.algorithm, ksk.keyTag);
        DnsResourceRecord good = signCurrent(rrset, ksk, "example.com.");
        run(concat(rrset, first, good, ksk.dnskey));
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void testNoRrsigVerifyingIsBogus() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord bad = unusableSignature(rrset, ksk.algorithm, ksk.keyTag);
        run(concat(rrset, bad, ksk.dnskey));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    // ---- unsupported algorithms ----

    private TestKey unsupportedAlgorithmKey(int flags) {
        byte[] publicKey = new byte[64];
        for (int i = 0; i < publicKey.length; i++) {
            publicKey[i] = (byte) (i * 7 + 1);
        }
        DnsResourceRecord dnskey = DnsResourceRecord.dnskey("example.com.", 300, flags,
                FAKE_ALGORITHM, publicKey);
        return new TestKey(null, dnskey, FAKE_ALGORITHM);
    }

    @Test
    public void testZoneSignedOnlyWithUnsupportedAlgorithmIsInsecure() throws Exception {
        TestKey ksk = unsupportedAlgorithmKey(257);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = unusableSignature(rrset, FAKE_ALGORITHM, ksk.keyTag);
        run(concat(rrset, sig, ksk.dnskey));
        assertEquals(DnssecStatus.INSECURE, status);
    }

    /**
     * The downgrade guard: an attacker cannot turn a zone that is really
     * signed with a supported algorithm into an insecure one by supplying a
     * signature and key of an unsupported algorithm, because the parent's
     * authenticated DS record points at the genuine key.
     */
    @Test
    public void testInjectedUnsupportedSignatureDoesNotDowngradeSignedZone() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey fake = unsupportedAlgorithmKey(257);
        parentSigning(ds(ksk, 2));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = unusableSignature(rrset, FAKE_ALGORITHM, fake.keyTag);
        run(concat(rrset, sig, ksk.dnskey, fake.dnskey));
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void testUnsupportedAlgorithmWithForgedDsIsNotInsecure() throws Exception {
        TestKey ksk = unsupportedAlgorithmKey(257);
        TestKey parent = ecdsaP256("com.", 257);
        TestKey attacker = ecdsaP256("com.", 257);
        anchors.addDNSKEYAnchor("com.", parent.dnskey);
        DnsResourceRecord ds = ds(ksk, 2);
        DnsResourceRecord dsSig = signCurrent(list(ds), attacker, "com.");
        resolver.put("example.com.", DnsType.DS, message(list(ds, dsSig), none()));
        resolver.put("com.", DnsType.DNSKEY, message(list(parent.dnskey), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = unusableSignature(rrset, FAKE_ALGORITHM, ksk.keyTag);
        run(concat(rrset, sig, ksk.dnskey));
        assertNotEquals(DnssecStatus.INSECURE, status);
        assertNotEquals(DnssecStatus.SECURE, status);
    }
}
