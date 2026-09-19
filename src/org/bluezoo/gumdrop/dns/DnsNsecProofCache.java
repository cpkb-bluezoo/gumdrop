/*
 * DnsNsecProofCache.java
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

import org.bluezoo.gumdrop.dns.client.DnssecValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache of DNSSEC-validated NSEC/NSEC3 denial-of-existence proofs keyed by
 * signer zone, for RFC 8198 aggressive use of validated cache. Ingestion and
 * lookup are event-driven ({@link DnsResourceRecordSink},
 * {@link DnsNsecSynthesisHandler}); nothing here parses wire format or returns
 * a synthesized {@link DnsMessage}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsNsecProofCache {

    private static final AtomicLong TEST_CLOCK_SKEW_MS = new AtomicLong();

    static void testingAdvanceClock(long millis) {
        TEST_CLOCK_SKEW_MS.addAndGet(millis);
    }

    static void testingResetClock() {
        TEST_CLOCK_SKEW_MS.set(0L);
    }

    private static long clockMillis() {
        return System.currentTimeMillis() + TEST_CLOCK_SKEW_MS.get();
    }

    private final Map<String, ZoneProofs> proofsByZone =
            new ConcurrentHashMap<String, ZoneProofs>();

    /**
     * Ingests records from a parsed authority section (event dispatch).
     *
     * @param signerZone the signer zone name
     * @param authoritySection authority RRs from a validated response
     */
    public void ingestAuthoritySection(String signerZone,
                                       List<DnsResourceRecord> authoritySection) {
        if (signerZone == null || signerZone.isEmpty()) {
            return;
        }
        final String zoneKey = normalizeZone(signerZone);
        final ZoneProofs batch = new ZoneProofs();
        DnsRecordSectionDispatcher.dispatch(authoritySection,
                new DnsResourceRecordSink() {
                    @Override
                    public void resourceRecord(DnsResourceRecord record) {
                        batch.add(record);
                    }

                    @Override
                    public void endSection() {
                        if (!batch.isEmpty()) {
                            mergeZoneProofs(zoneKey, batch);
                        }
                    }
                });
    }

    /**
     * Looks up a cached proof that covers {@code question} and delivers the
     * synthesis through {@code handler} (miss or start/records/complete).
     *
     * @param question the query
     * @param handler receives the result
     */
    public void lookup(DnsQuestion question, DnsNsecSynthesisHandler handler) {
        if (question == null || handler == null) {
            return;
        }
        String qname = question.getName();
        DnsType qtype = question.getType();
        if (qname == null || qtype == null) {
            handler.synthesisMiss();
            return;
        }

        for (String zone : zoneSuffixes(qname)) {
            ZoneProofs proofs = proofsByZone.get(normalizeZone(zone));
            if (proofs == null || proofs.isExpired()) {
                if (proofs != null && proofs.isExpired()) {
                    proofsByZone.remove(normalizeZone(zone), proofs);
                }
                continue;
            }
            int rcode = classifyProof(qname, qtype, proofs);
            if (rcode < 0) {
                continue;
            }
            handler.synthesisStart(question, rcode);
            proofs.emitProofRecords(handler, clockMillis());
            handler.synthesisComplete();
            return;
        }
        handler.synthesisMiss();
    }

    /**
     * Clears all cached proofs.
     */
    public void clear() {
        proofsByZone.clear();
    }

    private void mergeZoneProofs(String zoneKey, ZoneProofs batch) {
        ZoneProofs existing = proofsByZone.get(zoneKey);
        if (existing == null) {
            proofsByZone.put(zoneKey, batch);
            return;
        }
        existing.merge(batch);
        proofsByZone.put(zoneKey, existing);
    }

    private static String normalizeZone(String zone) {
        String z = zone.trim().toLowerCase();
        if (z.endsWith(".")) {
            return z;
        }
        return z + ".";
    }

    private static Iterable<String> zoneSuffixes(String name) {
        List<String> zones = new ArrayList<String>();
        String current = name;
        while (current != null && !current.isEmpty()) {
            zones.add(current);
            int dot = current.indexOf('.');
            if (dot < 0) {
                break;
            }
            current = current.substring(dot + 1);
        }
        zones.add(".");
        return zones;
    }

    /**
     * @return response rcode, or {@code -1} if proofs do not cover the name
     */
    private static int classifyProof(String qname, DnsType qtype,
                                     ZoneProofs proofs) {
        List<DnsResourceRecord> nsec = proofs.nsecRecords;
        List<DnsResourceRecord> nsec3 = proofs.nsec3Records;
        if (!nsec.isEmpty()) {
            if (DnssecValidator.verifyNSEC(qname, qtype, nsec)) {
                return DnssecValidator.nsecDenialRcode(qname, nsec);
            }
        }
        if (!nsec3.isEmpty()) {
            if (DnssecValidator.verifyNSEC3(qname, qtype, nsec3)) {
                return DnssecValidator.nsec3DenialRcode(qname, nsec3);
            }
        }
        return -1;
    }

    private static final class ZoneProofs {
        final List<DnsResourceRecord> nsecRecords =
                new ArrayList<DnsResourceRecord>();
        final List<DnsResourceRecord> nsec3Records =
                new ArrayList<DnsResourceRecord>();
        final List<DnsResourceRecord> rrsigRecords =
                new ArrayList<DnsResourceRecord>();
        long expiryTime;
        long creationTime;

        void add(DnsResourceRecord record) {
            if (record == null || record.getType() == null) {
                return;
            }
            DnsType type = record.getType();
            if (type == DnsType.NSEC) {
                upsertByName(nsecRecords, record);
            } else if (type == DnsType.NSEC3) {
                upsertByName(nsec3Records, record);
            } else if (type == DnsType.RRSIG) {
                upsertByName(rrsigRecords, record);
            }
            int ttl = record.getTTL();
            if (ttl <= 0) {
                return;
            }
            long candidateExpiry = clockMillis() + ttl * 1000L;
            if (creationTime == 0L) {
                creationTime = clockMillis();
                expiryTime = candidateExpiry;
            } else if (candidateExpiry < expiryTime) {
                expiryTime = candidateExpiry;
            }
        }

        void merge(ZoneProofs other) {
            for (int i = 0; i < other.nsecRecords.size(); i++) {
                upsertByName(nsecRecords, other.nsecRecords.get(i));
            }
            for (int i = 0; i < other.nsec3Records.size(); i++) {
                upsertByName(nsec3Records, other.nsec3Records.get(i));
            }
            for (int i = 0; i < other.rrsigRecords.size(); i++) {
                upsertByName(rrsigRecords, other.rrsigRecords.get(i));
            }
            if (other.expiryTime > 0L
                    && (expiryTime == 0L || other.expiryTime < expiryTime)) {
                expiryTime = other.expiryTime;
            }
            if (creationTime == 0L) {
                creationTime = other.creationTime;
            }
        }

        boolean isEmpty() {
            return nsecRecords.isEmpty() && nsec3Records.isEmpty();
        }

        boolean isExpired() {
            return expiryTime > 0L && clockMillis() >= expiryTime;
        }

        void emitProofRecords(DnsNsecSynthesisHandler handler, long now) {
            emitList(handler, nsecRecords, now, creationTime);
            emitList(handler, nsec3Records, now, creationTime);
            emitList(handler, rrsigRecords, now, creationTime);
        }

        private static void emitList(DnsNsecSynthesisHandler handler,
                                     List<DnsResourceRecord> list, long now,
                                     long createdAt) {
            long elapsed = (now - createdAt) / 1000L;
            for (int i = 0; i < list.size(); i++) {
                DnsResourceRecord record = list.get(i);
                int adjusted = (int) Math.max(1, record.getTTL() - elapsed);
                handler.proofRecord(new DnsResourceRecord(
                        record.getName(),
                        record.getType(),
                        record.getDNSClass(),
                        adjusted,
                        record.getRData()));
            }
        }

        private static void upsertByName(List<DnsResourceRecord> list,
                                         DnsResourceRecord record) {
            String name = record.getName();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getName().equalsIgnoreCase(name)) {
                    list.set(i, copyRecord(record));
                    return;
                }
            }
            list.add(copyRecord(record));
        }

        private static DnsResourceRecord copyRecord(DnsResourceRecord record) {
            byte[] rdata = record.getRData();
            byte[] rdataCopy = new byte[rdata.length];
            System.arraycopy(rdata, 0, rdataCopy, 0, rdata.length);
            return new DnsResourceRecord(
                    record.getName(),
                    record.getType(),
                    record.getDNSClass(),
                    record.getTTL(),
                    rdataCopy);
        }
    }
}
