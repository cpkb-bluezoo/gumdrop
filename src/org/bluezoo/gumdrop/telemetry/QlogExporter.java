/*
 * QlogExporter.java
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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

/**
 * Writes the log records of level {@link LogLevel#QLOG} as qlog files
 * (draft-ietf-quic-qlog-main-schema-14, JSON-SEQ serialisation): one file
 * per connection, named {@code {group id}_{vantage point}.sqlog}. Traces,
 * metrics and records of other levels are not its business and are ignored.
 *
 * <p>A file starts with a {@code QlogFileSeq} record describing the trace
 * and continues with one record per event ({@code time}, {@code name},
 * {@code data}); each record is a 0x1E byte, the JSON text and a newline.
 * Event times are written in milliseconds relative to the trace's
 * reference time, the time of the first event, to the microsecond.
 *
 * <p>Records are queued and written by a thread of the exporter's own, so
 * the transport never waits on a file. The queue is large, because a trace
 * with holes is of little use, but bounded: what does not fit is counted
 * and reported when the exporter shuts down.
 *
 * <p>This class is the place that knows which revision of the qlog drafts
 * is written; see {@link #QLOG_REVISION}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QlogExporter implements TelemetryExporter {

    private static final ResourceBundle L10N = ResourceBundle.getBundle(
            "org.bluezoo.gumdrop.telemetry.L10N", Trace.class.getModule());
    private static final Logger logger = Logger.getLogger(QlogExporter.class.getName());

    /** The queue size used when the exporter is created from configuration. */
    public static final int DEFAULT_QUEUE_SIZE = 65536;

    /** The revision of the qlog drafts the files follow. */
    public static final String QLOG_REVISION = "draft-ietf-quic-qlog-main-schema-14, "
            + "draft-ietf-quic-qlog-quic-events-13, draft-ietf-quic-qlog-h3-events-13";

    private static final long IDLE_NANOS = 60_000_000_000L;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final BlockingQueue<LogRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Object writeLock = new Object();
    // files being written, by file stem
    private final Map<String, TraceFile> open = new HashMap<String, TraceFile>();
    // reference times of files written and since closed, by file stem
    private final Map<String, Long> finished = new HashMap<String, Long>();
    private final Thread thread;
    private volatile boolean running = true;

    /**
     * Creates an exporter that writes qlog files to a directory, with a
     * thread of its own to do it.
     *
     * @param directory the directory, created if it does not exist
     * @param maxQueueSize the number of events that may wait to be written
     */
    public QlogExporter(Path directory, int maxQueueSize) {
        this(directory, maxQueueSize, true);
    }

    QlogExporter(Path directory, int maxQueueSize, boolean startThread) {
        this.directory = directory;
        this.queue = new ArrayBlockingQueue<LogRecord>(maxQueueSize);
        if (startThread) {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWriter();
                }
            }, "QlogExporter");
            thread.setDaemon(true);
            thread.start();
        } else {
            thread = null;
        }
        logger.info(MessageFormat.format(L10N.getString("info.qlog_exporter_started"), directory));
    }

    @Override
    public boolean accepts(LogLevel level) {
        return level == LogLevel.QLOG;
    }

    @Override
    public boolean acceptsTraces() {
        return false;
    }

    @Override
    public void export(LogRecord record) {
        if (!running || record == null || record.getLevel() != LogLevel.QLOG) {
            return;
        }
        if (!queue.offer(record)) {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void export(Trace trace) {
    }

    @Override
    public void export(List<MetricData> metrics) {
    }

    /**
     * Returns how many events did not fit in the queue and were lost.
     *
     * @return the number of events dropped
     */
    public long getDroppedCount() {
        return dropped.get();
    }

    int getOpenFileCount() {
        synchronized (writeLock) {
            return open.size();
        }
    }

    @Override
    public void flush() {
        drain();
    }

    @Override
    public void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drain();
        synchronized (writeLock) {
            for (Iterator<Map.Entry<String, TraceFile>> i = open.entrySet().iterator(); i.hasNext();) {
                Map.Entry<String, TraceFile> entry = i.next();
                entry.getValue().close();
                finished.put(entry.getKey(), Long.valueOf(entry.getValue().referenceNanos));
                i.remove();
            }
        }
        long lost = dropped.get();
        if (lost > 0) {
            logger.warning(MessageFormat.format(L10N.getString("warn.qlog_events_dropped"), Long.valueOf(lost)));
        }
        logger.info(L10N.getString("info.qlog_exporter_shutdown"));
    }

    private void runWriter() {
        while (running) {
            try {
                LogRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    synchronized (writeLock) {
                        write(first);
                        drainLocked();
                    }
                }
                closeIdle(System.nanoTime());
            } catch (InterruptedException e) {
                // shutdown wakes the thread this way; the loop condition ends it
            }
        }
    }

    /** Writes everything queued so far. */
    void drain() {
        synchronized (writeLock) {
            drainLocked();
        }
    }

    private void drainLocked() {
        LogRecord record;
        while ((record = queue.poll()) != null) {
            write(record);
        }
        for (TraceFile file : open.values()) {
            file.flush();
        }
    }

    /** Closes the files that have had no event since a minute before the given time. */
    void closeIdle(long nowNanos) {
        synchronized (writeLock) {
            for (Iterator<Map.Entry<String, TraceFile>> i = open.entrySet().iterator(); i.hasNext();) {
                Map.Entry<String, TraceFile> entry = i.next();
                if (nowNanos - entry.getValue().lastWriteNanos >= IDLE_NANOS) {
                    entry.getValue().close();
                    finished.put(entry.getKey(), Long.valueOf(entry.getValue().referenceNanos));
                    i.remove();
                }
            }
        }
    }

    // Keeps a group id to what can safely be part of a file name.
    private static String safeName(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static String vantagePoint(String value) {
        if ("client".equals(value) || "server".equals(value)) {
            return value;
        }
        return "unknown";
    }

    // The caller holds writeLock.
    private void write(LogRecord record) {
        String name = record.getKey();
        String group = safeName(record.getString(QlogAttributes.GROUP_ID));
        String vantage = vantagePoint(record.getString(QlogAttributes.VANTAGE_POINT));
        String stem = group + "_" + vantage;
        TraceFile file = open.get(stem);
        if (file == null) {
            Long reference = finished.get(stem);
            file = openFile(stem, group, vantage, reference, record.getTimeUnixNano());
            if (file == null) {
                return;
            }
            open.put(stem, file);
        }
        file.writeEvent(record, name);
        if ("quic:connection_closed".equals(name)) {
            file.close();
            finished.put(stem, Long.valueOf(file.referenceNanos));
            open.remove(stem);
        }
    }

    private TraceFile openFile(String stem, String group, String vantage, Long reference, long firstNanos) {
        try {
            Files.createDirectories(directory);
            Path path = directory.resolve(stem + ".sqlog");
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            TraceFile file;
            if (reference != null) {
                file = new TraceFile(out, reference.longValue());
            } else {
                // the reference time is the first event's, to the millisecond
                file = new TraceFile(out, Math.floorDiv(firstNanos, 1_000_000L) * 1_000_000L);
                file.writeHeader(group, vantage);
            }
            return file;
        } catch (IOException e) {
            logger.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.qlog_open_failed"),
                    stem, e.getMessage()));
            return null;
        }
    }

    static void escape(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    /** Milliseconds, to the microsecond, as a JSON number. */
    static String milliseconds(long deltaNanos) {
        long abs = Math.abs(deltaNanos);
        long micros = (abs / 1000L) % 1000L;
        StringBuilder sb = new StringBuilder(24);
        if (deltaNanos < 0) {
            sb.append('-');
        }
        sb.append(abs / 1_000_000L).append('.');
        if (micros < 100) {
            sb.append('0');
        }
        if (micros < 10) {
            sb.append('0');
        }
        sb.append(micros);
        return sb.toString();
    }

    /** One open file and what is needed to write events to it. */
    private static final class TraceFile {
        final OutputStream out;
        final long referenceNanos;
        long lastWriteNanos = System.nanoTime();

        TraceFile(OutputStream out, long referenceNanos) {
            this.out = out;
            this.referenceNanos = referenceNanos;
        }

        void writeHeader(String group, String vantage) {
            String epoch = ISO_MILLIS.format(Instant.ofEpochMilli(referenceNanos / 1_000_000L));
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"file_schema\":\"urn:ietf:params:qlog:file:sequential\",");
            sb.append("\"serialization_format\":\"application/qlog+json-seq\",");
            sb.append("\"trace\":{\"vantage_point\":{\"type\":");
            escape(sb, vantage);
            sb.append("},\"description\":");
            escape(sb, "gumdrop, " + QLOG_REVISION);
            sb.append(",\"event_schemas\":[");
            sb.append("\"").append(QlogAttributes.SCHEMA_QUIC).append("\",");
            sb.append("\"").append(QlogAttributes.SCHEMA_HTTP3).append("\",");
            sb.append("\"").append(QlogAttributes.SCHEMA_HTTP).append("\"],");
            sb.append("\"common_fields\":{\"group_id\":");
            escape(sb, group);
            sb.append(",\"time_format\":\"relative_to_epoch\",\"reference_time\":{");
            sb.append("\"clock_type\":\"system\",\"epoch\":\"").append(epoch).append("\",");
            sb.append("\"wall_clock_time\":\"").append(epoch).append("\"}}}}");
            writeRecord(sb);
        }

        void writeEvent(LogRecord record, String name) {
            String data = record.getBody();
            StringBuilder sb = new StringBuilder(64 + (data == null ? 2 : data.length()));
            sb.append("{\"time\":").append(milliseconds(record.getTimeUnixNano() - referenceNanos));
            sb.append(",\"name\":");
            escape(sb, name);
            sb.append(",\"data\":").append(data == null || data.isEmpty() ? "{}" : data).append('}');
            writeRecord(sb);
            lastWriteNanos = System.nanoTime();
        }

        private void writeRecord(StringBuilder json) {
            try {
                out.write(0x1e);
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            } catch (IOException e) {
                logger.log(Level.FINE, e.getMessage(), e);
            }
        }

        void flush() {
            try {
                out.flush();
            } catch (IOException e) {
                logger.log(Level.FINE, e.getMessage(), e);
            }
        }

        void close() {
            try {
                out.close();
            } catch (IOException e) {
                logger.log(Level.FINE, e.getMessage(), e);
            }
        }
    }
}
