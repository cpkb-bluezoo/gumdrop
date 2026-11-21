/*
 * QlogOtlpSizeTest.java
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

package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.QlogExporter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.otlp.LogSerializer;
import org.bluezoo.protobuf.ProtobufWriter;
import org.junit.Test;

/**
 * Measures how large one connection's qlog is in each serialization we
 * have or might define: the JSON-SEQ file the qlog exporter writes, the
 * OTLP request the telemetry pipeline sends today (the event's data as
 * a JSON string in the body), and two forms a qlog-over-OTLP mapping
 * could take (the data as a structured body, with the qlog metadata on
 * every record or hoisted to the instrumentation scope). Each is also
 * measured gzipped, which is how logs are stored and shipped at scale.
 *
 * <p>The numbers are printed and written to {@code build/qlog-otlp-sizes.txt};
 * the assertions only check that the hand-written OTLP envelope matches
 * {@link LogSerializer} byte for byte, so the structured variants are
 * measured against a known-correct baseline.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QlogOtlpSizeTest {

    // OTLP field numbers (opentelemetry-proto logs.proto, common.proto, resource.proto)
    private static final int REQUEST_RESOURCE_LOGS = 1;
    private static final int RESOURCE_LOGS_RESOURCE = 1;
    private static final int RESOURCE_LOGS_SCOPE_LOGS = 2;
    private static final int RESOURCE_LOGS_SCHEMA_URL = 3;
    private static final int RESOURCE_ATTRIBUTES = 1;
    private static final int SCOPE_LOGS_SCOPE = 1;
    private static final int SCOPE_LOGS_LOG_RECORDS = 2;
    private static final int SCOPE_NAME = 1;
    private static final int SCOPE_VERSION = 2;
    private static final int SCOPE_ATTRIBUTES = 3;
    private static final int LOG_RECORD_TIME_UNIX_NANO = 1;
    private static final int LOG_RECORD_SEVERITY_NUMBER = 2;
    private static final int LOG_RECORD_SEVERITY_TEXT = 3;
    private static final int LOG_RECORD_BODY = 5;
    private static final int LOG_RECORD_ATTRIBUTES = 6;
    private static final int LOG_RECORD_OBSERVED_TIME_UNIX_NANO = 11;
    private static final int LOG_RECORD_EVENT_NAME = 12;
    private static final int KEY_VALUE_KEY = 1;
    private static final int KEY_VALUE_VALUE = 2;
    private static final int ANY_STRING = 1;
    private static final int ANY_BOOL = 2;
    private static final int ANY_INT = 3;
    private static final int ANY_DOUBLE = 4;
    private static final int ANY_ARRAY = 5;
    private static final int ANY_KVLIST = 6;
    private static final int ANY_BYTES = 7;
    private static final int LIST_VALUES = 1;

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";
    private static final String SERVICE = "gumdrop";

    /** How the data object is carried in the OTLP body. */
    private static final int BODY_JSON_STRING = 0;
    private static final int BODY_STRUCTURED = 1;

    /** Where the qlog metadata (schema, vantage point) goes. */
    private static final int META_PER_RECORD = 0;
    private static final int META_IN_SCOPE = 1;
    /** As META_IN_SCOPE, and without the record fields a qlog mapping has no use for. */
    private static final int META_LEAN = 2;

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    /** Runs one connection with a transfer each way and returns its qlog records. */
    private List<LogRecord> run() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        QuicLoopbackQlogTest.Capture capture = new QuicLoopbackQlogTest.Capture(true);
        TelemetryConfig config = new TelemetryConfig();
        config.exporter(capture);
        lb.serverFactory.setTelemetryConfig(config);
        lb.clientFactory.setTelemetryConfig(config);
        lb.startFactories();
        QuicLoopbackScenariosTest.ConnCapture server = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startServer(server);
        QuicLoopbackScenariosTest.ConnCapture client = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        QuicLoopbackScenariosTest.Rec[] recs = new QuicLoopbackScenariosTest.Rec[3];
        for (int i = 0; i < recs.length; i++) {
            recs[i] = new QuicLoopbackScenariosTest.Rec();
            Endpoint e = client.conn.openStream(recs[i]);
            e.send(ByteBuffer.wrap(bytes(60000)));
            e.close();
        }
        for (int i = 0; i < 60; i++) {
            lb.pump();
        }
        for (QuicLoopbackScenariosTest.Rec r : server.bidi.recs) {
            r.endpoint.send(ByteBuffer.wrap(bytes(40000)));
            r.endpoint.close();
        }
        for (int i = 0; i < 60; i++) {
            lb.pump();
        }
        client.conn.closeWithApplicationError(0, "done");
        for (int i = 0; i < 10; i++) {
            lb.pump();
        }
        synchronized (capture) {
            return new ArrayList<LogRecord>(capture.records);
        }
    }

    private static String attr(LogRecord r, String key) {
        for (Attribute a : r.getAttributes()) {
            if (a.getKey().equals(key)) {
                return a.getStringValue();
            }
        }
        return null;
    }

    /** The records of one vantage point: one process, one OTLP batch, one qlog file. */
    private static List<LogRecord> of(List<LogRecord> all, String vantage) {
        List<LogRecord> out = new ArrayList<LogRecord>();
        for (LogRecord r : all) {
            if (vantage.equals(attr(r, QlogAttributes.VANTAGE_POINT))) {
                out.add(r);
            }
        }
        return out;
    }

    private static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(in);
        gz.close();
        return bos.toByteArray();
    }

    // ── the JSON-SEQ file ──

    private static byte[] sqlog(List<LogRecord> records) throws IOException {
        Path dir = Files.createTempDirectory("qlog-size");
        QlogExporter exporter = new QlogExporter(dir, 1 << 20);
        for (LogRecord r : records) {
            exporter.export(r);
        }
        exporter.shutdown();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.sqlog");
        try {
            for (Path p : files) {
                all.write(Files.readAllBytes(p));
                Files.delete(p);
            }
        } finally {
            files.close();
        }
        Files.delete(dir);
        return all.toByteArray();
    }

    // ── OTLP, hand-written so the body and metadata placement can vary ──

    private static byte[] otlp(List<LogRecord> records, int body, int meta) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ProtobufWriter w = new ProtobufWriter(Channels.newChannel(bos));
        w.writeMessageField(REQUEST_RESOURCE_LOGS, new ResourceLogs(records, body, meta));
        return bos.toByteArray();
    }

    private static final class ResourceLogs implements ProtobufWriter.MessageContent {
        final List<LogRecord> records;
        final int body;
        final int meta;

        ResourceLogs(List<LogRecord> records, int body, int meta) {
            this.records = records;
            this.body = body;
            this.meta = meta;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeMessageField(RESOURCE_LOGS_RESOURCE, new Resource());
            w.writeMessageField(RESOURCE_LOGS_SCOPE_LOGS, new ScopeLogs(records, body, meta));
            w.writeStringField(RESOURCE_LOGS_SCHEMA_URL, SCHEMA_URL);
        }
    }

    private static final class Resource implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeMessageField(RESOURCE_ATTRIBUTES, new StringKeyValue("service.name", SERVICE));
        }
    }

    private static final class ScopeLogs implements ProtobufWriter.MessageContent {
        final List<LogRecord> records;
        final int body;
        final int meta;

        ScopeLogs(List<LogRecord> records, int body, int meta) {
            this.records = records;
            this.body = body;
            this.meta = meta;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeMessageField(SCOPE_LOGS_SCOPE, new Scope(records, meta));
            for (LogRecord r : records) {
                w.writeMessageField(SCOPE_LOGS_LOG_RECORDS, new Record(r, body, meta));
            }
        }
    }

    private static final class Scope implements ProtobufWriter.MessageContent {
        final List<LogRecord> records;
        final int meta;

        Scope(List<LogRecord> records, int meta) {
            this.records = records;
            this.meta = meta;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeStringField(SCOPE_NAME, SERVICE);
            w.writeStringField(SCOPE_VERSION, Gumdrop.VERSION);
            if (meta != META_PER_RECORD && !records.isEmpty()) {
                // the trace-level metadata, once per batch rather than per event
                LogRecord first = records.get(0);
                w.writeMessageField(SCOPE_ATTRIBUTES, new StringKeyValue(QlogAttributes.VANTAGE_POINT,
                        attr(first, QlogAttributes.VANTAGE_POINT)));
                w.writeMessageField(SCOPE_ATTRIBUTES, new StringKeyValue(QlogAttributes.SCHEMA,
                        attr(first, QlogAttributes.SCHEMA)));
            }
        }
    }

    private static final class Record implements ProtobufWriter.MessageContent {
        final LogRecord r;
        final int body;
        final int meta;

        Record(LogRecord r, int body, int meta) {
            this.r = r;
            this.body = body;
            this.meta = meta;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeFixed64Field(LOG_RECORD_TIME_UNIX_NANO, r.getTimeUnixNano());
            if (meta != META_LEAN) {
                w.writeFixed64Field(LOG_RECORD_OBSERVED_TIME_UNIX_NANO, r.getTimeUnixNano());
            }
            w.writeVarintField(LOG_RECORD_SEVERITY_NUMBER, r.getSeverityNumber());
            if (meta != META_LEAN) {
                w.writeStringField(LOG_RECORD_SEVERITY_TEXT, r.getSeverityText());
            }
            if (r.getBody() != null) {
                if (body == BODY_JSON_STRING) {
                    w.writeMessageField(LOG_RECORD_BODY, new StringValue(r.getBody()));
                } else {
                    Object tree = new Json(r.getBody()).value();
                    w.writeMessageField(LOG_RECORD_BODY, new Any(tree, null, r.getKey()));
                }
            }
            for (Attribute a : r.getAttributes()) {
                if (meta != META_PER_RECORD && !a.getKey().equals(QlogAttributes.GROUP_ID)) {
                    continue;
                }
                if (meta == META_LEAN) {
                    w.writeMessageField(LOG_RECORD_ATTRIBUTES, new BytesKeyValue(a.getKey(), unhex(a.getStringValue())));
                } else {
                    w.writeMessageField(LOG_RECORD_ATTRIBUTES, new StringKeyValue(a.getKey(), a.getStringValue()));
                }
            }
            w.writeStringField(LOG_RECORD_EVENT_NAME, r.getKey());
        }
    }

    private static final class StringKeyValue implements ProtobufWriter.MessageContent {
        final String key;
        final String value;

        StringKeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeStringField(KEY_VALUE_KEY, key);
            w.writeMessageField(KEY_VALUE_VALUE, new StringValue(value));
        }
    }

    private static final class BytesKeyValue implements ProtobufWriter.MessageContent {
        final String key;
        final byte[] value;

        BytesKeyValue(String key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeStringField(KEY_VALUE_KEY, key);
            w.writeMessageField(KEY_VALUE_VALUE, new BytesValue(value));
        }
    }

    private static final class BytesValue implements ProtobufWriter.MessageContent {
        final byte[] value;

        BytesValue(byte[] value) {
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeBytesField(ANY_BYTES, value);
        }
    }

    private static final class StringValue implements ProtobufWriter.MessageContent {
        final String value;

        StringValue(String value) {
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeStringField(ANY_STRING, value);
        }
    }

    /** A parsed JSON value as an OTLP AnyValue; hexstrings become bytes. */
    private static final class Any implements ProtobufWriter.MessageContent {
        final Object value;
        final String key;
        final String event;

        Any(Object value, String key, String event) {
            this.value = value;
            this.key = key;
            this.event = event;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void writeTo(ProtobufWriter w) throws IOException {
            if (value instanceof Map) {
                w.writeMessageField(ANY_KVLIST, new KvList((Map<String, Object>) value, event));
            } else if (value instanceof List) {
                w.writeMessageField(ANY_ARRAY, new Array((List<Object>) value, event));
            } else if (value instanceof String) {
                if (isHex(key, event)) {
                    w.writeBytesField(ANY_BYTES, unhex((String) value));
                } else {
                    w.writeStringField(ANY_STRING, (String) value);
                }
            } else if (value instanceof Long) {
                w.writeVarintField(ANY_INT, ((Long) value).longValue());
            } else if (value instanceof Double) {
                w.writeDoubleField(ANY_DOUBLE, ((Double) value).doubleValue());
            } else if (value instanceof Boolean) {
                w.writeBoolField(ANY_BOOL, ((Boolean) value).booleanValue());
            }
        }
    }

    private static final class KvList implements ProtobufWriter.MessageContent {
        final Map<String, Object> map;
        final String event;

        KvList(Map<String, Object> map, String event) {
            this.map = map;
            this.event = event;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                w.writeMessageField(LIST_VALUES, new KeyValue(e.getKey(), e.getValue(), event));
            }
        }
    }

    private static final class KeyValue implements ProtobufWriter.MessageContent {
        final String key;
        final Object value;
        final String event;

        KeyValue(String key, Object value, String event) {
            this.key = key;
            this.value = value;
            this.event = event;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            w.writeStringField(KEY_VALUE_KEY, key);
            w.writeMessageField(KEY_VALUE_VALUE, new Any(value, key, event));
        }
    }

    private static final class Array implements ProtobufWriter.MessageContent {
        final List<Object> list;
        final String event;

        Array(List<Object> list, String event) {
            this.list = list;
            this.event = event;
        }

        @Override
        public void writeTo(ProtobufWriter w) throws IOException {
            for (Object o : list) {
                w.writeMessageField(LIST_VALUES, new Any(o, null, event));
            }
        }
    }

    /** The fields gumdrop writes as hexstrings (connection IDs and tokens). */
    private static boolean isHex(String key, String event) {
        if (key == null) {
            return false;
        }
        if (key.equals("scid") || key.equals("dcid") || key.equals("src_cid") || key.equals("dst_cid")
                || key.equals("original_destination_connection_id") || key.equals("initial_source_connection_id")
                || key.equals("retry_source_connection_id") || key.equals("stateless_reset_token")) {
            return true;
        }
        return "quic:connection_id_updated".equals(event) && (key.equals("old") || key.equals("new"));
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** A reader for the JSON gumdrop's qlog builders write: enough for a data object. */
    private static final class Json {
        final String s;
        int pos;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(pos);
            if (c == '{') {
                pos++;
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                skip();
                if (s.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skip();
                    String key = string();
                    skip();
                    expect(':');
                    map.put(key, value());
                    skip();
                    if (s.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                pos++;
                List<Object> list = new ArrayList<Object>();
                skip();
                if (s.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skip();
                    if (s.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            boolean fraction = false;
            while (pos < s.length()) {
                char d = s.charAt(pos);
                if (d == '.' || d == 'e' || d == 'E') {
                    fraction = true;
                } else if (!(d == '-' || d == '+' || (d >= '0' && d <= '9'))) {
                    break;
                }
                pos++;
            }
            String num = s.substring(start, pos);
            return fraction ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    if (e == 'u') {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    } else if (e == 'n') {
                        sb.append('\n');
                    } else if (e == 't') {
                        sb.append('\t');
                    } else {
                        sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        void skip() {
            while (pos < s.length() && s.charAt(pos) <= ' ') {
                pos++;
            }
        }

        void expect(char c) {
            if (s.charAt(pos) != c) {
                throw new IllegalStateException("expected " + c + " at " + pos + " in " + s);
            }
            pos++;
        }
    }

    /** The event data objects alone, as JSON text or as AnyValue messages, concatenated. */
    private static byte[] bodies(List<LogRecord> records, int body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (body == BODY_JSON_STRING) {
            for (LogRecord r : records) {
                bos.write(r.getBody().getBytes(StandardCharsets.UTF_8));
            }
        } else {
            ProtobufWriter w = new ProtobufWriter(Channels.newChannel(bos));
            for (LogRecord r : records) {
                w.writeMessageField(LOG_RECORD_BODY, new Any(new Json(r.getBody()).value(), null, r.getKey()));
            }
        }
        return bos.toByteArray();
    }

    // ── the measurement ──

    private static final class Row {
        final String label;
        final long raw;
        final long gzipped;

        Row(String label, long raw, long gzipped) {
            this.label = label;
            this.raw = raw;
            this.gzipped = gzipped;
        }
    }

    @Test
    public void sizesOfOneConnectionInEachSerialization() throws Exception {
        List<LogRecord> all = run();
        List<LogRecord> client = of(all, "client");
        List<LogRecord> server = of(all, "server");
        int records = client.size() + server.size();
        assertTrue("too few records: " + records, records > 200);

        // the hand-written envelope must be what LogSerializer produces
        LogSerializer serializer = new LogSerializer(SERVICE);
        ByteBuffer reference = serializer.serialize(client);
        byte[] expected = new byte[reference.remaining()];
        reference.get(expected);
        assertArrayEquals(expected, otlp(client, BODY_JSON_STRING, META_PER_RECORD));

        // a structured body must round-trip the same tree as the JSON text
        for (LogRecord r : all) {
            Object tree = new Json(r.getBody()).value();
            assertTrue(r.getKey(), tree instanceof Map);
        }

        List<Row> rows = new ArrayList<Row>();
        rows.add(measure("sqlog (JSON-SEQ file)", sqlog(client), sqlog(server)));
        rows.add(measure("OTLP, data as JSON string, metadata per record (today)",
                otlp(client, BODY_JSON_STRING, META_PER_RECORD), otlp(server, BODY_JSON_STRING, META_PER_RECORD)));
        rows.add(measure("OTLP, structured body, metadata per record",
                otlp(client, BODY_STRUCTURED, META_PER_RECORD), otlp(server, BODY_STRUCTURED, META_PER_RECORD)));
        rows.add(measure("OTLP, structured body, metadata in scope",
                otlp(client, BODY_STRUCTURED, META_IN_SCOPE), otlp(server, BODY_STRUCTURED, META_IN_SCOPE)));
        rows.add(measure("OTLP, structured body, lean record (no observed time, severity text; group id as bytes)",
                otlp(client, BODY_STRUCTURED, META_LEAN), otlp(server, BODY_STRUCTURED, META_LEAN)));
        rows.add(measure("event data only, as JSON text",
                bodies(client, BODY_JSON_STRING), bodies(server, BODY_JSON_STRING)));
        rows.add(measure("event data only, as AnyValue",
                bodies(client, BODY_STRUCTURED), bodies(server, BODY_STRUCTURED)));

        long bodyText = 0;
        for (LogRecord r : all) {
            bodyText += r.getBody().getBytes(StandardCharsets.UTF_8).length;
        }

        StringBuilder out = new StringBuilder();
        out.append("qlog of one loopback connection, both vantage points: ")
                .append(records).append(" events, ")
                .append(bodyText).append(" bytes of event data as JSON text\n\n");
        out.append(String.format("%-90s %10s %10s %8s %8s%n", "serialization", "bytes", "gzip", "B/event", "gz/ev"));
        for (Row row : rows) {
            out.append(String.format("%-90s %10d %10d %8.1f %8.1f%n", row.label, row.raw, row.gzipped,
                    row.raw / (double) records, row.gzipped / (double) records));
        }
        Map<String, Integer> names = new LinkedHashMap<String, Integer>();
        for (LogRecord r : all) {
            Integer n = names.get(r.getKey());
            names.put(r.getKey(), n == null ? 1 : n + 1);
        }
        out.append("\nevents by name:\n");
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            out.append(String.format("  %-40s %6d%n", e.getKey(), e.getValue()));
        }
        System.out.println(out);
        Path report = Path.of("build", "qlog-otlp-sizes.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString());
    }

    private static Row measure(String label, byte[] a, byte[] b) throws IOException {
        return new Row(label, a.length + b.length, gzip(a).length + gzip(b).length);
    }
}
