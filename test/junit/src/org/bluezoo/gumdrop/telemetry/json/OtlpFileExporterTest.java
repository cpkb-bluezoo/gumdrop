/*
 * OtlpFileExporterTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry.json;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

/**
 * Tests for {@link OtlpFileExporter} writing to an in-memory file system.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpFileExporterTest {

    private static String read(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void delete(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve("sub").resolve("traces.json"));
        Files.deleteIfExists(dir.resolve("sub").resolve("logs.json"));
        Files.deleteIfExists(dir.resolve("sub").resolve("metrics.json"));
        Files.deleteIfExists(dir.resolve("sub"));
        Files.deleteIfExists(dir.resolve("blocker"));
        Files.deleteIfExists(dir);
    }

    @Test
    public void testWritesAllSignals() throws IOException {
        Path dir = MemoryTemp.createTempDirectory("otlpfile");
        try {
            Path sub = dir.resolve("sub");
            Path traces = sub.resolve("traces.json");
            Path logs = sub.resolve("logs.json");
            Path metrics = sub.resolve("metrics.json");
            TelemetryConfig config = new TelemetryConfig();
            config.setServiceName("svc");
            config.setServiceInstanceId("inst");
            config.setDeploymentEnvironment("test");
            config.setMetricsEnabled(true);
            Meter meter = config.getMeter("scope");
            LongCounter counter = meter.counterBuilder("c").build();
            counter.add(5L);
            OtlpFileExporter e = new OtlpFileExporter(traces, logs, metrics);
            e.setMetricsIntervalMs(60000L);
            e.setMaxQueueSize(2);
            e.init(config);
            e.export(TelemetryTestData.richTrace());
            e.export(TelemetryTestData.richTrace());
            e.export(TelemetryTestData.richTrace());
            List<LogRecord> records = TelemetryTestData.logRecords();
            for (LogRecord r : records) {
                e.export(r);
            }
            List<MetricData> m = TelemetryTestData.metrics();
            e.export(m);
            e.export(m);
            e.export(m);
            e.export((org.bluezoo.gumdrop.telemetry.Trace) null);
            e.export((LogRecord) null);
            e.export((List<MetricData>) null);
            e.flush();
            e.shutdown();
            e.export(TelemetryTestData.richTrace());
            assertTrue(read(traces).contains("resourceSpans"));
            assertTrue(read(logs).contains("resourceLogs"));
            assertTrue(read(metrics).contains("resourceMetrics"));
        } finally {
            delete(dir);
        }
    }

    /**
     * A flush or shutdown request must not cost the data it is asking for.
     * The export thread used to be interrupted to wake it, and an interrupt
     * that finds a thread writing to a file channel closes the channel, so
     * what was queued was lost whenever the request caught the thread at
     * work. Repeated, because it takes that timing to show.
     */
    @Test
    public void testFlushAndShutdownKeepQueuedData() throws IOException {
        for (int i = 0; i < 200; i++) {
            Path dir = MemoryTemp.createTempDirectory("otlpfile");
            try {
                Path sub = dir.resolve("sub");
                Path traces = sub.resolve("traces.json");
                Path logs = sub.resolve("logs.json");
                Path metrics = sub.resolve("metrics.json");
                TelemetryConfig config = new TelemetryConfig();
                config.setServiceName("svc");
                OtlpFileExporter e = new OtlpFileExporter(traces, logs, metrics);
                e.init(config);
                e.export(TelemetryTestData.richTrace());
                e.flush();
                e.shutdown();
                String written = read(traces);
                assertTrue("run " + i + ": " + written, written.contains("resourceSpans"));
            } finally {
                delete(dir);
            }
        }
    }

    @Test
    public void testUnwritablePathFallsBack() throws IOException {
        Path dir = MemoryTemp.createTempDirectory("otlpfile");
        try {
            Path blocker = dir.resolve("blocker");
            Files.write(blocker, new byte[0]);
            Path bad = blocker.resolve("x.json");
            TelemetryConfig config = new TelemetryConfig();
            // Capture System.out to prove the fallback writes to it and
            // that shutdown never closes the process-wide stream.
            final boolean[] closed = new boolean[1];
            ByteArrayOutputStream sink = new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    closed[0] = true;
                    super.close();
                }
            };
            PrintStream saved = System.out;
            PrintStream capture = new PrintStream(sink, true);
            System.setOut(capture);
            try {
                OtlpFileExporter e = new OtlpFileExporter(bad, bad, bad);
                e.init(config);
                e.export(TelemetryTestData.richTrace());
                e.flush();
                e.shutdown();
                assertFalse("System.out must not be closed", closed[0]);
                assertFalse(capture.checkError());
                capture.flush();
                String out = sink.toString("UTF-8");
                assertTrue(out, out.contains("resourceSpans"));
            } finally {
                System.setOut(saved);
            }
        } finally {
            delete(dir);
        }
    }

    @Test
    public void testFileBufferSizeIsASettingOfTheExporter() {
        OtlpFileExporter e = new OtlpFileExporter();
        assertEquals(8192, e.getFileBufferSize());
        e.setFileBufferSize(4096);
        assertEquals(4096, e.getFileBufferSize());
    }

    @Test
    public void testAcceptsNothingUntilStarted() throws IOException {
        Path dir = MemoryTemp.createTempDirectory("otlpfile");
        try {
            Path sub = dir.resolve("sub");
            OtlpFileExporter e = new OtlpFileExporter(
                    sub.resolve("traces.json"), sub.resolve("logs.json"), sub.resolve("metrics.json"));
            assertFalse(e.accepts(LogLevel.INFO));
            assertFalse(e.acceptsTraces());
            e.shutdown();
            e.init(new TelemetryConfig());
            assertTrue(e.accepts(LogLevel.INFO));
            assertTrue(e.acceptsTraces());
            assertFalse(e.accepts(LogLevel.ACCESS));
            e.setLevels(LogLevel.ACCESS);
            assertTrue(e.accepts(LogLevel.ACCESS));
            e.shutdown();
        } finally {
            delete(dir);
        }
    }
}
