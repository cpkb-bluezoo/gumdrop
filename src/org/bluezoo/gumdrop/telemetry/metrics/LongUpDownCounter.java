/*
 * LongUpDownCounter.java
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * A counter that can increase or decrease.
 * Useful for tracking things like active connections, queue size, etc.
 *
 * <p>Example usage:
 * <pre>
 * LongUpDownCounter activeConnections = meter.upDownCounterBuilder("connections.active")
 *     .setDescription("Number of active connections")
 *     .build();
 * 
 * activeConnections.add(1);   // Connection opened
 * activeConnections.add(-1);  // Connection closed
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LongUpDownCounter implements Instrument {

    private final String name;
    private final String description;
    private final String unit;
    private final long startTimeNano;

    private final Map<Attributes, LongAdder> counters;
    private final Map<Attributes, Long> lastExportedValues;

    LongUpDownCounter(String name, String description, String unit) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.startTimeNano = System.currentTimeMillis() * 1_000_000L;
        this.counters = new ConcurrentHashMap<>();
        this.lastExportedValues = new ConcurrentHashMap<>();
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
     * Adds a value to the counter (can be positive or negative).
     *
     * @param value the value to add
     */
    public void add(long value) {
        add(value, Attributes.empty());
    }

    /**
     * Adds a value to the counter with attributes.
     *
     * @param value the value to add
     * @param attributes the attributes for this measurement
     */
    public void add(long value, Attributes attributes) {
        if (attributes == null) {
            attributes = Attributes.empty();
        }
        LongAdder adder = counters.get(attributes);
        if (adder == null) {
            adder = new LongAdder();
            LongAdder existing = counters.putIfAbsent(attributes, adder);
            if (existing != null) {
                adder = existing;
            }
        }
        adder.add(value);
    }

    @Override
    public MetricData collect(AggregationTemporality temporality) {
        if (counters.isEmpty()) {
            return null;
        }

        long nowNano = System.currentTimeMillis() * 1_000_000L;
        List<NumberDataPoint> dataPoints = new ArrayList<>();

        for (Map.Entry<Attributes, LongAdder> entry : counters.entrySet()) {
            Attributes attrs = entry.getKey();
            long currentValue = entry.getValue().sum();

            long reportedValue;
            long startTime;

            if (temporality == AggregationTemporality.DELTA) {
                Long lastValue = lastExportedValues.get(attrs);
                long previousValue = lastValue != null ? lastValue : 0L;
                reportedValue = currentValue - previousValue;
                lastExportedValues.put(attrs, currentValue);
                startTime = nowNano;
            } else {
                reportedValue = currentValue;
                startTime = startTimeNano;
            }

            dataPoints.add(new NumberDataPoint(attrs, startTime, nowNano, reportedValue));
        }

        if (dataPoints.isEmpty()) {
            return null;
        }

        return MetricData.sum(name)
                .setDescription(description)
                .setUnit(unit)
                .setTemporality(temporality)
                .setMonotonic(false) // UpDownCounter is not monotonic
                .setNumberDataPoints(dataPoints)
                .build();
    }

    /**
     * Builder for LongUpDownCounter.
     */
    public static class Builder {

        private final String name;
        private String description = "";
        private String unit = "";

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

        public LongUpDownCounter build() {
            return new LongUpDownCounter(name, description, unit);
        }
    }

}

