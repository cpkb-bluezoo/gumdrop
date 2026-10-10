/*
 * DnssecChainValidatorTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DNSSEC data recorded from the real DNS by
 * {@code scripts/capture-dnssec-fixture.sh}, parsed from dig's zone-file
 * text. A recording is only valid at the time it was captured, so tests
 * validate with a fixed clock set from {@link #getCapturedAt()}.
 *
 * <p>The text format is a {@code ;; captured EPOCH} line, then one
 * {@code ;; query NAME TYPE} line per response, each followed by that
 * response's records. The last response is the answer being validated; the
 * others are what the chain walk fetches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DnssecRecordedData {

    private static final DateTimeFormatter SIG_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final long capturedAt;
    private final Map<String, List<DnsResourceRecord>> responses =
            new LinkedHashMap<String, List<DnsResourceRecord>>();
    private final Map<String, String> queryNames = new LinkedHashMap<String, String>();
    private final Map<String, DnsType> queryTypes = new LinkedHashMap<String, DnsType>();
    private String answerKey;

    private DnssecRecordedData(long capturedAt) {
        this.capturedAt = capturedAt;
    }

    /** Reads a recording from the test resources, e.g. {@code "cloudflare.com.zone"}. */
    static String read(String resource) throws IOException {
        InputStream in = DnssecRecordedData.class.getResourceAsStream("/dnssec/" + resource);
        if (in == null) {
            throw new IOException("missing test resource /dnssec/" + resource);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    static DnssecRecordedData load(String resource) throws Exception {
        return parse(read(resource));
    }

    static DnssecRecordedData parse(String text) throws Exception {
        long captured = 0;
        DnssecRecordedData data = null;
        String[] lines = text.split("\n");
        String currentKey = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(";; captured ")) {
                captured = Long.parseLong(line.substring(";; captured ".length()).trim());
                data = new DnssecRecordedData(captured);
                continue;
            }
            if (data == null) {
                throw new IllegalArgumentException("recording must begin with ';; captured'");
            }
            if (line.startsWith(";; query ")) {
                String[] parts = line.substring(";; query ".length()).trim().split("\\s+");
                DnsType type = DnsType.valueOf(parts[1]);
                currentKey = key(parts[0], type);
                data.responses.put(currentKey, new ArrayList<DnsResourceRecord>());
                data.queryNames.put(currentKey, parts[0]);
                data.queryTypes.put(currentKey, type);
                data.answerKey = currentKey;
                continue;
            }
            if (line.startsWith(";")) {
                continue;
            }
            data.responses.get(currentKey).add(parseRecord(line));
        }
        if (data == null) {
            throw new IllegalArgumentException("empty recording");
        }
        return data;
    }

    private static String key(String name, DnsType type) {
        String n = name.toLowerCase();
        if (!n.endsWith(".")) {
            n = n + ".";
        }
        return n + "/" + type.name();
    }

    private static DnsResourceRecord parseRecord(String line) throws Exception {
        String[] t = line.split("\\s+");
        String name = t[0];
        int ttl = Integer.parseInt(t[1]);
        // t[2] is the class (IN)
        DnsType type = DnsType.valueOf(t[3]);
        switch (type) {
            case A:
                return DnsResourceRecord.a(name, ttl, InetAddress.getByName(t[4]));
            case DNSKEY: {
                int flags = Integer.parseInt(t[4]);
                int algorithm = Integer.parseInt(t[6]);
                byte[] key = Base64.getDecoder().decode(join(t, 7));
                return DnsResourceRecord.dnskey(name, ttl, flags, algorithm, key);
            }
            case DS: {
                int tag = Integer.parseInt(t[4]);
                int algorithm = Integer.parseInt(t[5]);
                int digestType = Integer.parseInt(t[6]);
                byte[] digest = hex(join(t, 7));
                byte[] rdata = new byte[4 + digest.length];
                rdata[0] = (byte) (tag >> 8);
                rdata[1] = (byte) tag;
                rdata[2] = (byte) algorithm;
                rdata[3] = (byte) digestType;
                System.arraycopy(digest, 0, rdata, 4, digest.length);
                return new DnsResourceRecord(name, DnsType.DS, DnsClass.IN, ttl, rdata);
            }
            case RRSIG: {
                DnsType covered = DnsType.valueOf(t[4]);
                int algorithm = Integer.parseInt(t[5]);
                int labels = Integer.parseInt(t[6]);
                int originalTtl = Integer.parseInt(t[7]);
                long expiration = epoch(t[8]);
                long inception = epoch(t[9]);
                int keyTag = Integer.parseInt(t[10]);
                String signer = t[11];
                byte[] signature = Base64.getDecoder().decode(join(t, 12));
                return DnsResourceRecord.rrsig(name, ttl, covered, algorithm, labels,
                        originalTtl, expiration, inception, keyTag, signer, signature);
            }
            default:
                throw new IllegalArgumentException("unsupported record type in recording: " + type);
        }
    }

    private static String join(String[] tokens, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tokens.length; i++) {
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    private static long epoch(String sigTime) {
        return LocalDateTime.parse(sigTime, SIG_TIME).toEpochSecond(ZoneOffset.UTC);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** When the data was captured, in seconds since the epoch. */
    long getCapturedAt() {
        return capturedAt;
    }

    /** The records of the answer being validated (the last response recorded). */
    List<DnsResourceRecord> getAnswer() {
        return Collections.unmodifiableList(responses.get(answerKey));
    }

    /** Makes every recorded response except the answer available to a resolver. */
    void installIn(DnssecTestFixtures.CannedResolver resolver) {
        for (Map.Entry<String, List<DnsResourceRecord>> e : responses.entrySet()) {
            if (e.getKey().equals(answerKey)) {
                continue;
            }
            resolver.put(queryNames.get(e.getKey()), queryTypes.get(e.getKey()),
                    DnssecTestFixtures.message(e.getValue(),
                            Collections.<DnsResourceRecord>emptyList()));
        }
    }
}
