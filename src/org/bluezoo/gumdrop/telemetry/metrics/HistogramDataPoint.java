/*
 * HistogramDataPoint.java
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

import java.util.Arrays;

/**
 * A single data point for a histogram metric.
 * Contains the distribution of values across explicit buckets.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HistogramDataPoint {

    private final Attributes attributes;
    private final long startTimeUnixNano;
    private final long timeUnixNano;
    private final long count;
    private final double sum;
    private final long[] bucketCounts;
    private final double[] explicitBounds;
    private final double min;
    private final double max;

    /**
     * Creates a histogram data point.
     *
     * @param attributes the attributes identifying this time series
     * @param startTimeUnixNano the start time for cumulative temporality
     * @param timeUnixNano the timestamp of this data point
     * @param count the total number of recorded values
     * @param sum the sum of all recorded values
     * @param bucketCounts the count of values in each bucket (length = bounds.length + 1)
     * @param explicitBounds the upper bounds of each bucket (exclusive)
     * @param min the minimum recorded value
     * @param max the maximum recorded value
     */
    public HistogramDataPoint(Attributes attributes, long startTimeUnixNano,
                             long timeUnixNano, long count, double sum,
                             long[] bucketCounts, double[] explicitBounds,
                             double min, double max) {
        this.attributes = attributes != null ? attributes : Attributes.empty();
        this.startTimeUnixNano = startTimeUnixNano;
        this.timeUnixNano = timeUnixNano;
        this.count = count;
        this.sum = sum;
        this.bucketCounts = bucketCounts != null ? bucketCounts.clone() : new long[0];
        this.explicitBounds = explicitBounds != null ? explicitBounds.clone() : new double[0];
        this.min = min;
        this.max = max;
    }

    /**
     * Returns the attributes for this data point.
     */
    public Attributes getAttributes() {
        return attributes;
    }

    /**
     * Returns the start time for cumulative temporality.
     */
    public long getStartTimeUnixNano() {
        return startTimeUnixNano;
    }

    /**
     * Returns the timestamp of this data point.
     */
    public long getTimeUnixNano() {
        return timeUnixNano;
    }

    /**
     * Returns the total number of recorded values.
     */
    public long getCount() {
        return count;
    }

    /**
     * Returns the sum of all recorded values.
     */
    public double getSum() {
        return sum;
    }

    /**
     * Returns the count of values in each bucket.
     * The length is explicitBounds.length + 1.
     * Bucket[i] counts values where: bounds[i-1] <= value < bounds[i]
     * The first bucket counts values < bounds[0].
     * The last bucket counts values >= bounds[length-1].
     */
    public long[] getBucketCounts() {
        return bucketCounts.clone();
    }

    /**
     * Returns the upper bounds of each bucket (exclusive).
     */
    public double[] getExplicitBounds() {
        return explicitBounds.clone();
    }

    /**
     * Returns the minimum recorded value.
     */
    public double getMin() {
        return min;
    }

    /**
     * Returns the maximum recorded value.
     */
    public double getMax() {
        return max;
    }

    @Override
    public String toString() {
        return "HistogramDataPoint[" + attributes + 
               ", count=" + count + ", sum=" + sum +
               ", min=" + min + ", max=" + max +
               ", buckets=" + Arrays.toString(bucketCounts) + "]";
    }

}

