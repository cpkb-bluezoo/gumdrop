/*
 * MutableZone.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory authoritative zone that supports RFC 2136 dynamic updates.
 */
public final class MutableZone {

    private final String origin;
    private final int defaultTtl;
    private final Map<String, List<DnsResourceRecord>> recordsByName;
    private DnsResourceRecord soaRecord;
    private ZoneFile.SoaData soaData;
    private int minimumTtl;

    private MutableZone(String origin, int defaultTtl,
                        Map<String, List<DnsResourceRecord>> recordsByName,
                        DnsResourceRecord soaRecord, ZoneFile.SoaData soaData) {
        this.origin = origin;
        this.defaultTtl = defaultTtl;
        this.recordsByName = recordsByName;
        this.soaRecord = soaRecord;
        this.soaData = soaData;
        this.minimumTtl = soaData.minimum;
    }

    static MutableZone from(ZoneFile zone) {
        Map<String, List<DnsResourceRecord>> copy = new LinkedHashMap<String, List<DnsResourceRecord>>();
        for (Map.Entry<String, List<DnsResourceRecord>> e : zone.copyRecordsByName().entrySet()) {
            copy.put(e.getKey(), new ArrayList<DnsResourceRecord>(e.getValue()));
        }
        ZoneFile.SoaData soa = zone.copySoaData();
        DnsResourceRecord soaRr = zone.getSoaRecord();
        return new MutableZone(zone.getOrigin(), zone.getDefaultTtl(), copy, soaRr, soa);
    }

    public String getOrigin() {
        return origin;
    }

    public int getDefaultTtl() {
        return defaultTtl;
    }

    java.util.Set<String> ownerNames() {
        return recordsByName.keySet();
    }

    ZoneFile.SoaData getSoaData() {
        return soaData;
    }

    public boolean isWithinZone(String qname) {
        String normalized = ZoneFile.normalizeName(qname);
        if (normalized.equals(origin)) {
            return true;
        }
        return normalized.endsWith("." + origin);
    }

    ZoneLookupResult lookup(String qname, DnsType type) {
        String normalized = ZoneFile.normalizeName(qname);
        ZoneLookupResult exact = lookupAtOwner(normalized, type, false);
        if (exact.getStatus() != ZoneLookupResult.STATUS_NXDOMAIN) {
            return exact;
        }
        String wildcardOwner = ZoneFile.wildcardOwnerName(normalized);
        if (wildcardOwner == null) {
            return ZoneLookupResult.nxdomain();
        }
        ZoneLookupResult wildcard = lookupAtOwner(wildcardOwner, type, true);
        if (wildcard.getStatus() == ZoneLookupResult.STATUS_NXDOMAIN) {
            return ZoneLookupResult.nxdomain();
        }
        return wildcard;
    }

    List<DnsResourceRecord> glueFor(List<DnsResourceRecord> nameRecords) {
        if (nameRecords == null || nameRecords.isEmpty()) {
            return Collections.emptyList();
        }
        List<DnsResourceRecord> glue = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < nameRecords.size(); i++) {
            DnsResourceRecord rr = nameRecords.get(i);
            String target = null;
            if (rr.getType() == DnsType.NS || rr.getType() == DnsType.MX) {
                target = rr.getType() == DnsType.NS
                        ? rr.getTargetName()
                        : rr.getMXExchange();
            }
            if (target == null || !isWithinZone(target)) {
                continue;
            }
            appendAddressRecords(glue, target);
        }
        return glue;
    }

    public List<DnsResourceRecord> getNsRecords() {
        List<DnsResourceRecord> atOrigin = recordsByName.get(origin);
        if (atOrigin == null) {
            return Collections.emptyList();
        }
        List<DnsResourceRecord> ns = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < atOrigin.size(); i++) {
            if (atOrigin.get(i).getType() == DnsType.NS) {
                ns.add(atOrigin.get(i));
            }
        }
        return ns;
    }

    public DnsResourceRecord getSoaRecord() {
        return soaRecord;
    }

    int getMinimumTtl() {
        return minimumTtl;
    }

    int getSerial() {
        return soaData.serial;
    }

    DnsResourceRecord authoritySoa() {
        return DnsResourceRecord.soa(soaRecord.getName(), minimumTtl,
                soaData.mname, soaData.rname, soaData.serial, soaData.refresh,
                soaData.retry, soaData.expire, minimumTtl);
    }

    List<DnsResourceRecord> allRecords() {
        List<DnsResourceRecord> all = new ArrayList<DnsResourceRecord>();
        for (List<DnsResourceRecord> list : recordsByName.values()) {
            all.addAll(list);
        }
        return all;
    }

    List<DnsResourceRecord> recordsAt(String owner) {
        List<DnsResourceRecord> at = recordsByName.get(ZoneFile.normalizeName(owner));
        if (at == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(at);
    }

    boolean nameExists(String owner) {
        List<DnsResourceRecord> at = recordsByName.get(ZoneFile.normalizeName(owner));
        return at != null && !at.isEmpty();
    }

    boolean rrsetExists(String owner, DnsType type) {
        List<DnsResourceRecord> at = recordsByName.get(ZoneFile.normalizeName(owner));
        if (at == null) {
            return false;
        }
        for (int i = 0; i < at.size(); i++) {
            if (at.get(i).getType() == type) {
                return true;
            }
        }
        return false;
    }

    void addRecord(DnsResourceRecord rr) {
        String owner = ZoneFile.normalizeName(rr.getName());
        if (!isWithinZone(owner)) {
            throw new IllegalArgumentException("owner outside zone");
        }
        List<DnsResourceRecord> list = recordsByName.get(owner);
        if (list == null) {
            list = new ArrayList<DnsResourceRecord>();
            recordsByName.put(owner, list);
        }
        list.add(rr);
    }

    void deleteRrset(String owner, DnsType type) {
        String normalized = ZoneFile.normalizeName(owner);
        if (normalized.equals(origin) && type == DnsType.SOA) {
            throw new IllegalArgumentException("cannot delete SOA");
        }
        List<DnsResourceRecord> at = recordsByName.get(normalized);
        if (at == null) {
            return;
        }
        for (int i = at.size() - 1; i >= 0; i--) {
            if (at.get(i).getType() == type) {
                at.remove(i);
            }
        }
        if (at.isEmpty()) {
            recordsByName.remove(normalized);
        }
    }

    void deleteName(String owner) {
        String normalized = ZoneFile.normalizeName(owner);
        if (normalized.equals(origin)) {
            throw new IllegalArgumentException("cannot delete origin");
        }
        recordsByName.remove(normalized);
    }

    void bumpSoaSerial() {
        soaData = new ZoneFile.SoaData(soaData.mname, soaData.rname,
                soaData.serial + 1, soaData.refresh, soaData.retry,
                soaData.expire, soaData.minimum);
        DnsResourceRecord updated = DnsResourceRecord.soa(origin, soaRecord.getTTL(),
                soaData.mname, soaData.rname, soaData.serial, soaData.refresh,
                soaData.retry, soaData.expire, soaData.minimum);
        replaceSoaRecord(updated);
    }

    void replaceFromAxfr(List<DnsResourceRecord> records) {
        recordsByName.clear();
        for (int i = 0; i < records.size(); i++) {
            addRecord(records.get(i));
        }
        List<DnsResourceRecord> atOrigin = recordsByName.get(origin);
        if (atOrigin != null) {
            for (int i = 0; i < atOrigin.size(); i++) {
                if (atOrigin.get(i).getType() == DnsType.SOA) {
                    soaRecord = atOrigin.get(i);
                    break;
                }
            }
        }
    }

    private void replaceSoaRecord(DnsResourceRecord updated) {
        soaRecord = updated;
        minimumTtl = soaData.minimum;
        List<DnsResourceRecord> atOrigin = recordsByName.get(origin);
        if (atOrigin == null) {
            atOrigin = new ArrayList<DnsResourceRecord>();
            recordsByName.put(origin, atOrigin);
        }
        for (int i = 0; i < atOrigin.size(); i++) {
            if (atOrigin.get(i).getType() == DnsType.SOA) {
                atOrigin.set(i, updated);
                return;
            }
        }
        atOrigin.add(updated);
    }

    private ZoneLookupResult lookupAtOwner(String owner, DnsType type, boolean wildcard) {
        List<DnsResourceRecord> atOwner = recordsByName.get(owner);
        if (atOwner == null || atOwner.isEmpty()) {
            return ZoneLookupResult.nxdomain();
        }
        List<DnsResourceRecord> matches = matchType(atOwner, type);
        if (!matches.isEmpty()) {
            return wildcard
                    ? ZoneLookupResult.answerWildcard(matches)
                    : ZoneLookupResult.answer(matches);
        }
        if (type == DnsType.CNAME) {
            return ZoneLookupResult.nodata();
        }
        DnsResourceRecord cname = firstOfType(atOwner, DnsType.CNAME);
        if (cname != null) {
            List<DnsResourceRecord> answers = Collections.singletonList(cname);
            return wildcard
                    ? ZoneLookupResult.answerWildcard(answers)
                    : ZoneLookupResult.answer(answers);
        }
        return ZoneLookupResult.nodata();
    }

    private static List<DnsResourceRecord> matchType(List<DnsResourceRecord> atOwner,
                                                     DnsType type) {
        List<DnsResourceRecord> matches = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < atOwner.size(); i++) {
            DnsResourceRecord rr = atOwner.get(i);
            if (rr.getType() == type || type == DnsType.ANY) {
                matches.add(rr);
            }
        }
        return matches;
    }

    private static DnsResourceRecord firstOfType(List<DnsResourceRecord> atOwner,
                                                 DnsType type) {
        for (int i = 0; i < atOwner.size(); i++) {
            if (atOwner.get(i).getType() == type) {
                return atOwner.get(i);
            }
        }
        return null;
    }

    private void appendAddressRecords(List<DnsResourceRecord> glue, String name) {
        List<DnsResourceRecord> at = recordsByName.get(ZoneFile.normalizeName(name));
        if (at == null) {
            return;
        }
        for (int i = 0; i < at.size(); i++) {
            DnsResourceRecord rr = at.get(i);
            if (rr.getType() == DnsType.A || rr.getType() == DnsType.AAAA) {
                glue.add(rr);
            }
        }
    }
}
