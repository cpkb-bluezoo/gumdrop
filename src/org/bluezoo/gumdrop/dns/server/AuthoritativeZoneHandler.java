/*
 * AuthoritativeZoneHandler.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;
import org.bluezoo.gumdrop.dns.client.DnsZoneOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative DNS {@link DnsQueryHandler} backed by one or more
 * {@link MutableZone} instances (typically loaded from BIND-style zone files).
 *
 * <p>Supports RFC 2308 negative answers (NXDOMAIN vs NODATA), RFC 1034
 * wildcard records, in-zone CNAME chaining, glue records in the
 * additional section for in-bailiwick NS/MX targets, RFC 1996 NOTIFY,
 * RFC 2136 dynamic update (with optional TSIG), and AXFR (UDP, small zones).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ZoneFile
 */
public final class AuthoritativeZoneHandler implements DnsQueryHandler {

    private static final int MAX_CNAME_CHAIN = 16;

    private final List<MutableZone> zones;
    private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;
    private TsigKey tsigKey;
    private boolean tsigRequired;
    private final List<InetSocketAddress> notifyPeers;
    private final Map<String, InetSocketAddress> mastersByOrigin;

    public AuthoritativeZoneHandler(MutableZone zone) {
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        this.zones = Collections.singletonList(zone);
        this.notifyPeers = Collections.emptyList();
        this.mastersByOrigin = Collections.emptyMap();
    }

    AuthoritativeZoneHandler(List<MutableZone> zones, TsigKey tsigKey,
                             boolean tsigRequired, List<InetSocketAddress> notifyPeers,
                             Map<String, InetSocketAddress> mastersByOrigin) {
        if (zones == null || zones.isEmpty()) {
            throw new IllegalArgumentException("at least one zone is required");
        }
        this.zones = Collections.unmodifiableList(new ArrayList<MutableZone>(zones));
        this.tsigKey = tsigKey;
        this.tsigRequired = tsigRequired;
        this.notifyPeers = notifyPeers == null
                ? Collections.<InetSocketAddress>emptyList()
                : Collections.unmodifiableList(new ArrayList<InetSocketAddress>(notifyPeers));
        this.mastersByOrigin = mastersByOrigin == null
                ? Collections.<String, InetSocketAddress>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, InetSocketAddress>(mastersByOrigin));
    }

    /**
     * Starts fluent configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RFC 8482 policy for QTYPE=ANY (default: minimal HINFO answer).
     */
    public void setMinimalAnyPolicy(MinimalAnyPolicy minimalAnyPolicy) {
        this.minimalAnyPolicy = minimalAnyPolicy != null
                ? minimalAnyPolicy : MinimalAnyPolicy.DISABLED;
    }

    /**
     * Loads a single zone file from disk.
     */
    public static AuthoritativeZoneHandler load(Path zoneFile) throws IOException {
        return builder().zoneFile(zoneFile).build();
    }

    /**
     * Returns the primary zone (first configured), or the only zone.
     */
    public MutableZone getZone() {
        return zones.get(0);
    }

    /**
     * Returns all configured zones (longest match wins when routing queries).
     */
    public List<MutableZone> getZones() {
        return zones;
    }

    @Override
    public boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                        DnsQueryCallback callback) {
        int opcode = query.getOpcode();
        if (opcode == DnsMessage.OPCODE_NOTIFY) {
            handleNotify(query, callback);
            return true;
        }
        if (opcode == DnsMessage.OPCODE_UPDATE) {
            handleUpdate(query, callback);
            return true;
        }
        return false;
    }

    private void handleNotify(DnsMessage query, DnsQueryCallback callback) {
        if (query.getQuestions().isEmpty()) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_FORMERR));
            return;
        }
        String zoneName = ZoneFile.normalizeName(query.getQuestions().get(0).getName());
        MutableZone zone = zoneForOrigin(zoneName);
        if (zone == null) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_NOTAUTH));
            return;
        }
        InetSocketAddress master = mastersByOrigin.get(zone.getOrigin());
        if (master != null) {
            refreshFromMaster(zone, master);
        }
        callback.onResponse(query.createAuthoritativeEmptyResponse(
                DnsMessage.RCODE_NOERROR));
    }

    private void handleUpdate(DnsMessage query, DnsQueryCallback callback) {
        if (!DnsTsig.verify(query, tsigKey, !tsigRequired)) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_REFUSED));
            return;
        }
        if (query.getUpdateZoneSection().isEmpty()) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_FORMERR));
            return;
        }
        String zname = ZoneFile.normalizeName(
                query.getUpdateZoneSection().get(0).getName());
        MutableZone zone = zoneForOrigin(zname);
        if (zone == null) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_NOTAUTH));
            return;
        }
        int rcode = DynamicUpdateProcessor.apply(zone, query);
        if (rcode == DnsMessage.RCODE_NOERROR) {
            ZoneNotifySender.notifySecondaries(zone, notifyPeers);
        }
        callback.onResponse(query.createAuthoritativeEmptyResponse(rcode));
    }

    private void refreshFromMaster(MutableZone zone, InetSocketAddress master) {
        try {
            List<DnsResourceRecord> records = DnsZoneOperations.axfr(master,
                    zone.getOrigin());
            zone.replaceFromAxfr(records);
        } catch (IOException e) {
            // refresh is best-effort on NOTIFY
        }
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        DnsQuestion question = query.getQuestions().get(0);
        String qname = question.getName();
        DnsType qtype = question.getType();

        MutableZone zone = zoneFor(qname);
        if (zone == null) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_REFUSED));
            return;
        }

        if (qtype == DnsType.AXFR) {
            String normalized = ZoneFile.normalizeName(qname);
            if (!normalized.equals(zone.getOrigin())) {
                callback.onResponse(query.createAuthoritativeErrorResponse(
                        DnsMessage.RCODE_REFUSED));
                return;
            }
            callback.onResponse(createAuthoritativeResponse(query, zone,
                    zone.allRecords(),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NOERROR));
            return;
        }

        if (qtype == DnsType.ANY
                && minimalAnyPolicy.shouldReturnMinimalAny(question)) {
            callback.onResponse(createAuthoritativeResponse(query, zone,
                    MinimalAnyResponse.records(qname),
                    zone.getNsRecords(),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NOERROR));
            return;
        }

        if (qtype == DnsType.SOA) {
            callback.onResponse(createAuthoritativeResponse(query, zone,
                    Collections.singletonList(zone.getSoaRecord()),
                    zone.getNsRecords(),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NOERROR));
            return;
        }

        ResolvedQuery resolved = resolveWithCnameChain(zone, qname, qtype, 0);
        if (resolved == null) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_SERVFAIL));
            return;
        }

        if (resolved.rcode == DnsMessage.RCODE_NXDOMAIN) {
            callback.onResponse(createAuthoritativeResponse(query, zone,
                    Collections.<DnsResourceRecord>emptyList(),
                    negativeAuthority(zone),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NXDOMAIN));
            return;
        }

        if (resolved.answers.isEmpty()) {
            callback.onResponse(createAuthoritativeResponse(query, zone,
                    Collections.<DnsResourceRecord>emptyList(),
                    negativeAuthority(zone),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NOERROR));
            return;
        }

        List<DnsResourceRecord> authorities = positiveAuthority(zone, qname, qtype);
        List<DnsResourceRecord> additionals = zone.glueFor(resolved.answers);
        if (!additionals.isEmpty()) {
            additionals = new ArrayList<DnsResourceRecord>(additionals);
            additionals.addAll(zone.glueFor(authorities));
        } else {
            additionals = zone.glueFor(authorities);
        }

        callback.onResponse(createAuthoritativeResponse(query, zone,
                resolved.answers, authorities, additionals,
                DnsMessage.RCODE_NOERROR));
    }

    private MutableZone zoneFor(String qname) {
        for (int i = 0; i < zones.size(); i++) {
            MutableZone zone = zones.get(i);
            if (zone.isWithinZone(qname)) {
                return zone;
            }
        }
        return null;
    }

    private MutableZone zoneForOrigin(String origin) {
        for (int i = 0; i < zones.size(); i++) {
            MutableZone zone = zones.get(i);
            if (zone.getOrigin().equals(origin)) {
                return zone;
            }
        }
        return null;
    }

    private static ResolvedQuery resolveWithCnameChain(MutableZone zone, String qname,
                                                       DnsType qtype, int depth) {
        if (depth > MAX_CNAME_CHAIN) {
            return null;
        }
        ZoneLookupResult result = zone.lookup(qname, qtype);
        if (result.getStatus() == ZoneLookupResult.STATUS_NXDOMAIN) {
            return ResolvedQuery.nxdomain();
        }
        if (result.getStatus() == ZoneLookupResult.STATUS_NODATA) {
            return ResolvedQuery.nodata();
        }
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(
                result.getAnswers());
        if (qtype != DnsType.CNAME && answers.size() == 1
                && answers.get(0).getType() == DnsType.CNAME) {
            String target = answers.get(0).getTargetName();
            if (!zone.isWithinZone(target)) {
                return ResolvedQuery.ok(answers);
            }
            ResolvedQuery tail = resolveWithCnameChain(zone, target, qtype, depth + 1);
            if (tail == null) {
                return null;
            }
            if (tail.rcode != DnsMessage.RCODE_NOERROR) {
                return tail;
            }
            answers.addAll(tail.answers);
        }
        return ResolvedQuery.ok(answers);
    }

    private static List<DnsResourceRecord> negativeAuthority(MutableZone zone) {
        return Collections.singletonList(zone.authoritySoa());
    }

    private static List<DnsResourceRecord> positiveAuthority(MutableZone zone,
            String qname, DnsType qtype) {
        if (qtype == DnsType.NS || !qname.equals(zone.getOrigin())) {
            return zone.getNsRecords();
        }
        return Collections.emptyList();
    }

    private static DnsMessage createAuthoritativeResponse(DnsMessage query,
            MutableZone zone, List<DnsResourceRecord> answers,
            List<DnsResourceRecord> authorities,
            List<DnsResourceRecord> additionals, int rcode) {
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_AA
                | (query.getFlags() & DnsMessage.FLAG_RD);
        if (rcode != DnsMessage.RCODE_NOERROR) {
            flags |= (rcode & 0x0F);
        }
        return new DnsMessage(query.getId(), flags, query.getQuestions(),
                new ArrayList<DnsResourceRecord>(answers),
                new ArrayList<DnsResourceRecord>(authorities),
                new ArrayList<DnsResourceRecord>(additionals));
    }

    private static final class ResolvedQuery {
        final int rcode;
        final List<DnsResourceRecord> answers;

        private ResolvedQuery(int rcode, List<DnsResourceRecord> answers) {
            this.rcode = rcode;
            this.answers = answers;
        }

        static ResolvedQuery ok(List<DnsResourceRecord> answers) {
            return new ResolvedQuery(DnsMessage.RCODE_NOERROR, answers);
        }

        static ResolvedQuery nodata() {
            return new ResolvedQuery(DnsMessage.RCODE_NOERROR,
                    Collections.<DnsResourceRecord>emptyList());
        }

        static ResolvedQuery nxdomain() {
            return new ResolvedQuery(DnsMessage.RCODE_NXDOMAIN,
                    Collections.<DnsResourceRecord>emptyList());
        }
    }

    /**
     * Builder for {@link AuthoritativeZoneHandler}.
     */
    public static final class Builder {

        private final List<Path> zonePaths = new ArrayList<Path>();
        private final List<MutableZone> loadedZones = new ArrayList<MutableZone>();
        private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;
        private TsigKey tsigKey;
        private boolean tsigRequired;
        private final List<InetSocketAddress> notifyPeers = new ArrayList<InetSocketAddress>();
        private final Map<String, InetSocketAddress> mastersByOrigin =
                new HashMap<String, InetSocketAddress>();

        private Builder() {
        }

        public Builder zoneFile(Path path) {
            if (path == null) {
                throw new NullPointerException("path");
            }
            zonePaths.add(path);
            return this;
        }

        public Builder zone(ZoneFile zone) {
            if (zone == null) {
                throw new NullPointerException("zone");
            }
            loadedZones.add(zone.asMutable());
            return this;
        }

        public Builder zone(MutableZone zone) {
            if (zone == null) {
                throw new NullPointerException("zone");
            }
            loadedZones.add(zone);
            return this;
        }

        public Builder tsigKey(TsigKey key) {
            this.tsigKey = key;
            return this;
        }

        public Builder requireTsig(boolean required) {
            this.tsigRequired = required;
            return this;
        }

        public Builder notifyPeer(InetSocketAddress address) {
            if (address != null) {
                notifyPeers.add(address);
            }
            return this;
        }

        /**
         * Configures a secondary zone that refreshes from {@code master} on NOTIFY.
         */
        public Builder slaveOf(String zoneOrigin, InetSocketAddress master) {
            mastersByOrigin.put(ZoneFile.normalizeName(zoneOrigin), master);
            return this;
        }

        public Builder minimalAnyPolicy(MinimalAnyPolicy policy) {
            this.minimalAnyPolicy = policy;
            return this;
        }

        public AuthoritativeZoneHandler build() {
            List<MutableZone> zoneFiles = new ArrayList<MutableZone>(loadedZones);
            for (int i = 0; i < zonePaths.size(); i++) {
                try {
                    zoneFiles.add(ZoneFile.load(zonePaths.get(i)).asMutable());
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "failed to load zone file: " + zonePaths.get(i), e);
                }
            }
            if (zoneFiles.isEmpty()) {
                throw new IllegalStateException("at least one zone is required");
            }
            List<MutableZone> ordered = new ArrayList<MutableZone>(zoneFiles);
            Collections.sort(ordered, new Comparator<MutableZone>() {
                @Override
                public int compare(MutableZone a, MutableZone b) {
                    return labelCount(b.getOrigin()) - labelCount(a.getOrigin());
                }
            });
            AuthoritativeZoneHandler handler = new AuthoritativeZoneHandler(ordered,
                    tsigKey, tsigRequired, notifyPeers, mastersByOrigin);
            handler.setMinimalAnyPolicy(minimalAnyPolicy);
            return handler;
        }

        private static int labelCount(String origin) {
            int dots = 0;
            for (int i = 0; i < origin.length(); i++) {
                if (origin.charAt(i) == '.') {
                    dots++;
                }
            }
            return dots;
        }
    }
}
