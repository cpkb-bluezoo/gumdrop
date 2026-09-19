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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BIND-style zone file loader for authoritative DNS.
 *
 * <p>Supports {@code $ORIGIN}, {@code $TTL}, {@code $INCLUDE}, wildcards
 * ({@code *}), and record types SOA, NS, A, AAAA, CNAME, MX, TXT, PTR.
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

    private ZoneFile(String origin, int defaultTtl,
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
        Path absolute = path.toAbsolutePath().normalize();
        ParseState state = new ParseState();
        state.zoneRoot = absolute;
        parseFile(absolute, state, new ArrayDeque<Path>());
        if (state.origin == null) {
            throw new IOException("Zone file missing $ORIGIN: " + absolute);
        }
        List<DnsResourceRecord> originRecords = state.records.get(state.origin);
        DnsResourceRecord soa = null;
        if (originRecords != null) {
            for (int i = 0; i < originRecords.size(); i++) {
                if (originRecords.get(i).getType() == DnsType.SOA) {
                    soa = originRecords.get(i);
                    break;
                }
            }
        }
        if (soa == null || state.soaData == null) {
            throw new IOException("Zone file missing SOA at origin: " + absolute);
        }
        return new ZoneFile(state.origin, state.defaultTtl, state.records, soa,
                state.soaData);
    }

    private static void parseFile(Path file, ParseState state,
                                  Deque<Path> includeStack) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        for (Path open : includeStack) {
            if (open.equals(absolute)) {
                throw new IOException("$INCLUDE cycle: " + formatIncludeChain(
                        includeStack, absolute));
            }
        }
        boolean nested = !includeStack.isEmpty();
        includeStack.addLast(absolute);
        String savedOrigin = state.origin;
        int savedTtl = state.defaultTtl;
        Path parentDir = absolute.getParent();
        try (BufferedReader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripComment(line).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("$ORIGIN")) {
                    state.origin = normalizeName(tokenize(line)[1]);
                    continue;
                }
                if (line.startsWith("$TTL")) {
                    state.defaultTtl = Integer.parseInt(tokenize(line)[1]);
                    continue;
                }
                if (line.startsWith("$INCLUDE")) {
                    parseInclude(line, parentDir, state, includeStack);
                    continue;
                }
                if (line.startsWith("$")) {
                    continue;
                }
                parseRecordLine(line, state);
            }
        } finally {
            if (nested) {
                state.origin = savedOrigin;
                state.defaultTtl = savedTtl;
            }
            if (!includeStack.isEmpty() && includeStack.peekLast().equals(absolute)) {
                includeStack.pollLast();
            }
        }
    }

    private static void parseInclude(String line, Path parentDir, ParseState state,
                                     Deque<Path> includeStack) throws IOException {
        String[] tokens = tokenize(line);
        if (tokens.length < 2) {
            throw new IOException("Malformed $INCLUDE: " + line);
        }
        String filename = unquote(tokens[1]);
        Path includePath = resolveIncludePath(parentDir, filename);
        if (!Files.isRegularFile(includePath)) {
            throw new IOException("$INCLUDE file not found: " + includePath);
        }
        String savedOrigin = state.origin;
        if (tokens.length >= 3) {
            state.origin = normalizeName(tokens[2]);
        }
        parseFile(includePath, state, includeStack);
        state.origin = savedOrigin;
    }

    private static Path resolveIncludePath(Path parentDir, String filename) {
        Path candidate = Paths.get(filename);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        if (parentDir != null) {
            return parentDir.resolve(candidate).normalize();
        }
        return candidate.normalize();
    }

    private static void parseRecordLine(String line, ParseState state) throws IOException {
        String[] tokens = tokenize(line);
        int idx = 0;
        String nameToken = tokens[idx++];
        int ttl = state.defaultTtl;
        if (idx < tokens.length && Character.isDigit(tokens[idx].charAt(0))) {
            ttl = Integer.parseInt(tokens[idx++]);
        }
        if (idx < tokens.length && "IN".equalsIgnoreCase(tokens[idx])) {
            idx++;
        } else if (idx < tokens.length && tokens[idx].length() == 2
                && Character.isLetter(tokens[idx].charAt(0))) {
            idx++;
        }
        if (idx + 1 >= tokens.length) {
            throw new IOException("Malformed zone record: " + line);
        }
        String typeToken = tokens[idx++].toUpperCase(Locale.ROOT);
        DnsType type = DnsType.valueOf(typeToken);
        if (state.origin == null) {
            throw new IOException("Zone record before $ORIGIN: " + line);
        }
        String owner = ownerName(nameToken, state.origin);
        if (type == DnsType.SOA && owner.equals(state.origin) && state.soaData == null) {
            state.soaData = parseSoaTokens(tokens, idx, line);
        }
        DnsResourceRecord rr = parseRecord(owner, type, ttl, tokens, idx, line);
        addRecord(state.records, owner, rr);
    }

    private static String formatIncludeChain(Deque<Path> stack, Path cycle) {
        StringBuilder sb = new StringBuilder();
        for (Path p : stack) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(p);
        }
        sb.append(" -> ").append(cycle);
        return sb.toString();
    }

    private static final class ParseState {
        String origin;
        int defaultTtl = 3600;
        final Map<String, List<DnsResourceRecord>> records =
                new LinkedHashMap<String, List<DnsResourceRecord>>();
        SoaData soaData;
        Path zoneRoot;
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

    private static SoaData parseSoaTokens(String[] tokens, int idx, String line)
            throws IOException {
        if (idx + 6 >= tokens.length) {
            throw new IOException("Malformed SOA: " + line);
        }
        String mname = normalizeName(tokens[idx++]);
        String rname = normalizeName(tokens[idx++]);
        long serial = Long.parseLong(tokens[idx++]);
        int refresh = Integer.parseInt(tokens[idx++]);
        int retry = Integer.parseInt(tokens[idx++]);
        int expire = Integer.parseInt(tokens[idx++]);
        int minimum = Integer.parseInt(tokens[idx++]);
        return new SoaData(mname, rname, (int) serial, refresh, retry, expire, minimum);
    }

    private static final class SoaData {
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

    private static DnsResourceRecord parseRecord(String owner, DnsType type,
            int ttl, String[] tokens, int idx, String line)
            throws IOException {
        switch (type) {
            case A:
                return DnsResourceRecord.a(owner, ttl,
                        InetAddress.getByName(tokens[idx]));
            case AAAA:
                return DnsResourceRecord.aaaa(owner, ttl,
                        InetAddress.getByName(tokens[idx]));
            case NS:
                return DnsResourceRecord.ns(owner, ttl, normalizeName(tokens[idx]));
            case CNAME:
                return DnsResourceRecord.cname(owner, ttl, normalizeName(tokens[idx]));
            case PTR:
                return DnsResourceRecord.ptr(owner, ttl, normalizeName(tokens[idx]));
            case MX: {
                int preference = Integer.parseInt(tokens[idx++]);
                return DnsResourceRecord.mx(owner, ttl, preference,
                        normalizeName(tokens[idx]));
            }
            case TXT: {
                StringBuilder sb = new StringBuilder();
                for (int i = idx; i < tokens.length; i++) {
                    if (i > idx) {
                        sb.append(' ');
                    }
                    sb.append(unquote(tokens[i]));
                }
                return DnsResourceRecord.txt(owner, ttl, sb.toString());
            }
            case SOA: {
                if (idx + 6 >= tokens.length) {
                    throw new IOException("Malformed SOA: " + line);
                }
                String mname = normalizeName(tokens[idx++]);
                String rname = normalizeName(tokens[idx++]);
                long serial = Long.parseLong(tokens[idx++]);
                int refresh = Integer.parseInt(tokens[idx++]);
                int retry = Integer.parseInt(tokens[idx++]);
                int expire = Integer.parseInt(tokens[idx++]);
                int minimum = Integer.parseInt(tokens[idx++]);
                return DnsResourceRecord.soa(owner, ttl, mname, rname,
                        (int) serial, refresh, retry, expire, minimum);
            }
            default:
                throw new IOException("Unsupported zone record type: " + type);
        }
    }

    private static void addRecord(Map<String, List<DnsResourceRecord>> records,
                                  String owner, DnsResourceRecord rr) {
        List<DnsResourceRecord> list = records.get(owner);
        if (list == null) {
            list = new ArrayList<DnsResourceRecord>();
            records.put(owner, list);
        }
        list.add(rr);
    }

    private static String ownerName(String nameToken, String origin) {
        if ("*".equals(nameToken)) {
            return "*." + origin;
        }
        return expandName(nameToken, origin);
    }

    private static String stripComment(String line) {
        int idx = line.indexOf(';');
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static String[] tokenize(String line) {
        return line.trim().split("\\s+");
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1);
        }
        return token;
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
