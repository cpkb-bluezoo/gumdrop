/*
 * DnsNsecProofCacheTest.java
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

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RFC 8198 validated NSEC proof cache (prerequisite for aggressive cache use).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsNsecProofCacheTest {

    @After
    public void resetClock() {
        DnsNsecProofCache.testingResetClock();
        DnsCache.testingResetClock();
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (int i = 0; i < parts.length; i++) {
            len += parts[i].length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, off, parts[i].length);
            off += parts[i].length;
        }
        return out;
    }

    private static DnsResourceRecord nsec(String owner, String next, int ttl)
            throws Exception {
        byte[] nextName = DnsMessage.encodeName(next);
        byte[] bitmap = new byte[] { 0, 1, 0x60 };
        return new DnsResourceRecord(owner, DnsType.NSEC, DnsClass.IN, ttl,
                concat(nextName, bitmap));
    }

    @Test
    public void lookupMissWhenEmpty() {
        DnsNsecProofCache cache = new DnsNsecProofCache();
        RecordingHandler handler = new RecordingHandler();
        cache.lookup(new DnsQuestion("missing.example.com", DnsType.A,
                DnsClass.IN), handler);
        assertTrue(handler.miss);
    }

    @Test
    public void nxdomainSynthesizedFromCachedRange() throws Exception {
        DnsNsecProofCache cache = new DnsNsecProofCache();
        List<DnsResourceRecord> authority = Arrays.asList(
                nsec("a.example.com.", "z.example.com.", 300));
        cache.ingestAuthoritySection("example.com.", authority);

        RecordingHandler handler = new RecordingHandler();
        cache.lookup(new DnsQuestion("missing.example.com", DnsType.A,
                DnsClass.IN), handler);

        assertEquals(DnsMessage.RCODE_NXDOMAIN, handler.rcode);
        assertTrue(handler.proofCount >= 1);
    }

    @Test
    public void nodataWhenNameExistsTypeAbsent() throws Exception {
        DnsNsecProofCache cache = new DnsNsecProofCache();
        byte[] nextName = DnsMessage.encodeName("z.example.com.");
        byte[] bitmap = new byte[] { 0, 0, 0x02 };
        DnsResourceRecord nsec = new DnsResourceRecord(
                "host.example.com.", DnsType.NSEC, DnsClass.IN, 300,
                concat(nextName, bitmap));
        cache.ingestAuthoritySection("example.com.",
                Collections.singletonList(nsec));

        RecordingHandler handler = new RecordingHandler();
        cache.lookup(new DnsQuestion("host.example.com", DnsType.A,
                DnsClass.IN), handler);

        assertEquals(DnsMessage.RCODE_NOERROR, handler.rcode);
    }

    @Test
    public void expiredProofDoesNotHit() throws Exception {
        DnsNsecProofCache cache = new DnsNsecProofCache();
        cache.ingestAuthoritySection("example.com.", Arrays.asList(
                nsec("a.example.com.", "z.example.com.", 60)));
        DnsNsecProofCache.testingAdvanceClock(61_000L);

        RecordingHandler handler = new RecordingHandler();
        cache.lookup(new DnsQuestion("missing.example.com", DnsType.A,
                DnsClass.IN), handler);
        assertTrue(handler.miss);
    }

    private static final class RecordingHandler implements DnsNsecSynthesisHandler {
        boolean miss;
        int rcode = -1;
        int proofCount;
        final AtomicInteger complete = new AtomicInteger();

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
            proofCount++;
        }

        @Override
        public void synthesisComplete() {
            complete.incrementAndGet();
        }
    }
}
