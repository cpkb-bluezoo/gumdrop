/*
 * DoubleHistogram.java
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

package org.bluezoo.gumdrop.telemetry.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A histogram for recording distributions of double values.
 * Uses explicit bucket boundaries for efficient aggregation.
 *
 * <p>Example usage:
 * <pre>
 * DoubleHistogram latency = meter.histogramBuilder("http.request.duration")
 *     .setDescription("HTTP request latency in milliseconds")
 *     .setUnit("ms")
 *     .setExplicitBuckets(5, 10, 25, 50, 100, 250, 500, 1000)
 *     .build();
 * 
 * latency.record(45.2, Attributes.of("method", "GET"));
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DoubleHistogram implements Instrument {

    // Default bucket boundaries (suitable for latency in ms)
    private static final double[] DEFAULT_BOUNDARIES = {
        0, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 7500, 10000
    };

    private final String name;
    private final String description;
    private final String unit;
    private final double[] boundaries;
    private final long startTimeNano;

    private final Map<Attributes, HistogramBuckets> histograms;
    private final Map<Attributes, HistogramSnapshot> lastExportedSnapshots;

    DoubleHistogram(String name, String description, String unit, double[] boundaries) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.boundaries = boundaries != null ? boundaries.clone() : DEFAULT_BOUNDARIES;
        Arrays.sort(this.boundaries);
        this.startTimeNano = System.currentTimeMillis() * 1_000_000L;
        this.histograms = new ConcurrentHashMap<>();
        this.lastExportedSnapshots = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getUnit() {
        return unit;
    }

    /**
     * Records a value.
     *
     * @param value the value to record
     */
    public void record(double value) {
        record(value, Attributes.empty());
    }

    /**
     * Records a value with attributes.
     *
     * @param value the value to record
     * @param attributes the attributes for this measurement
     */
    public void record(double value, Attributes attributes) {
        if (attributes == null) {
            attributes = Attributes.empty();
        }
        HistogramBuckets buckets = histograms.get(attributes);
        if (buckets == null) {
            buckets = new HistogramBuckets(boundaries);
            HistogramBuckets existing = histograms.putIfAbsent(attributes, buckets);
            if (existing != null) {
                buckets = existing;
            }
        }
        buckets.record(value);
    }

    @Override
    public MetricData collect(AggregationTemporality temporality) {
        if (histograms.isEmpty()) {
            return null;
        }

        long nowNano = System.currentTimeMillis() * 1_000_000L;
        List<HistogramDataPoint> dataPoints = new ArrayList<>();

        for (Map.Entry<Attributes, HistogramBuckets> entry : histograms.entrySet()) {
            Attributes attrs = entry.getKey();
            HistogramBuckets buckets = entry.getValue();
            HistogramSnapshot snapshot = buckets.snapshot();

            long[] reportedCounts;
            long reportedCount;
            double reportedSum;
            double reportedMin;
            double reportedMax;
            long startTime;

            if (temporality == AggregationTemporality.DELTA) {
                HistogramSnapshot lastSnapshot = lastExportedSnapshots.get(attrs);
                if (lastSnapshot != null) {
                    // Compute delta
                    reportedCount = snapshot.count - lastSnapshot.count;
                    reportedSum = snapshot.sum - lastSnapshot.sum;
                    reportedCounts = new long[snapshot.bucketCounts.length];
                    for (int i = 0; i < reportedCounts.length; i++) {
                        reportedCounts[i] = snapshot.bucketCounts[i] - lastSnapshot.bucketCounts[i];
                    }
                    // Min/max for delta period - use current values as approximation
                    reportedMin = snapshot.min;
                    reportedMax = snapshot.max;
                } else {
                    reportedCount = snapshot.count;
                    reportedSum = snapshot.sum;
                    reportedCounts = snapshot.bucketCounts.clone();
                    reportedMin = snapshot.min;
                    reportedMax = snapshot.max;
                }
                lastExportedSnapshots.put(attrs, snapshot);
                startTime = nowNano;
            } else {
                reportedCount = snapshot.count;
                reportedSum = snapshot.sum;
                reportedCounts = snapshot.bucketCounts.clone();
                reportedMin = snapshot.min;
                reportedMax = snapshot.max;
                startTime = startTimeNano;
            }

            if (reportedCount > 0) {
                dataPoints.add(new HistogramDataPoint(
                    attrs, startTime, nowNano, reportedCount, reportedSum,
                    reportedCounts, boundaries, reportedMin, reportedMax
                ));
            }
        }

        if (dataPoints.isEmpty()) {
            return null;
        }

        return MetricData.histogram(name)
                .setDescription(description)
                .setUnit(unit)
                .setTemporality(temporality)
                .setHistogramDataPoints(dataPoints)
                .build();
    }

    /**
     * Internal bucket storage for a single time series.
     */
    private static final class HistogramBuckets {

        private final double[] boundaries;
        private final long[] counts; // counts.length = boundaries.length + 1
        private long totalCount;
        private double sum;
        private double min = Double.MAX_VALUE;
        private double max = Double.MIN_VALUE;

        HistogramBuckets(double[] boundaries) {
            this.boundaries = boundaries;
            this.counts = new long[boundaries.length + 1];
        }

        synchronized void record(double value) {
            totalCount++;
            sum += value;
            if (value < min) min = value;
            if (value > max) max = value;

            // Find bucket
            int bucket = findBucket(value);
            counts[bucket]++;
        }

        private int findBucket(double value) {
            // Binary search for the right bucket
            int low = 0;
            int high = boundaries.length;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (value >= boundaries[mid]) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        synchronized HistogramSnapshot snapshot() {
            return new HistogramSnapshot(totalCount, sum, counts.clone(), min, max);
        }
    }

    /**
     * Immutable snapshot of histogram state.
     */
    private static final class HistogramSnapshot {
        final long count;
        final double sum;
        final long[] bucketCounts;
        final double min;
        final double max;

        HistogramSnapshot(long count, double sum, long[] bucketCounts, double min, double max) {
            this.count = count;
            this.sum = sum;
            this.bucketCounts = bucketCounts;
            this.min = min;
            this.max = max;
        }
    }

    /**
     * Builder for DoubleHistogram.
     */
    public static class Builder {

        private final String name;
        private String description = "";
        private String unit = "";
        private double[] boundaries;

        public Builder(String name) {
            this.name = name;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setUnit(String unit) {
            this.unit = unit;
            return this;
        }

        /**
         * Sets explicit bucket boundaries.
         * Values less than the first boundary go into bucket 0.
         * Values greater than or equal to the last boundary go into the last bucket.
         */
        public Builder setExplicitBuckets(double... boundaries) {
            this.boundaries = boundaries;
            return this;
        }

        public DoubleHistogram build() {
            return new DoubleHistogram(name, description, unit, boundaries);
        }
    }

}

