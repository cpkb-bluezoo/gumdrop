/*
 * ZoneFileLoader.java
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
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@link ZoneFile} instances via {@link ZoneFileParser}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneFileLoader implements ZoneFileHandler {

    private static final int READ_SIZE = 8192;

    static ZoneFile load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        ParseState state = new ParseState();
        state.zoneRoot = absolute;
        ZoneFileLoader loader = new ZoneFileLoader(state);
        loader.feedFile(absolute, new ArrayDeque<Path>());
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

    private final ParseState state;
    private Path currentFile;
    private Path currentParentDir;
    private Deque<Path> activeIncludeStack;
    private EntryBuilder recordBuilder;
    private GenerateBuilder generateBuilder;

    private ZoneFileLoader(ParseState state) {
        this.state = state;
    }

    private void feedFile(Path file, Deque<Path> includeStack) throws IOException {
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
        currentFile = absolute;
        currentParentDir = absolute.getParent();
        activeIncludeStack = includeStack;
        ZoneFileParser parser = new ZoneFileParser(this);
        try (SeekableByteChannel channel = Files.newByteChannel(absolute)) {
            ByteBuffer buffer = ByteBuffer.allocate(READ_SIZE);
            while (true) {
                if (!parser.isUnderflow()) {
                    buffer.clear();
                } else {
                    buffer.compact();
                }
                int read = channel.read(buffer);
                buffer.flip();
                if (buffer.hasRemaining()) {
                    parser.receive(buffer);
                }
                if (read < 0) {
                    break;
                }
            }
            parser.close();
            endFile(absolute);
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

    @Override
    public void origin(String origin) {
        state.origin = origin;
    }

    @Override
    public void defaultTtl(int ttl) {
        state.defaultTtl = ttl;
    }

    @Override
    public void include(String filename, String originOverride) throws IOException {
        Path includePath = resolveIncludePath(currentParentDir, filename);
        if (!Files.isRegularFile(includePath)) {
            throw new IOException("$INCLUDE file not found: " + includePath);
        }
        String savedOrigin = state.origin;
        if (originOverride != null) {
            state.origin = ZoneFile.normalizeName(originOverride);
        }
        feedFile(includePath, activeIncludeStack);
        state.origin = savedOrigin;
    }

    @Override
    public void unknownDirective(String name) {
    }

    @Override
    public void beginGenerate(String rangeSpec, String ownerTemplate) throws IOException {
        if (state.origin == null) {
            throw new IOException("$GENERATE before $ORIGIN");
        }
        generateBuilder = new GenerateBuilder(rangeSpec, ownerTemplate);
        recordBuilder = null;
    }

    @Override
    public void beginRecord(String ownerToken) throws IOException {
        if (state.origin == null) {
            throw new IOException("Zone record before $ORIGIN");
        }
        recordBuilder = new EntryBuilder(ownerToken);
        generateBuilder = null;
    }

    @Override
    public void appendField(String token) throws IOException {
        if (generateBuilder != null) {
            generateBuilder.append(token);
        } else if (recordBuilder != null) {
            recordBuilder.append(token);
        }
    }

    @Override
    public void endGenerate() throws IOException {
        if (generateBuilder == null) {
            throw new IOException("Internal parser state: $GENERATE");
        }
        generateBuilder.finish(state);
        generateBuilder = null;
    }

    @Override
    public void endRecord() throws IOException {
        if (recordBuilder == null) {
            throw new IOException("Internal parser state: record");
        }
        recordBuilder.finish(state);
        recordBuilder = null;
    }

    @Override
    public void endFile(Path file) {
    }

    private static final class ParseState {
        String origin;
        int defaultTtl = 3600;
        final Map<String, List<DnsResourceRecord>> records =
                new LinkedHashMap<String, List<DnsResourceRecord>>();
        ZoneFile.SoaData soaData;
        Path zoneRoot;
    }

    private static final class EntryBuilder {
        private final String ownerToken;
        private int ttl = -1;
        private boolean sawTtl;
        private boolean sawClass;
        private String typeToken;
        private final List<String> rdata = new ArrayList<String>();
        private Phase phase = Phase.MAYBE_TTL;

        private enum Phase {
            MAYBE_TTL,
            MAYBE_CLASS,
            TYPE,
            RDATA
        }

        EntryBuilder(String ownerToken) {
            this.ownerToken = ownerToken;
        }

        void append(String token) throws IOException {
            switch (phase) {
                case MAYBE_TTL:
                    if (isDigits(token)) {
                        ttl = Integer.parseInt(token);
                        sawTtl = true;
                        phase = Phase.MAYBE_CLASS;
                        return;
                    }
                    phase = Phase.MAYBE_CLASS;
                    // fall through
                case MAYBE_CLASS:
                    if (isDnsClass(token)) {
                        sawClass = true;
                        phase = Phase.TYPE;
                        return;
                    }
                    typeToken = token.toUpperCase(Locale.ROOT);
                    phase = Phase.RDATA;
                    return;
                case TYPE:
                    typeToken = token.toUpperCase(Locale.ROOT);
                    phase = Phase.RDATA;
                    return;
                case RDATA:
                    rdata.add(token);
                    return;
                default:
                    throw new IOException("Malformed zone record");
            }
        }

        void finish(ParseState state) throws IOException {
            if (typeToken == null) {
                throw new IOException("Malformed zone record: missing type");
            }
            int effectiveTtl = sawTtl ? ttl : state.defaultTtl;
            String owner = ownerName(ownerToken, state.origin);
            DnsType type = DnsType.valueOf(typeToken);
            String[] rdataTokens = rdata.toArray(new String[rdata.size()]);
            if (type == DnsType.SOA && owner.equals(state.origin) && state.soaData == null) {
                state.soaData = parseSoaTokens(rdataTokens);
            }
            DnsResourceRecord rr = parseRecord(owner, type, effectiveTtl, rdataTokens);
            addRecord(state.records, owner, rr);
        }
    }

    private static final class GenerateBuilder {
        private final RangeSpec range;
        private final String ownerTemplate;
        private int ttl = -1;
        private boolean sawTtl;
        private String typeToken;
        private final List<String> rdata = new ArrayList<String>();
        private Phase phase = Phase.MAYBE_TTL;

        private enum Phase {
            MAYBE_TTL,
            MAYBE_CLASS,
            TYPE,
            RDATA
        }

        GenerateBuilder(String rangeSpec, String ownerTemplate) throws IOException {
            this.range = RangeSpec.parse(rangeSpec);
            this.ownerTemplate = ownerTemplate;
        }

        void append(String token) throws IOException {
            switch (phase) {
                case MAYBE_TTL:
                    if (isDigits(token)) {
                        ttl = Integer.parseInt(token);
                        sawTtl = true;
                        phase = Phase.MAYBE_CLASS;
                        return;
                    }
                    phase = Phase.MAYBE_CLASS;
                    // fall through
                case MAYBE_CLASS:
                    if (isDnsClass(token)) {
                        phase = Phase.TYPE;
                        return;
                    }
                    typeToken = token.toUpperCase(Locale.ROOT);
                    phase = Phase.RDATA;
                    return;
                case TYPE:
                    typeToken = token.toUpperCase(Locale.ROOT);
                    phase = Phase.RDATA;
                    return;
                case RDATA:
                    rdata.add(token);
                    return;
                default:
                    throw new IOException("Malformed $GENERATE");
            }
        }

        void finish(ParseState state) throws IOException {
            if (typeToken == null) {
                throw new IOException("Malformed $GENERATE: missing type");
            }
            int effectiveTtl = sawTtl ? ttl : state.defaultTtl;
            DnsType type = DnsType.valueOf(typeToken);
            String[] templateRdata = rdata.toArray(new String[rdata.size()]);
            for (int n = range.start; range.matches(n); n += range.step) {
                String counter = range.format(n);
                String ownerToken = substituteGenerateCounter(ownerTemplate, counter);
                String owner = ownerName(ownerToken, state.origin);
                String[] expanded = new String[templateRdata.length];
                for (int i = 0; i < templateRdata.length; i++) {
                    expanded[i] = substituteGenerateCounter(templateRdata[i], counter);
                }
                if (type == DnsType.SOA && owner.equals(state.origin) && state.soaData == null) {
                    state.soaData = parseSoaTokens(expanded);
                }
                DnsResourceRecord rr = parseRecord(owner, type, effectiveTtl, expanded);
                addRecord(state.records, owner, rr);
            }
        }
    }

    private static boolean isDigits(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDnsClass(String token) {
        if ("IN".equalsIgnoreCase(token)) {
            return true;
        }
        return token.length() == 2 && Character.isLetter(token.charAt(0));
    }

    private static final class RangeSpec {
        final int start;
        final int end;
        final int step;
        final int width;

        private RangeSpec(int start, int end, int step, int width) {
            this.start = start;
            this.end = end;
            this.step = step;
            this.width = width;
        }

        static RangeSpec parse(String spec) throws IOException {
            String rangePart = spec;
            int step = 1;
            int slash = spec.indexOf('/');
            if (slash >= 0) {
                rangePart = spec.substring(0, slash);
                step = Integer.parseInt(spec.substring(slash + 1));
                if (step == 0) {
                    throw new IOException("Invalid $GENERATE step: " + spec);
                }
            }
            int dash = rangePart.indexOf('-');
            if (dash < 0) {
                int single = Integer.parseInt(rangePart);
                int width = leadingZeroWidth(rangePart);
                return new RangeSpec(single, single, 1, width);
            }
            String startToken = rangePart.substring(0, dash);
            String endToken = rangePart.substring(dash + 1);
            int width = Math.max(leadingZeroWidth(startToken), leadingZeroWidth(endToken));
            int start = Integer.parseInt(startToken);
            int end = Integer.parseInt(endToken);
            if (step > 0 ? start > end : start < end) {
                throw new IOException("Invalid $GENERATE range: " + spec);
            }
            return new RangeSpec(start, end, step, width);
        }

        boolean matches(int n) {
            return step > 0 ? n <= end : n >= end;
        }

        String format(int n) {
            if (width <= 0) {
                return Integer.toString(n);
            }
            String s = Integer.toString(n);
            if (s.length() >= width) {
                return s;
            }
            StringBuilder sb = new StringBuilder(width);
            for (int i = s.length(); i < width; i++) {
                sb.append('0');
            }
            sb.append(s);
            return sb.toString();
        }

        private static int leadingZeroWidth(String token) {
            if (token.length() <= 1 || token.charAt(0) != '0') {
                return 0;
            }
            return token.length();
        }
    }

    static String substituteGenerateCounter(String template, String counter) {
        if (template.indexOf('$') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + counter.length());
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '$') {
                out.append(counter);
            } else {
                out.append(c);
            }
        }
        return out.toString();
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

    private static String ownerName(String nameToken, String origin) {
        if ("*".equals(nameToken)) {
            return "*." + origin;
        }
        return ZoneFile.expandName(nameToken, origin);
    }

    private static ZoneFile.SoaData parseSoaTokens(String[] tokens) throws IOException {
        if (tokens.length < 7) {
            throw new IOException("Malformed SOA");
        }
        int idx = 0;
        String mname = ZoneFile.normalizeName(tokens[idx++]);
        String rname = ZoneFile.normalizeName(tokens[idx++]);
        long serial = Long.parseLong(tokens[idx++]);
        int refresh = Integer.parseInt(tokens[idx++]);
        int retry = Integer.parseInt(tokens[idx++]);
        int expire = Integer.parseInt(tokens[idx++]);
        int minimum = Integer.parseInt(tokens[idx++]);
        return new ZoneFile.SoaData(mname, rname, (int) serial, refresh, retry, expire,
                minimum);
    }

    private static DnsResourceRecord parseRecord(String owner, DnsType type,
            int ttl, String[] tokens) throws IOException {
        int idx = 0;
        switch (type) {
            case A:
                return DnsResourceRecord.a(owner, ttl,
                        InetAddress.getByName(tokens[idx]));
            case AAAA:
                return DnsResourceRecord.aaaa(owner, ttl,
                        InetAddress.getByName(tokens[idx]));
            case NS:
                return DnsResourceRecord.ns(owner, ttl, ZoneFile.normalizeName(tokens[idx]));
            case CNAME:
                return DnsResourceRecord.cname(owner, ttl, ZoneFile.normalizeName(tokens[idx]));
            case PTR:
                return DnsResourceRecord.ptr(owner, ttl, ZoneFile.normalizeName(tokens[idx]));
            case MX: {
                int preference = Integer.parseInt(tokens[idx++]);
                return DnsResourceRecord.mx(owner, ttl, preference,
                        ZoneFile.normalizeName(tokens[idx]));
            }
            case TXT: {
                StringBuilder sb = new StringBuilder();
                for (int i = idx; i < tokens.length; i++) {
                    if (i > idx) {
                        sb.append(' ');
                    }
                    sb.append(tokens[i]);
                }
                return DnsResourceRecord.txt(owner, ttl, sb.toString());
            }
            case SOA: {
                if (tokens.length < idx + 7) {
                    throw new IOException("Malformed SOA");
                }
                String mname = ZoneFile.normalizeName(tokens[idx++]);
                String rname = ZoneFile.normalizeName(tokens[idx++]);
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
}
