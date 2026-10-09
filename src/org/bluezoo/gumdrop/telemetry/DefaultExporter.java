/*
 * DefaultExporter.java
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

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.util.LaconicFormatter;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The exporter a runtime has when nothing else is configured: log
 * records go to {@link java.util.logging}, through the logger named by
 * the record's scope, so {@code logging.properties} and
 * {@link LaconicFormatter} apply as they always have. Traces and metrics
 * are not its business.
 *
 * <p>An operational record is published as a JUL record carrying the
 * resource bundle, the key and the attribute values as parameters, and
 * the formatter does the lookup and the substitution. A record with no
 * bundle is published as its body, or its key and attributes.
 *
 * <p>Records are queued and published by a thread of the exporter's
 * own, so the thread that emits an event never waits on a handler. Once
 * {@link #shutdown} has run the thread is gone and a record is published
 * on the emitting thread; and when the logger has no handler left, as
 * after {@code LogManager} has reset during JVM shutdown, the line is
 * written to stderr in the laconic form instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DefaultExporter implements TelemetryExporter {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    // Raw JUL, for what this exporter has to say about itself.
    private static final Logger logger = Logger.getLogger(DefaultExporter.class.getName());

    /** The number of records that may wait to be published. */
    public static final int DEFAULT_QUEUE_SIZE = 4096;

    private final EnumSet<LogLevel> levels = EnumSet.of(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR);
    private final BlockingQueue<LogRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Formatter fallback = new LaconicFormatter();
    private final Object lock = new Object();
    private final boolean threaded;
    // records offered to the queue and not yet published
    private final AtomicInteger pending = new AtomicInteger();
    private Thread thread;
    private volatile boolean running = true;

    /**
     * Creates an exporter with the default queue size.
     */
    public DefaultExporter() {
        this(DEFAULT_QUEUE_SIZE);
    }

    /**
     * Creates an exporter.
     *
     * @param queueSize the number of records that may wait to be published
     */
    public DefaultExporter(int queueSize) {
        this(queueSize, true);
    }

    DefaultExporter(int queueSize, boolean threaded) {
        this.queue = new ArrayBlockingQueue<LogRecord>(queueSize);
        this.threaded = threaded;
    }

    /**
     * Sets the levels this exporter publishes. The default is the
     * operational levels: INFO, WARN and ERROR.
     *
     * @param levels the levels
     * @return this exporter
     */
    public DefaultExporter levels(LogLevel... levels) {
        synchronized (this.levels) {
            this.levels.clear();
            for (LogLevel level : levels) {
                this.levels.add(level);
            }
        }
        return this;
    }

    @Override
    public boolean accepts(LogLevel level) {
        synchronized (levels) {
            return levels.contains(level);
        }
    }

    @Override
    public boolean acceptsTraces() {
        return false;
    }

    @Override
    public void export(Trace trace) {
    }

    @Override
    public void export(List<MetricData> metrics) {
    }

    @Override
    public void export(LogRecord record) {
        if (record == null || !accepts(record.getLevel())) {
            return;
        }
        if (!running) {
            synchronized (lock) {
                publish(record);
            }
            return;
        }
        ensureThread();
        if (queue.offer(record)) {
            pending.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
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
        Thread t;
        synchronized (lock) {
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drain();
        long lost = dropped.get();
        if (lost > 0) {
            logger.warning(MessageFormat.format(L10N.getString("warn.log_events_dropped"), Long.valueOf(lost)));
        }
    }

    private void ensureThread() {
        if (!threaded) {
            return;
        }
        synchronized (lock) {
            if (thread == null && running) {
                thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runPublisher();
                    }
                }, "DefaultExporter");
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    private void runPublisher() {
        while (running) {
            try {
                LogRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    synchronized (lock) {
                        publish(first);
                        pending.decrementAndGet();
                        drainLocked();
                        lock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                // shutdown wakes the thread this way; the loop condition ends it
            }
        }
    }

    /**
     * Publishes everything queued so far on the calling thread, and
     * waits for whatever the publisher thread had already taken.
     */
    private void drain() {
        synchronized (lock) {
            drainLocked();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pending.get() > 0 && thread != null && System.nanoTime() < deadline) {
                try {
                    lock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // The caller holds lock.
    private void drainLocked() {
        LogRecord record;
        while ((record = queue.poll()) != null) {
            publish(record);
            pending.decrementAndGet();
        }
    }

    // The caller holds lock.
    private void publish(LogRecord record) {
        String scope = record.getScope();
        Logger target = Logger.getLogger(scope != null ? scope : DefaultExporter.class.getName());
        java.util.logging.LogRecord jul = toJul(record);
        if (hasHandlers(target)) {
            target.log(jul);
        } else {
            System.err.print(fallback.format(jul));
            System.err.flush();
        }
    }

    static java.util.logging.LogRecord toJul(LogRecord record) {
        ResourceBundle bundle = record.getResourceBundle();
        List<Attribute> attributes = record.getAttributes();
        java.util.logging.LogRecord jul;
        if (bundle != null) {
            jul = new java.util.logging.LogRecord(julLevel(record.getLevel()), record.getKey());
            jul.setResourceBundle(bundle);
            jul.setResourceBundleName(bundle.getBaseBundleName());
            if (!attributes.isEmpty()) {
                Object[] parameters = new Object[attributes.size()];
                for (int i = 0; i < parameters.length; i++) {
                    parameters[i] = attributes.get(i).getValue();
                }
                jul.setParameters(parameters);
            }
        } else {
            jul = new java.util.logging.LogRecord(julLevel(record.getLevel()), plainMessage(record));
        }
        String scope = record.getScope();
        if (scope != null) {
            jul.setLoggerName(scope);
        }
        jul.setInstant(Instant.ofEpochSecond(0L, record.getTimeUnixNano()));
        jul.setThrown(record.getThrown());
        return jul;
    }

    // A record with no bundle has nothing to look up: its body if it has
    // one, else its name and attributes.
    private static String plainMessage(LogRecord record) {
        if (record.getBody() != null) {
            return record.getBody();
        }
        StringBuilder sb = new StringBuilder(record.getKey());
        for (Attribute attribute : record.getAttributes()) {
            sb.append(' ').append(attribute.getKey()).append('=').append(attribute.getValue());
        }
        return sb.toString();
    }

    static Level julLevel(LogLevel level) {
        switch (level) {
            case WARN:
                return Level.WARNING;
            case ERROR:
                return Level.SEVERE;
            case QLOG:
                return Level.FINE;
            case INFO:
            case ACCESS:
            default:
                return Level.INFO;
        }
    }

    // Whether a record logged to this logger would reach a handler.
    static boolean hasHandlers(Logger target) {
        for (Logger l = target; l != null; l = l.getParent()) {
            if (l.getHandlers().length > 0) {
                return true;
            }
            if (!l.getUseParentHandlers()) {
                return false;
            }
        }
        return false;
    }

}
