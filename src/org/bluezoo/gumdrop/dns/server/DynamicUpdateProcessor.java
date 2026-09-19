/*
 * DynamicUpdateProcessor.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.util.List;

/**
 * RFC 2136 dynamic update processing against a {@link MutableZone}.
 */
final class DynamicUpdateProcessor {

    private DynamicUpdateProcessor() {
    }

    static int apply(MutableZone zone, DnsMessage request) {
        List<DnsResourceRecord> zoneSection = request.getUpdateZoneSection();
        if (zoneSection.size() != 1) {
            return DnsMessage.RCODE_FORMERR;
        }
        DnsResourceRecord zoneRr = zoneSection.get(0);
        String zname = ZoneFile.normalizeName(zoneRr.getName());
        if (!zname.equals(zone.getOrigin())) {
            return DnsMessage.RCODE_NOTZONE;
        }
        if (!zone.isWithinZone(zname)) {
            return DnsMessage.RCODE_NOTAUTH;
        }

        List<DnsResourceRecord> prereqs = request.getUpdatePrerequisites();
        for (int i = 0; i < prereqs.size(); i++) {
            int rc = checkPrerequisite(zone, prereqs.get(i));
            if (rc != DnsMessage.RCODE_NOERROR) {
                return rc;
            }
        }

        List<DnsResourceRecord> updates = request.getUpdateChanges();
        boolean changed = false;
        for (int i = 0; i < updates.size(); i++) {
            int rc = applyUpdate(zone, updates.get(i));
            if (rc != DnsMessage.RCODE_NOERROR) {
                return rc;
            }
            changed = true;
        }
        if (changed) {
            zone.bumpSoaSerial();
        }
        return DnsMessage.RCODE_NOERROR;
    }

    private static int checkPrerequisite(MutableZone zone, DnsResourceRecord rr) {
        String owner = ZoneFile.normalizeName(rr.getName());
        if (!zone.isWithinZone(owner)) {
            return DnsMessage.RCODE_NOTZONE;
        }
        int cls = rr.getRawClass();
        DnsType type = rr.getType();
        if (type == null && rr.getRawType() == DnsType.ANY.getValue()) {
            type = DnsType.ANY;
        }
        if (cls == DnsClass.ANY.getValue() && type == DnsType.ANY && rr.getTTL() == 0
                && rr.getRData().length == 0) {
            if (zone.nameExists(owner)) {
                return DnsMessage.RCODE_YXDOMAIN;
            }
            return DnsMessage.RCODE_NOERROR;
        }
        if (type == DnsType.ANY && rr.getTTL() == 0 && rr.getRData().length == 0) {
            if (zone.nameExists(owner)) {
                return DnsMessage.RCODE_NOERROR;
            }
            return DnsMessage.RCODE_NXDOMAIN;
        }
        if (rr.getTTL() == 0 && rr.getRData().length == 0) {
            if (zone.rrsetExists(owner, type)) {
                return DnsMessage.RCODE_YXRRSET;
            }
            return DnsMessage.RCODE_NOERROR;
        }
        if (!zone.rrsetExists(owner, type)) {
            return DnsMessage.RCODE_NXRRSET;
        }
        return DnsMessage.RCODE_NOERROR;
    }

    private static int applyUpdate(MutableZone zone, DnsResourceRecord rr) {
        String owner = ZoneFile.normalizeName(rr.getName());
        if (!zone.isWithinZone(owner)) {
            return DnsMessage.RCODE_NOTZONE;
        }
        DnsType type = rr.getType();
        if (type == null) {
            type = DnsType.fromValue(rr.getRawType());
        }
        if (rr.getTTL() == 0 && rr.getRawClass() == DnsClass.ANY.getValue()) {
            if (type == DnsType.ANY) {
                zone.deleteName(owner);
            } else {
                zone.deleteRrset(owner, type);
            }
            return DnsMessage.RCODE_NOERROR;
        }
        if (type == DnsType.SOA) {
            return DnsMessage.RCODE_REFUSED;
        }
        zone.addRecord(rr);
        return DnsMessage.RCODE_NOERROR;
    }
}
