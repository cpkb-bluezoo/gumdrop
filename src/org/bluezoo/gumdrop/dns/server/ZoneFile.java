/*
 * ZoneFile.java
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

import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BIND-style zone file loader for authoritative DNS.
 *
 * <p>Supports {@code $ORIGIN}, {@code $TTL}, {@code $INCLUDE},
 * {@code $GENERATE}, BIND parenthesis continuations, wildcards ({@code *}),
 * and record types SOA, NS, A, AAAA, CNAME, MX, TXT, PTR.
 * Loading uses {@link ZoneFileParser} incremental parsing.
 * Include paths are resolved relative to the file that contains the
 * {@code $INCLUDE} directive unless absolute.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ZoneFile {

    private final String origin;
    private final int defaultTtl;
    private final Map<String, List<DnsResourceRecord>> recordsByName;
    private final DnsResourceRecord soaRecord;
    private final int minimumTtl;
    private final SoaData soaData;

    ZoneFile(String origin, int defaultTtl,
                     Map<String, List<DnsResourceRecord>> recordsByName,
                     DnsResourceRecord soaRecord, SoaData soaData) {
        this.origin = origin;
        this.defaultTtl = defaultTtl;
        this.recordsByName = recordsByName;
        this.soaRecord = soaRecord;
        this.soaData = soaData;
        this.minimumTtl = soaData.minimum;
    }

    /**
     * Loads a zone file from disk.
     */
    public static ZoneFile load(Path path) throws IOException {
        return ZoneFileLoader.load(path);
    }

    public String getOrigin() {
        return origin;
    }

    /**
     * Returns true if {@code qname} is this zone or a subdomain of it.
     */
    public boolean isWithinZone(String qname) {
        String normalized = normalizeName(qname);
        if (normalized.equals(origin)) {
            return true;
        }
        return normalized.endsWith("." + origin);
    }

    /**
     * Authoritative lookup for a name and type (RFC 1034 wildcards, CNAME at owner).
     */
    ZoneLookupResult lookup(String qname, DnsType type) {
        String normalized = normalizeName(qname);
        ZoneLookupResult exact = lookupAtOwner(normalized, type, false);
        if (exact.getStatus() != ZoneLookupResult.STATUS_NXDOMAIN) {
            return exact;
        }
        String wildcardOwner = wildcardOwnerName(normalized);
        if (wildcardOwner == null) {
            return ZoneLookupResult.nxdomain();
        }
        ZoneLookupResult wildcard = lookupAtOwner(wildcardOwner, type, true);
        if (wildcard.getStatus() == ZoneLookupResult.STATUS_NXDOMAIN) {
            return ZoneLookupResult.nxdomain();
        }
        return wildcard;
    }

    /**
     * Looks up A/AAAA glue for in-zone names referred to by NS or MX targets.
     */
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

    DnsResourceRecord getSoaRecord() {
        return soaRecord;
    }

    int getMinimumTtl() {
        return minimumTtl;
    }

    /**
     * Returns SOA for authority sections (negative answers use minimum TTL).
     */
    DnsResourceRecord authoritySoa() {
        return DnsResourceRecord.soa(soaRecord.getName(), minimumTtl,
                soaData.mname, soaData.rname, soaData.serial, soaData.refresh,
                soaData.retry, soaData.expire, minimumTtl);
    }

    static final class SoaData {
        final String mname;
        final String rname;
        final int serial;
        final int refresh;
        final int retry;
        final int expire;
        final int minimum;

        SoaData(String mname, String rname, int serial, int refresh,
                int retry, int expire, int minimum) {
            this.mname = mname;
            this.rname = rname;
            this.serial = serial;
            this.refresh = refresh;
            this.retry = retry;
            this.expire = expire;
            this.minimum = minimum;
        }
    }

    private ZoneLookupResult lookupAtOwner(String owner, DnsType type,
                                           boolean wildcard) {
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
        List<DnsResourceRecord> at = recordsByName.get(normalizeName(name));
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

    /**
     * RFC 1034: {@code *.label.rest} for {@code host.label.rest}.
     */
    static String wildcardOwnerName(String normalizedQname) {
        if (normalizedQname == null || normalizedQname.isEmpty()) {
            return null;
        }
        int dot = normalizedQname.indexOf('.');
        if (dot < 0 || dot + 1 >= normalizedQname.length()) {
            return null;
        }
        return "*." + normalizedQname.substring(dot + 1);
    }

    static String normalizeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if ("@".equals(n)) {
            throw new IllegalArgumentException("@ requires origin context");
        }
        if (!n.endsWith(".")) {
            n = n + ".";
        }
        return n;
    }

    static String expandName(String token, String origin) {
        if ("@".equals(token)) {
            return origin;
        }
        if (token.endsWith(".")) {
            return normalizeName(token);
        }
        return normalizeName(token + "." + origin);
    }

}
