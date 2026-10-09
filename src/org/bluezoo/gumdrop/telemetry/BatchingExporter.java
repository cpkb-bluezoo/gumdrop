/*
 * BatchingExporter.java
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

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;

import java.util.EnumSet;

/**
 * The settings that the exporters which queue and send in batches have in
 * common: the log levels they take, how large a batch is, how long a batch
 * may wait, how many records may queue, and how metrics are collected for
 * them. The OTLP exporters and the JSONL file exporter extend it.
 *
 * <p>The settings are made on the exporter, before {@link
 * TelemetryConfig#init()} starts it, and each returns the exporter so that
 * they can be chained. They are read once, when the exporter starts.
 *
 * @param <E> the concrete exporter, which the settings return
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class BatchingExporter<E extends BatchingExporter<E>> implements TelemetryExporter {

    private final EnumSet<LogLevel> levels = EnumSet.of(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR);
    private int batchSize = 512;
    private long flushIntervalMs = 5000;
    private int maxQueueSize = 2048;
    private long metricsIntervalMs = 60000;
    private AggregationTemporality metricsTemporality = AggregationTemporality.CUMULATIVE;

    /**
     * Returns this exporter as its concrete type, for the settings to
     * return.
     *
     * @return this exporter
     */
    @SuppressWarnings("unchecked") // E is, by its bound, the concrete subclass of this
    protected final E self() {
        return (E) this;
    }

    /**
     * Sets the levels of log record this exporter takes. The default is
     * the operational levels: INFO, WARN and ERROR.
     *
     * @param levels the levels
     * @return this exporter
     */
    public E levels(LogLevel... levels) {
        synchronized (this.levels) {
            this.levels.clear();
            for (LogLevel level : levels) {
                this.levels.add(level);
            }
        }
        return self();
    }

    /**
     * Returns whether the exporter is set to take a level. Subclasses
     * combine this with whether they have anywhere to send it.
     *
     * @param level the level
     * @return true if the level is one of those set
     */
    protected final boolean takesLevel(LogLevel level) {
        synchronized (levels) {
            return levels.contains(level);
        }
    }

    /**
     * Returns the number of items sent in one batch.
     *
     * @return the batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of items sent in one batch. The default is 512.
     *
     * @param batchSize the batch size
     * @return this exporter
     */
    public E batchSize(int batchSize) {
        this.batchSize = batchSize;
        return self();
    }

    /**
     * Returns the longest time, in milliseconds, between exports.
     *
     * @return the interval
     */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    /**
     * Sets the longest time, in milliseconds, between exports. The default
     * is 5000.
     *
     * @param flushIntervalMs the interval
     * @return this exporter
     */
    public E flushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
        return self();
    }

    /**
     * Returns the number of items of each signal that may wait to be
     * exported.
     *
     * @return the queue size
     */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /**
     * Sets the number of items of each signal that may wait to be
     * exported; what does not fit is dropped. The default is 2048.
     *
     * @param maxQueueSize the queue size
     * @return this exporter
     */
    public E maxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
        return self();
    }

    /**
     * Returns the interval, in milliseconds, at which metrics are collected.
     *
     * @return the interval
     */
    public long getMetricsIntervalMs() {
        return metricsIntervalMs;
    }

    /**
     * Sets the interval, in milliseconds, at which metrics are collected
     * and exported. The default is 60000.
     *
     * @param metricsIntervalMs the interval
     * @return this exporter
     */
    public E metricsIntervalMs(long metricsIntervalMs) {
        this.metricsIntervalMs = metricsIntervalMs;
        return self();
    }

    /**
     * Returns the aggregation temporality of the metrics exported.
     *
     * @return the temporality
     */
    public AggregationTemporality getMetricsTemporality() {
        return metricsTemporality;
    }

    /**
     * Sets the aggregation temporality of the metrics exported. The
     * default is {@link AggregationTemporality#CUMULATIVE}.
     *
     * @param metricsTemporality DELTA or CUMULATIVE
     * @return this exporter
     */
    public E metricsTemporality(AggregationTemporality metricsTemporality) {
        this.metricsTemporality = metricsTemporality;
        return self();
    }

}
