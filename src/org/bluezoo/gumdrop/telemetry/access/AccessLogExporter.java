/*
 * AccessLogExporter.java
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

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes {@link LogLevel#ACCESS} records (see {@link HttpAccessLog}) to
 * a file in Common Log Format or W3C Extended Log File Format. Records
 * of other levels, traces and metrics are not its business.
 *
 * <p>Records are queued and written by a thread of the exporter's own,
 * so the connection that completed the request never waits on the file.
 * The queue is bounded; what does not fit is counted and reported when
 * the exporter shuts down.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AccessLogExporter implements TelemetryExporter {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    // Raw JUL, for what this exporter has to say about itself.
    private static final Logger logger = Logger.getLogger(AccessLogExporter.class.getName());

    /** The queue size used by the two-argument constructor. */
    public static final int DEFAULT_QUEUE_SIZE = 8192;

    private final HttpAccessLogFormatter formatter;
    private final BufferedWriter writer;
    private final BlockingQueue<LogRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Object writeLock = new Object();
    private final Thread thread;
    private boolean preambleWritten;
    private volatile boolean running = true;

    /**
     * Creates an exporter appending to a file.
     *
     * @param path the file, created if it does not exist
     * @param format the line format
     * @param userSelection which user a line names
     * @throws IOException if the file cannot be opened
     */
    public AccessLogExporter(Path path, AccessLogFormat format,
            AccessLogUserSelection userSelection) throws IOException {
        this(path, format, userSelection, DEFAULT_QUEUE_SIZE);
    }

    /**
     * Creates an exporter appending to a file.
     *
     * @param path the file, created if it does not exist
     * @param format the line format
     * @param userSelection which user a line names
     * @param maxQueueSize the number of records that may wait to be written
     * @throws IOException if the file cannot be opened
     */
    public AccessLogExporter(Path path, AccessLogFormat format,
            AccessLogUserSelection userSelection, int maxQueueSize) throws IOException {
        this.formatter = new HttpAccessLogFormatter(format, userSelection);
        boolean fileExists = Files.exists(path);
        this.writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        this.preambleWritten = fileExists;
        this.queue = new ArrayBlockingQueue<LogRecord>(maxQueueSize);
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runWriter();
            }
        }, "AccessLogExporter");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean accepts(LogLevel level) {
        return level == LogLevel.ACCESS;
    }

    @Override
    public boolean acceptsTraces() {
        return false;
    }

    @Override
    public void export(LogRecord record) {
        if (!running || record == null || record.getLevel() != LogLevel.ACCESS) {
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
     * Returns how many records did not fit in the queue and were lost.
     *
     * @return the number of records dropped
     */
    public long getDroppedCount() {
        return dropped.get();
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
        thread.interrupt();
        try {
            thread.join(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain();
        synchronized (writeLock) {
            try {
                writer.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.access_log_close_failed"), e.getMessage()), e);
            }
        }
        long lost = dropped.get();
        if (lost > 0) {
            logger.warning(MessageFormat.format(L10N.getString("warn.access_log_events_dropped"),
                    Long.valueOf(lost)));
        }
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
            } catch (InterruptedException e) {
                // shutdown wakes the thread this way; the loop condition ends it
            }
        }
    }

    /** Writes everything queued so far, on the calling thread. */
    private void drain() {
        synchronized (writeLock) {
            drainLocked();
        }
    }

    private void drainLocked() {
        LogRecord record;
        while ((record = queue.poll()) != null) {
            write(record);
        }
        try {
            writer.flush();
        } catch (IOException e) {
            failed(e);
        }
    }

    // The caller holds writeLock.
    private void write(LogRecord record) {
        try {
            if (!preambleWritten) {
                for (String line : formatter.preambleLines()) {
                    writer.write(line);
                    writer.newLine();
                }
                preambleWritten = true;
            }
            writer.write(formatter.formatLine(record));
            writer.newLine();
        } catch (IOException e) {
            failed(e);
        }
    }

    private static void failed(IOException e) {
        logger.log(Level.SEVERE, MessageFormat.format(
                L10N.getString("err.access_log_write_failed"), e.getMessage()), e);
    }
}
