/*
 * AccessLogExporterTest.java
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

package org.bluezoo.gumdrop.telemetry.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AccessLogExporterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static LogRecord request(String target, int status, long bytes) {
        return HttpAccessLog.toRecord(null, System.currentTimeMillis(),
                new InetSocketAddress("127.0.0.1", 4321), "GET", target, "HTTP/1.1",
                null, null, status, bytes);
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    public void writesAClfLinePerAccessRecord() throws Exception {
        Path log = tmp.newFile("access.log").toPath();
        AccessLogExporter exporter = new AccessLogExporter(
                log, AccessLogFormat.CLF, AccessLogUserSelection.PROTOCOL_FIRST);
        assertTrue(exporter.accepts(LogLevel.ACCESS));
        assertFalse(exporter.accepts(LogLevel.INFO));
        assertFalse(exporter.acceptsTraces());
        exporter.export(request("/test", 200, 0L));
        exporter.export(request("/other", 404, 12L));
        exporter.flush();
        String text = text(log);
        assertTrue(text, text.contains("\"GET /test HTTP/1.1\" 200 0\n"));
        assertTrue(text, text.contains("\"GET /other HTTP/1.1\" 404 12\n"));
        exporter.shutdown();
        exporter.shutdown();
        assertEquals(0L, exporter.getDroppedCount());
    }

    @Test
    public void otherLevelsAndSignalsAreIgnored() throws Exception {
        Path log = tmp.newFile("access.log").toPath();
        AccessLogExporter exporter = new AccessLogExporter(
                log, AccessLogFormat.CLF, AccessLogUserSelection.PROTOCOL_FIRST);
        exporter.export(new LogRecord(LogLevel.INFO, "info.thing"));
        exporter.export(new LogRecord(LogLevel.QLOG, "quic:packet_sent"));
        exporter.export(new Trace("t", SpanKind.SERVER));
        exporter.export(TelemetryTestData.metrics());
        exporter.export((LogRecord) null);
        exporter.shutdown();
        assertEquals("", text(log));
    }

    @Test
    public void elffPreambleIsWrittenOnceToANewFile() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("access.elff");
        AccessLogExporter exporter = new AccessLogExporter(
                log, AccessLogFormat.ELFF, AccessLogUserSelection.BOTH);
        exporter.export(request("/a", 200, 1L));
        exporter.export(request("/b", 200, 2L));
        exporter.shutdown();
        String text = text(log);
        assertTrue(text, text.startsWith("#Version: 1.0\n#Fields: "));
        assertEquals(1, text.split("#Version").length - 1);
        assertEquals(4, text.split("\n").length);

        // appending to the file does not repeat it
        AccessLogExporter again = new AccessLogExporter(
                log, AccessLogFormat.ELFF, AccessLogUserSelection.BOTH);
        again.export(request("/c", 200, 3L));
        again.shutdown();
        text = text(log);
        assertEquals(1, text.split("#Version").length - 1);
        assertEquals(5, text.split("\n").length);
    }

    @Test
    public void recordsAfterShutdownAreDropped() throws Exception {
        Path log = tmp.newFile("access.log").toPath();
        AccessLogExporter exporter = new AccessLogExporter(
                log, AccessLogFormat.CLF, AccessLogUserSelection.PROTOCOL_FIRST);
        exporter.shutdown();
        exporter.export(request("/late", 200, 0L));
        assertEquals("", text(log));
    }

}
