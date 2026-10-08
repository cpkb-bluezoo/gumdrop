/*
 * QlogExporterTest.java
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

package org.bluezoo.gumdrop.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.FlatJson;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The qlog JSON-SEQ file exporter: file naming, the header record, event
 * framing and times, routing of what it is given, and drop accounting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QlogExporterTest {

    private static final long T0 = 1_700_000_000_000_000_000L;
    private Path dir;

    @Before
    public void createDirectory() throws IOException {
        dir = MemoryTemp.createTempDirectory("qlog");
    }

    @After
    public void deleteDirectory() throws IOException {
        DirectoryStream<Path> files = Files.newDirectoryStream(dir);
        try {
            for (Path p : files) {
                Files.deleteIfExists(p);
            }
        } finally {
            files.close();
        }
        Files.deleteIfExists(dir);
    }

    static LogRecord event(long timeNanos, String group, String vantage, String name, String data) {
        return new LogRecord(timeNanos, LogLevel.QLOG, name)
                .body(data)
                .attr(QlogAttributes.SCHEMA, QlogAttributes.SCHEMA_QUIC)
                .attr(QlogAttributes.GROUP_ID, group)
                .attr(QlogAttributes.VANTAGE_POINT, vantage);
    }

    /** The records of a JSON-SEQ file, as text without the framing. */
    private static List<String> records(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<String> result = new ArrayList<String>();
        int start = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x1e) {
                assertEquals("a record starts only after a terminator", -1, start);
                start = i + 1;
            } else if (bytes[i] == '\n') {
                assertTrue("a terminator ends a record", start >= 0);
                result.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = -1;
            }
        }
        assertEquals("the last record is terminated", -1, start);
        return result;
    }

    private Path file(String name) {
        return dir.resolve(name);
    }

    @Test
    public void oneFilePerConnectionNamedByGroupAndVantagePoint() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.export(event(T0 + 1000, "bb02", "client", "quic:connection_started", "{}"));
        e.export(event(T0 + 2000, "aa01", "server", "quic:packet_lost", "{}"));
        e.flush();
        e.shutdown();
        assertTrue(Files.exists(file("aa01_server.sqlog")));
        assertTrue(Files.exists(file("bb02_client.sqlog")));
        assertEquals(3, records(file("aa01_server.sqlog")).size());
        assertEquals(2, records(file("bb02_client.sqlog")).size());
    }

    @Test
    public void firstRecordDescribesTheTrace() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.shutdown();
        Map<String, String> header = FlatJson.flatten(records(file("aa01_server.sqlog")).get(0));
        assertEquals("urn:ietf:params:qlog:file:sequential", header.get("file_schema"));
        assertEquals("application/qlog+json-seq", header.get("serialization_format"));
        assertEquals("server", header.get("trace.vantage_point.type"));
        assertEquals("urn:ietf:params:qlog:events:quic", header.get("trace.event_schemas.0"));
        assertEquals("urn:ietf:params:qlog:events:http3", header.get("trace.event_schemas.1"));
        assertEquals("urn:ietf:params:qlog:events:http", header.get("trace.event_schemas.2"));
        assertEquals("aa01", header.get("trace.common_fields.group_id"));
        assertEquals("relative_to_epoch", header.get("trace.common_fields.time_format"));
        assertEquals("system", header.get("trace.common_fields.reference_time.clock_type"));
        assertEquals("2023-11-14T22:13:20.000Z", header.get("trace.common_fields.reference_time.epoch"));
        assertTrue(header.containsKey("trace.description"));
    }

    @Test
    public void eventsCarryRelativeTimeNameAndData() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "aa01", "client", "quic:connection_started", "{\"src_port\":1}"));
        e.export(event(T0 + 1_500_250L, "aa01", "client", "quic:packet_lost",
                "{\"header\":{\"packet_number\":7}}"));
        e.shutdown();
        List<String> lines = records(file("aa01_client.sqlog"));
        assertEquals(3, lines.size());
        Map<String, String> first = FlatJson.flatten(lines.get(1));
        assertEquals(0.0, Double.parseDouble(first.get("time")), 1e-9);
        assertEquals("quic:connection_started", first.get("name"));
        assertEquals("1", first.get("data.src_port"));
        Map<String, String> second = FlatJson.flatten(lines.get(2));
        assertEquals(1.500, Double.parseDouble(second.get("time")), 1e-9);
        assertEquals("quic:packet_lost", second.get("name"));
        assertEquals("7", second.get("data.header.packet_number"));
    }

    @Test
    public void timesAreWrittenToTheMicrosecond() {
        assertEquals("1.500", QlogExporter.milliseconds(1_500_250L));
        assertEquals("0.001", QlogExporter.milliseconds(1_000L));
        assertEquals("0.010", QlogExporter.milliseconds(10_999L));
        assertEquals("-0.100", QlogExporter.milliseconds(-100_000L));
        assertEquals("12345.678", QlogExporter.milliseconds(12_345_678_999L));
    }

    @Test
    public void everyRecordIsFramedAsJsonSeq() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        for (int i = 0; i < 20; i++) {
            e.export(event(T0 + i, "aa01", "server", "quic:packet_lost", "{}"));
        }
        e.shutdown();
        byte[] bytes = Files.readAllBytes(file("aa01_server.sqlog"));
        assertEquals(0x1e, bytes[0]);
        assertEquals('\n', bytes[bytes.length - 1]);
        assertEquals(21, records(file("aa01_server.sqlog")).size());
    }

    @Test
    public void recordsOfOtherLevelsAndOtherSignalsAreIgnored() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(new LogRecord(LogLevel.INFO, "info.plain"));
        e.export(new LogRecord(LogLevel.ACCESS, "http.server.request").body("{}"));
        e.export(new Trace("t", SpanKind.SERVER));
        e.export(new ArrayList<org.bluezoo.gumdrop.telemetry.metrics.MetricData>());
        e.export((LogRecord) null);
        e.shutdown();
        DirectoryStream<Path> files = Files.newDirectoryStream(dir);
        try {
            assertFalse(files.iterator().hasNext());
        } finally {
            files.close();
        }
        assertTrue(e.accepts(LogLevel.QLOG));
        assertFalse(e.accepts(LogLevel.INFO));
        assertFalse(e.acceptsTraces());
    }

    @Test
    public void groupIdCannotEscapeTheDirectory() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "../../etc/passwd", "server", "quic:connection_started", "{}"));
        e.shutdown();
        DirectoryStream<Path> files = Files.newDirectoryStream(dir);
        try {
            int count = 0;
            for (Path p : files) {
                count++;
                assertEquals(dir, p.getParent());
                assertTrue(p.getFileName().toString().endsWith("_server.sqlog"));
                assertFalse(p.getFileName().toString().contains("/"));
                assertFalse(p.getFileName().toString().contains(".."));
            }
            assertEquals(1, count);
        } finally {
            files.close();
        }
    }

    @Test
    public void vantagePointIsRestrictedToClientAndServer() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "aa01", "../x", "quic:connection_started", "{}"));
        e.shutdown();
        assertTrue(Files.exists(file("aa01_unknown.sqlog")));
    }

    @Test
    public void shortfallIsCountedWhenTheQueueOverflows() throws Exception {
        // no writer thread, so nothing drains until shutdown
        QlogExporter e = new QlogExporter(dir, 3, false);
        for (int i = 0; i < 10; i++) {
            e.export(event(T0 + i, "aa01", "server", "quic:packet_lost", "{}"));
        }
        assertEquals(7, e.getDroppedCount());
        e.shutdown();
        assertEquals("header and the three that fitted", 4, records(file("aa01_server.sqlog")).size());
    }

    @Test
    public void connectionClosedEventClosesTheFile() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000, false);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.drain();
        assertEquals(1, e.getOpenFileCount());
        e.export(event(T0 + 5, "aa01", "server", "quic:connection_closed", "{}"));
        e.drain();
        assertEquals(0, e.getOpenFileCount());
        e.shutdown();
        assertEquals(3, records(file("aa01_server.sqlog")).size());
    }

    @Test
    public void lateEventAfterCloseIsAppendedWithoutASecondHeader() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000, false);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.export(event(T0 + 5, "aa01", "server", "quic:connection_closed", "{}"));
        e.drain();
        e.export(event(T0 + 9, "aa01", "server", "quic:packet_lost", "{}"));
        e.shutdown();
        List<String> lines = records(file("aa01_server.sqlog"));
        assertEquals(4, lines.size());
        assertEquals("quic:packet_lost", FlatJson.flatten(lines.get(3)).get("name"));
        int headers = 0;
        for (String line : lines) {
            if (FlatJson.flatten(line).containsKey("file_schema")) {
                headers++;
            }
        }
        assertEquals(1, headers);
    }

    @Test
    public void idleFilesAreClosed() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000, false);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.drain();
        assertEquals(1, e.getOpenFileCount());
        e.closeIdle(System.nanoTime() + 3_600_000_000_000L);
        assertEquals(0, e.getOpenFileCount());
        e.shutdown();
    }

    @Test
    public void eventsBeforeTheReferenceTimeHaveNegativeTime() throws Exception {
        QlogExporter e = new QlogExporter(dir, 1000);
        e.export(event(T0, "aa01", "server", "quic:connection_started", "{}"));
        e.export(event(T0 - 2_000_000L, "aa01", "server", "quic:packet_lost", "{}"));
        e.shutdown();
        assertEquals(-2.0, Double.parseDouble(FlatJson.flatten(records(file("aa01_server.sqlog")).get(2)).get("time")),
                1e-9);
    }
}
