/*
 * MetricData.java
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

import java.util.Collections;
import java.util.List;

/**
 * Represents an aggregated metric ready for export.
 * Contains the metric identity, type, and data points.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MetricData {

    /**
     * The type of metric data.
     */
    public enum Type {
        /** A gauge represents a sampled value at a point in time. */
        GAUGE,
        /** A sum represents cumulative or delta values. */
        SUM,
        /** A histogram represents a distribution of values. */
        HISTOGRAM
    }

    private final String name;
    private final String description;
    private final String unit;
    private final Type type;
    private final AggregationTemporality temporality;
    private final boolean monotonic;
    private final List<NumberDataPoint> numberDataPoints;
    private final List<HistogramDataPoint> histogramDataPoints;

    private MetricData(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.unit = builder.unit;
        this.type = builder.type;
        this.temporality = builder.temporality;
        this.monotonic = builder.monotonic;
        this.numberDataPoints = builder.numberDataPoints != null ?
                Collections.unmodifiableList(builder.numberDataPoints) :
                Collections.emptyList();
        this.histogramDataPoints = builder.histogramDataPoints != null ?
                Collections.unmodifiableList(builder.histogramDataPoints) :
                Collections.emptyList();
    }

    /**
     * Returns the metric name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the metric description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the unit of measurement.
     */
    public String getUnit() {
        return unit;
    }

    /**
     * Returns the metric type.
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the aggregation temporality (for SUM and HISTOGRAM types).
     */
    public AggregationTemporality getTemporality() {
        return temporality;
    }

    /**
     * Returns true if this is a monotonic sum (counter).
     */
    public boolean isMonotonic() {
        return monotonic;
    }

    /**
     * Returns the number data points (for GAUGE and SUM types).
     */
    public List<NumberDataPoint> getNumberDataPoints() {
        return numberDataPoints;
    }

    /**
     * Returns the histogram data points (for HISTOGRAM type).
     */
    public List<HistogramDataPoint> getHistogramDataPoints() {
        return histogramDataPoints;
    }

    /**
     * Creates a builder for a gauge metric.
     */
    public static Builder gauge(String name) {
        return new Builder(name, Type.GAUGE);
    }

    /**
     * Creates a builder for a sum metric.
     */
    public static Builder sum(String name) {
        return new Builder(name, Type.SUM);
    }

    /**
     * Creates a builder for a histogram metric.
     */
    public static Builder histogram(String name) {
        return new Builder(name, Type.HISTOGRAM);
    }

    @Override
    public String toString() {
        return "MetricData[" + name + ", type=" + type + "]";
    }

    /**
     * Builder for MetricData.
     */
    public static final class Builder {

        private final String name;
        private final Type type;
        private String description;
        private String unit;
        private AggregationTemporality temporality = AggregationTemporality.CUMULATIVE;
        private boolean monotonic;
        private List<NumberDataPoint> numberDataPoints;
        private List<HistogramDataPoint> histogramDataPoints;

        private Builder(String name, Type type) {
            this.name = name;
            this.type = type;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setUnit(String unit) {
            this.unit = unit;
            return this;
        }

        public Builder setTemporality(AggregationTemporality temporality) {
            this.temporality = temporality;
            return this;
        }

        public Builder setMonotonic(boolean monotonic) {
            this.monotonic = monotonic;
            return this;
        }

        public Builder setNumberDataPoints(List<NumberDataPoint> dataPoints) {
            this.numberDataPoints = dataPoints;
            return this;
        }

        public Builder setHistogramDataPoints(List<HistogramDataPoint> dataPoints) {
            this.histogramDataPoints = dataPoints;
            return this;
        }

        public MetricData build() {
            return new MetricData(this);
        }
    }

}

