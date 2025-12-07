/*
 * NumberDataPoint.java
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

/**
 * A single data point for a counter or gauge metric.
 * Contains attributes identifying the time series, timestamps, and the value.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NumberDataPoint {

    private final Attributes attributes;
    private final long startTimeUnixNano;
    private final long timeUnixNano;
    private final boolean isDouble;
    private final long longValue;
    private final double doubleValue;

    /**
     * Creates a data point with a long value.
     */
    public NumberDataPoint(Attributes attributes, long startTimeUnixNano,
                          long timeUnixNano, long value) {
        this.attributes = attributes != null ? attributes : Attributes.empty();
        this.startTimeUnixNano = startTimeUnixNano;
        this.timeUnixNano = timeUnixNano;
        this.isDouble = false;
        this.longValue = value;
        this.doubleValue = 0.0;
    }

    /**
     * Creates a data point with a double value.
     */
    public NumberDataPoint(Attributes attributes, long startTimeUnixNano,
                          long timeUnixNano, double value) {
        this.attributes = attributes != null ? attributes : Attributes.empty();
        this.startTimeUnixNano = startTimeUnixNano;
        this.timeUnixNano = timeUnixNano;
        this.isDouble = true;
        this.longValue = 0L;
        this.doubleValue = value;
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
     * Returns true if the value is a double.
     */
    public boolean isDouble() {
        return isDouble;
    }

    /**
     * Returns the long value (valid only if isDouble() is false).
     */
    public long getLongValue() {
        return longValue;
    }

    /**
     * Returns the double value (valid only if isDouble() is true).
     */
    public double getDoubleValue() {
        return doubleValue;
    }

    @Override
    public String toString() {
        return "NumberDataPoint[" + attributes + ", value=" + 
               (isDouble ? doubleValue : longValue) + "]";
    }

}

