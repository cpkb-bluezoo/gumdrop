/*
 * DynamicUpdateProcessor.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.util.List;

/**
 * RFC 2136 dynamic update processing against a {@link MutableZone}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
        ZoneChangeBatch batch = new ZoneChangeBatch();
        for (int i = 0; i < updates.size(); i++) {
            int rc = applyUpdate(zone, updates.get(i), batch);
            if (rc != DnsMessage.RCODE_NOERROR) {
                return rc;
            }
        }
        if (!batch.isEmpty()) {
            zone.commitDynamicUpdate(batch);
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

    private static int applyUpdate(MutableZone zone, DnsResourceRecord rr,
                                   ZoneChangeBatch batch) {
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
                zone.deleteName(owner, batch);
            } else {
                zone.deleteRrset(owner, type, batch);
            }
            return DnsMessage.RCODE_NOERROR;
        }
        if (type == DnsType.SOA) {
            return DnsMessage.RCODE_REFUSED;
        }
        zone.addRecord(rr, batch);
        return DnsMessage.RCODE_NOERROR;
    }
}
