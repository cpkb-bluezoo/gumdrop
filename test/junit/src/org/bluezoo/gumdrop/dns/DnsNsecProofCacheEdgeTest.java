/*
 * DnsNsecProofCacheEdgeTest.java
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

package org.bluezoo.gumdrop.dns;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Edge branches of {@link DnsNsecProofCache}: argument guards, merging of
 * proofs, TTL ageing, expiry eviction and non-matching proof types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsNsecProofCacheEdgeTest {

    @After
    public void resetClock() {
        DnsNsecProofCache.testingResetClock();
    }

    private static final class Rec implements DnsNsecSynthesisHandler {
        boolean miss;
        int rcode = -1;
        final List<DnsResourceRecord> proofs = new ArrayList<DnsResourceRecord>();
        int complete;

        @Override
        public void synthesisMiss() {
            miss = true;
        }

        @Override
        public void synthesisStart(DnsQuestion question, int rcode) {
            this.rcode = rcode;
        }

        @Override
        public void proofRecord(DnsResourceRecord record) {
            proofs.add(record);
        }

        @Override
        public void synthesisComplete() {
            complete++;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static DnsResourceRecord nsec(String owner, String next, int ttl) {
        return new DnsResourceRecord(owner, DnsType.NSEC, DnsClass.IN, ttl,
                concat(DnsMessage.encodeName(next), new byte[] {0, 1, 0x60}));
    }

    private static DnsQuestion q(String name) {
        return new DnsQuestion(name, DnsType.A, DnsClass.IN);
    }

    private static List<DnsResourceRecord> list(DnsResourceRecord... rrs) {
        List<DnsResourceRecord> l = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < rrs.length; i++) {
            l.add(rrs[i]);
        }
        return l;
    }

    @Test
    public void nullArgumentsAreIgnored() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        Rec r = new Rec();
        c.lookup(null, r);
        c.lookup(q("a.example.com."), null);
        assertFalse(r.miss);
        c.ingestAuthoritySection(null, list(nsec("a.example.com.", "z.example.com.", 60)));
        c.ingestAuthoritySection("", list(nsec("a.example.com.", "z.example.com.", 60)));
        c.lookup(q("m.example.com."), r);
        assertTrue(r.miss);
    }

    @Test
    public void proofFoundViaParentZoneSuffixAndAged() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        c.ingestAuthoritySection("EXAMPLE.com", list(
                nsec("a.example.com.", "z.example.com.", 100)));
        DnsNsecProofCache.testingAdvanceClock(40_000L);
        Rec r = new Rec();
        c.lookup(q("m.sub.example.com."), r);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, r.rcode);
        assertEquals(1, r.complete);
        assertEquals(1, r.proofs.size());
        assertTrue(r.proofs.get(0).getTTL() <= 60);
    }

    @Test
    public void mergeKeepsShorterExpiryAndReplacesSameOwner() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        c.ingestAuthoritySection("example.com.", list(
                nsec("a.example.com.", "z.example.com.", 500)));
        c.ingestAuthoritySection("example.com.", list(
                nsec("a.example.com.", "y.example.com.", 30),
                nsec("m.example.com.", "z.example.com.", 500)));
        Rec r = new Rec();
        c.lookup(q("b.example.com."), r);
        assertEquals(2, r.proofs.size());
        DnsNsecProofCache.testingAdvanceClock(31_000L);
        Rec expired = new Rec();
        c.lookup(q("b.example.com."), expired);
        assertTrue(expired.miss);
        // the expired entry was dropped: a second lookup is still a miss
        Rec again = new Rec();
        c.lookup(q("b.example.com."), again);
        assertTrue(again.miss);
    }

    @Test
    public void rrsigAreEmittedAfterNsecAndZeroTtlDoesNotSetExpiry() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        DnsResourceRecord sig = new DnsResourceRecord("a.example.com.", DnsType.RRSIG,
                DnsClass.IN, 0, new byte[] {1, 2, 3});
        c.ingestAuthoritySection("example.com.", list(
                nsec("a.example.com.", "z.example.com.", 100), sig));
        Rec r = new Rec();
        c.lookup(q("m.example.com."), r);
        assertEquals(2, r.proofs.size());
        assertEquals(DnsType.NSEC, r.proofs.get(0).getType());
        assertEquals(DnsType.RRSIG, r.proofs.get(1).getType());
    }

    @Test
    public void onlyRrsigBatchIsNotStored() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        DnsResourceRecord sig = new DnsResourceRecord("a.example.com.", DnsType.RRSIG,
                DnsClass.IN, 60, new byte[] {1, 2, 3});
        c.ingestAuthoritySection("example.com.", list(sig));
        Rec r = new Rec();
        c.lookup(q("m.example.com."), r);
        assertTrue(r.miss);
    }

    @Test
    public void nonCoveringNsecIsMiss() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        c.ingestAuthoritySection("example.com.", list(
                nsec("a.example.com.", "c.example.com.", 60)));
        Rec r = new Rec();
        c.lookup(q("mmm.example.com."), r);
        assertTrue(r.miss);
    }

    @Test
    public void unrelatedZoneAndClear() {
        DnsNsecProofCache c = new DnsNsecProofCache();
        c.ingestAuthoritySection("example.org.", list(
                nsec("a.example.org.", "z.example.org.", 60)));
        Rec r = new Rec();
        c.lookup(q("m.example.com."), r);
        assertTrue(r.miss);
        Rec hit = new Rec();
        c.lookup(q("m.example.org."), hit);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, hit.rcode);
        c.clear();
        Rec after = new Rec();
        c.lookup(q("m.example.org."), after);
        assertTrue(after.miss);
    }
}
