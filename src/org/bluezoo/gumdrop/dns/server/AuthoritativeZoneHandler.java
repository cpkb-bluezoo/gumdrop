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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryTransport;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(
            AuthoritativeZoneHandler.class.getName());

    private static final int MAX_CNAME_CHAIN = 16;

    private final List<ManagedZone> managedZones;
    private volatile List<MutableZone> zones;
    private StorageExecutor storageExecutor;
    private SelectorLoop defaultLoop;
    private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;
    private TsigKey tsigKey;
    private boolean tsigRequired;
    private final List<InetSocketAddress> notifyPeers;
    private final boolean notifyFromNsRecords;
    private final Map<String, InetSocketAddress> mastersByOrigin;

    private static final int UDP_AXFR_SIZE_LIMIT = 512;

    public AuthoritativeZoneHandler(MutableZone zone) {
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        this.managedZones = Collections.singletonList(ManagedZone.inMemory(zone));
        this.zones = buildZoneSnapshot(this.managedZones);
        this.notifyPeers = Collections.emptyList();
        this.notifyFromNsRecords = true;
        this.mastersByOrigin = Collections.emptyMap();
    }

    AuthoritativeZoneHandler(List<ManagedZone> managedZones, TsigKey tsigKey,
                             boolean tsigRequired, List<InetSocketAddress> notifyPeers,
                             boolean notifyFromNsRecords,
                             Map<String, InetSocketAddress> mastersByOrigin) {
        if (managedZones == null || managedZones.isEmpty()) {
            throw new IllegalArgumentException("at least one zone is required");
        }
        this.managedZones = Collections.unmodifiableList(
                new ArrayList<ManagedZone>(managedZones));
        this.zones = buildZoneSnapshot(this.managedZones);
        this.tsigKey = tsigKey;
        this.tsigRequired = tsigRequired;
        this.notifyPeers = notifyPeers == null
                ? Collections.<InetSocketAddress>emptyList()
                : Collections.unmodifiableList(new ArrayList<InetSocketAddress>(notifyPeers));
        this.notifyFromNsRecords = notifyFromNsRecords;
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
        return builder().zoneFile(zoneFile).deferZoneFileLoad(false).build();
    }

    @Override
    public void start(Gumdrop gumdrop) {
        if (gumdrop == null) {
            return;
        }
        storageExecutor = gumdrop.getStorageExecutor();
        defaultLoop = gumdrop.nextWorkerLoop();
        for (int i = 0; i < managedZones.size(); i++) {
            ManagedZone managed = managedZones.get(i);
            if (managed.persistPath != null && managed.accessMode == ZoneFileAccessMode.READ_WRITE) {
                managed.saveQueue = new ZoneStorage.SaveQueue(storageExecutor,
                        managed.persistPath);
            }
            if (managed.persistPath != null && managed.zone == null) {
                loadZoneFileAsync(managed);
            }
        }
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
            handleNotify(query, loop, callback);
            return true;
        }
        if (opcode == DnsMessage.OPCODE_UPDATE) {
            handleUpdate(query, loop, callback);
            return true;
        }
        return false;
    }

    private void handleNotify(DnsMessage query, SelectorLoop loop,
                              DnsQueryCallback callback) {
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
            final MutableZone refreshZone = zone;
            final SelectorLoop dispatchLoop = loop != null ? loop : defaultLoop;
            ZoneNetworkTasks.refreshFromMasterAsync(dispatchLoop,
                    refreshZone, master, new Runnable() {
                        @Override
                        public void run() {
                            schedulePersist(refreshZone, dispatchLoop);
                        }
                    });
        }
        callback.onResponse(query.createAuthoritativeEmptyResponse(
                DnsMessage.RCODE_NOERROR));
    }

    private void handleUpdate(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        if (!DnsTsig.verify(query, tsigKey, !tsigRequired)) {
            callback.onResponse(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_REFUSED));
            return;
        }
        if (query.getUpdateZoneSection().isEmpty()) {
            respondToUpdate(query, callback, DnsMessage.RCODE_FORMERR);
            return;
        }
        String zname = ZoneFile.normalizeName(
                query.getUpdateZoneSection().get(0).getName());
        MutableZone zone = zoneForOrigin(zname);
        if (zone == null) {
            respondToUpdate(query, callback, DnsMessage.RCODE_NOTAUTH);
            return;
        }
        int rcode = DynamicUpdateProcessor.apply(zone, query);
        if (rcode == DnsMessage.RCODE_NOERROR) {
            sendNotify(zone, loop);
            schedulePersist(zone, loop);
        }
        respondToUpdate(query, callback, rcode);
    }

    private void respondToUpdate(DnsMessage query, DnsQueryCallback callback,
                                 int rcode) {
        DnsMessage response = query.createAuthoritativeEmptyResponse(rcode);
        callback.onResponse(tsigSignUpdateResponse(query, response));
    }

    private DnsMessage tsigSignUpdateResponse(DnsMessage query, DnsMessage response) {
        if (query.getTsigRecord() == null || tsigKey == null) {
            return response;
        }
        try {
            return DnsTsig.signResponse(response, tsigKey, query);
        } catch (IOException e) {
            return response;
        }
    }

    private void sendNotify(MutableZone zone, SelectorLoop loop) {
        List<InetSocketAddress> peers = notifyTargetsFor(zone);
        if (peers.isEmpty()) {
            return;
        }
        SelectorLoop dispatchLoop = loop != null ? loop : defaultLoop;
        ZoneNetworkTasks.notifyPeersAsync(dispatchLoop,
                zone.getOrigin(), peers);
    }

    private List<InetSocketAddress> notifyTargetsFor(MutableZone zone) {
        List<InetSocketAddress> targets = new ArrayList<InetSocketAddress>(notifyPeers);
        if (notifyFromNsRecords) {
            targets.addAll(zone.inZoneSecondaryAddresses());
        }
        if (targets.size() <= 1) {
            return targets;
        }
        List<InetSocketAddress> unique = new ArrayList<InetSocketAddress>();
        for (int i = 0; i < targets.size(); i++) {
            InetSocketAddress candidate = targets.get(i);
            if (candidate == null) {
                continue;
            }
            boolean seen = false;
            for (int j = 0; j < unique.size(); j++) {
                if (unique.get(j).equals(candidate)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private void loadZoneFileAsync(final ManagedZone managed) {
        if (storageExecutor == null || managed.persistPath == null) {
            return;
        }
        SelectorLoop loop = defaultLoop;
        ZoneStorage.loadAsync(storageExecutor, managed.persistPath,
                ZoneStorage.loopDispatcher(loop),
                new StorageExecutor.Callback<MutableZone>() {
                    @Override
                    public void completed(MutableZone result) {
                        managed.zone = result;
                        if (managed.accessMode == ZoneFileAccessMode.READ_WRITE
                                && managed.saveQueue == null) {
                            managed.saveQueue = new ZoneStorage.SaveQueue(
                                    storageExecutor, managed.persistPath);
                        }
                        zones = buildZoneSnapshot(managedZones);
                    }

                    @Override
                    public void failed(Throwable error) {
                        LOGGER.log(Level.WARNING, MessageFormat.format(
                                DnsServer.L10N.getString("warn.zone_load_failed"),
                                managed.persistPath), error);
                    }
                });
    }

    private void schedulePersist(MutableZone zone, SelectorLoop loop) {
        ManagedZone managed = managedFor(zone);
        if (managed == null || managed.accessMode != ZoneFileAccessMode.READ_WRITE
                || managed.saveQueue == null) {
            return;
        }
        managed.saveQueue.schedule(zone, loop != null ? loop : defaultLoop);
    }

    private ManagedZone managedFor(MutableZone zone) {
        for (int i = 0; i < managedZones.size(); i++) {
            ManagedZone managed = managedZones.get(i);
            if (managed.zone == zone) {
                return managed;
            }
        }
        return null;
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryTransport transport,
                            DnsQueryCallback callback) {
        DnsQuestion question = query.getQuestions().get(0);
        String qname = question.getName();
        DnsType qtype = question.getType();

        MutableZone zone = zoneFor(qname);
        if (zone == null) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_REFUSED));
            return;
        }

        if (qtype == DnsType.AXFR || qtype == DnsType.IXFR) {
            String normalized = ZoneFile.normalizeName(qname);
            if (!normalized.equals(zone.getOrigin())) {
                callback.onResponse(query.createAuthoritativeErrorResponse(
                        DnsMessage.RCODE_REFUSED));
                return;
            }
            if (!DnsTsig.verify(query, tsigKey, !tsigRequired)) {
                callback.onResponse(query.createAuthoritativeErrorResponse(
                        DnsMessage.RCODE_REFUSED));
                return;
            }
            if (qtype == DnsType.IXFR) {
                answerIxfr(query, zone, transport, callback);
            } else {
                answerAxfr(query, zone, transport, callback);
            }
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

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        handleQuery(query, loop, DnsQueryTransport.UDP, callback);
    }

    private void answerAxfr(DnsMessage query, MutableZone zone,
                            DnsQueryTransport transport,
                            DnsQueryCallback callback) {
        if (transport != null && transport.supportsMultiMessageAnswers()) {
            callback.onResponseSequence(tsigSignTransferSequence(query,
                    AxfrMessageSplitter.split(query, zone)));
            return;
        }
        DnsMessage single = createAuthoritativeResponse(query, zone,
                zone.allRecords(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                DnsMessage.RCODE_NOERROR);
        if (single.serialize().remaining() > UDP_AXFR_SIZE_LIMIT) {
            callback.onResponse(truncatedTransferHint(query));
            return;
        }
        callback.onResponse(tsigSignUpdateResponse(query, single));
    }

    private void answerIxfr(DnsMessage query, MutableZone zone,
                            DnsQueryTransport transport,
                            DnsQueryCallback callback) {
        List<DnsMessage> messages = IxfrMessageSplitter.split(query, zone);
        if (transport != null && transport.supportsMultiMessageAnswers()) {
            callback.onResponseSequence(tsigSignTransferSequence(query, messages));
            return;
        }
        if (messages.size() == 1) {
            DnsMessage single = messages.get(0);
            if (single.serialize().remaining() > UDP_AXFR_SIZE_LIMIT) {
                callback.onResponse(truncatedTransferHint(query));
                return;
            }
            callback.onResponse(tsigSignUpdateResponse(query, single));
            return;
        }
        callback.onResponse(truncatedTransferHint(query));
    }

    private List<DnsMessage> tsigSignTransferSequence(DnsMessage query,
                                                      List<DnsMessage> messages) {
        if (query.getTsigRecord() == null || tsigKey == null) {
            return messages;
        }
        try {
            return DnsTsig.signResponseSequence(messages, tsigKey, query);
        } catch (IOException e) {
            return messages;
        }
    }

    private static DnsMessage truncatedTransferHint(DnsMessage query) {
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_AA | DnsMessage.FLAG_TC
                | (query.getFlags() & DnsMessage.FLAG_RD);
        return new DnsMessage(query.getId(), flags, query.getQuestions(),
                new ArrayList<DnsResourceRecord>(),
                new ArrayList<DnsResourceRecord>(),
                new ArrayList<DnsResourceRecord>());
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

    static DnsMessage createAuthoritativeResponse(DnsMessage query,
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

        private final List<MutableZone> loadedZones = new ArrayList<MutableZone>();
        private final List<ZoneFileSpec> zoneFileSpecs = new ArrayList<ZoneFileSpec>();
        private boolean deferZoneFileLoad = true;
        private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;
        private TsigKey tsigKey;
        private boolean tsigRequired;
        private boolean notifyFromNsRecords = true;
        private final List<InetSocketAddress> notifyPeers = new ArrayList<InetSocketAddress>();
        private final Map<String, InetSocketAddress> mastersByOrigin =
                new HashMap<String, InetSocketAddress>();

        private Builder() {
        }

        /**
         * Loads a zone file from {@code path} on the storage pool when
         * {@link #deferZoneFileLoad(boolean) deferred loading} is enabled
         * (the default). Disk writes after dynamic update are disabled
         * ({@link ZoneFileAccessMode#READ_ONLY}).
         */
        public Builder zoneFile(Path path) {
            return zoneFile(path, ZoneFileAccessMode.READ_ONLY);
        }

        /**
         * Loads a zone file with the given access mode. {@link ZoneFileAccessMode#READ_WRITE}
         * persists successful RFC 2136 updates and AXFR refreshes via NIO on the
         * {@link StorageExecutor}.
         */
        public Builder zoneFile(Path path, ZoneFileAccessMode accessMode) {
            if (path == null) {
                throw new NullPointerException("path");
            }
            if (accessMode == null) {
                throw new NullPointerException("accessMode");
            }
            zoneFileSpecs.add(new ZoneFileSpec(path, accessMode));
            return this;
        }

        /**
         * When {@code true} (default), zone files are read on
         * {@link #start(Gumdrop)} via the storage pool instead of during
         * {@link #build()}.
         */
        public Builder deferZoneFileLoad(boolean defer) {
            this.deferZoneFileLoad = defer;
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
         * When {@code true} (default), NOTIFY after updates is also sent to
         * in-zone NS targets that have glue A/AAAA records (port 53).
         */
        public Builder notifyFromNsRecords(boolean enabled) {
            this.notifyFromNsRecords = enabled;
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
            List<ManagedZone> managed = new ArrayList<ManagedZone>();
            for (int i = 0; i < loadedZones.size(); i++) {
                managed.add(ManagedZone.inMemory(loadedZones.get(i)));
            }
            for (int i = 0; i < zoneFileSpecs.size(); i++) {
                ZoneFileSpec spec = zoneFileSpecs.get(i);
                if (deferZoneFileLoad) {
                    managed.add(ManagedZone.deferred(spec.path, spec.accessMode));
                } else {
                    try {
                        MutableZone zone = ZoneStorage.loadBlocking(spec.path);
                        managed.add(ManagedZone.onDisk(spec.path, spec.accessMode, zone));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "failed to load zone file: " + spec.path, e);
                    }
                }
            }
            if (managed.isEmpty()) {
                throw new IllegalStateException("at least one zone is required");
            }
            AuthoritativeZoneHandler handler = new AuthoritativeZoneHandler(managed,
                    tsigKey, tsigRequired, notifyPeers, notifyFromNsRecords,
                    mastersByOrigin);
            handler.setMinimalAnyPolicy(minimalAnyPolicy);
            return handler;
        }
    }

    private static final class ZoneFileSpec {
        final Path path;
        final ZoneFileAccessMode accessMode;

        ZoneFileSpec(Path path, ZoneFileAccessMode accessMode) {
            this.path = path;
            this.accessMode = accessMode;
        }
    }

    static final class ManagedZone {
        volatile MutableZone zone;
        final Path persistPath;
        final ZoneFileAccessMode accessMode;
        ZoneStorage.SaveQueue saveQueue;

        private ManagedZone(MutableZone zone, Path persistPath,
                            ZoneFileAccessMode accessMode) {
            this.zone = zone;
            this.persistPath = persistPath;
            this.accessMode = accessMode;
        }

        static ManagedZone inMemory(MutableZone zone) {
            return new ManagedZone(zone, null, ZoneFileAccessMode.READ_ONLY);
        }

        static ManagedZone deferred(Path path, ZoneFileAccessMode accessMode) {
            return new ManagedZone(null, path, accessMode);
        }

        static ManagedZone onDisk(Path path, ZoneFileAccessMode accessMode,
                                  MutableZone zone) {
            return new ManagedZone(zone, path, accessMode);
        }
    }

    private static List<MutableZone> buildZoneSnapshot(List<ManagedZone> managed) {
        List<MutableZone> snapshot = new ArrayList<MutableZone>();
        for (int i = 0; i < managed.size(); i++) {
            MutableZone zone = managed.get(i).zone;
            if (zone != null) {
                snapshot.add(zone);
            }
        }
        Collections.sort(snapshot, new Comparator<MutableZone>() {
            @Override
            public int compare(MutableZone a, MutableZone b) {
                return labelCount(b.getOrigin()) - labelCount(a.getOrigin());
            }
        });
        return Collections.unmodifiableList(snapshot);
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
