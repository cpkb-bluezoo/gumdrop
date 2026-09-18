/*
 * ZoneFile.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal BIND-style zone file loader for authoritative DNS.
 *
 * <p>Supports {@code $ORIGIN}, {@code $TTL}, and common record types:
 * SOA, NS, A, AAAA, CNAME, MX, TXT.
 */
public final class ZoneFile {

    private final String origin;
    private final int defaultTtl;
    private final Map<String, List<DnsResourceRecord>> recordsByName;

    private ZoneFile(String origin, int defaultTtl,
                     Map<String, List<DnsResourceRecord>> recordsByName) {
        this.origin = origin;
        this.defaultTtl = defaultTtl;
        this.recordsByName = recordsByName;
    }

    /**
     * Loads a zone file from disk.
     */
    public static ZoneFile load(Path path) throws IOException {
        String origin = null;
        int defaultTtl = 3600;
        Map<String, List<DnsResourceRecord>> records = new LinkedHashMap<String, List<DnsResourceRecord>>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripComment(line).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("$ORIGIN")) {
                    origin = normalizeName(tokenize(line)[1]);
                    continue;
                }
                if (line.startsWith("$TTL")) {
                    defaultTtl = Integer.parseInt(tokenize(line)[1]);
                    continue;
                }
                if (line.startsWith("$")) {
                    continue;
                }

                String[] tokens = tokenize(line);
                int idx = 0;
                String nameToken = tokens[idx++];
                int ttl = defaultTtl;
                DnsClass dnsClass = DnsClass.IN;
                if (idx < tokens.length && Character.isDigit(tokens[idx].charAt(0))) {
                    ttl = Integer.parseInt(tokens[idx++]);
                }
                if (idx < tokens.length && "IN".equalsIgnoreCase(tokens[idx])) {
                    dnsClass = DnsClass.IN;
                    idx++;
                } else if (idx < tokens.length && tokens[idx].length() == 2
                        && Character.isLetter(tokens[idx].charAt(0))) {
                    dnsClass = DnsClass.IN;
                    idx++;
                }
                if (idx + 1 >= tokens.length) {
                    throw new IOException("Malformed zone record: " + line);
                }
                String typeToken = tokens[idx++].toUpperCase(Locale.ROOT);
                DnsType type = DnsType.valueOf(typeToken);
                String owner = expandName(nameToken, origin);
                DnsResourceRecord rr = parseRecord(owner, type, dnsClass, ttl, tokens, idx, line);
                addRecord(records, owner, rr);
            }
        }

        if (origin == null) {
            throw new IOException("Zone file missing $ORIGIN: " + path);
        }
        return new ZoneFile(origin, defaultTtl, records);
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
     * Looks up records for a query name and type.
     */
    public List<DnsResourceRecord> lookup(String qname, DnsType type) {
        String normalized = normalizeName(qname);
        List<DnsResourceRecord> exact = recordsByName.get(normalized);
        if (exact == null || exact.isEmpty()) {
            return Collections.emptyList();
        }
        List<DnsResourceRecord> matches = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < exact.size(); i++) {
            DnsResourceRecord rr = exact.get(i);
            if (rr.getType() == type || type == DnsType.ANY) {
                matches.add(rr);
            }
        }
        if (!matches.isEmpty()) {
            return matches;
        }
        if (type == DnsType.CNAME) {
            return Collections.emptyList();
        }
        for (int i = 0; i < exact.size(); i++) {
            if (exact.get(i).getType() == DnsType.CNAME) {
                return Collections.singletonList(exact.get(i));
            }
        }
        return Collections.emptyList();
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

    private static DnsResourceRecord parseRecord(String owner, DnsType type,
            DnsClass dnsClass, int ttl, String[] tokens, int idx, String line)
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
