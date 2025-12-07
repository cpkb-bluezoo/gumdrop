/*
 * ObservableGauge.java
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

/**
 * An asynchronous gauge that reports point-in-time values via callback.
 * The callback is invoked at collection time to capture current state.
 *
 * <p>Example usage:
 * <pre>
 * meter.gaugeBuilder("system.memory.used")
 *     .setDescription("Used memory in bytes")
 *     .setUnit("bytes")
 *     .buildWithCallback(new ObservableCallback() {
 *         public void observe(ObservableMeasurement measurement) {
 *             Runtime rt = Runtime.getRuntime();
 *             measurement.record(rt.totalMemory() - rt.freeMemory());
 *         }
 *     });
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ObservableGauge implements Instrument {

    private final String name;
    private final String description;
    private final String unit;
    private final ObservableCallback callback;

    ObservableGauge(String name, String description, String unit, ObservableCallback callback) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.callback = callback;
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

    @Override
    public MetricData collect(AggregationTemporality temporality) {
        // Invoke callback to get current values
        CollectorMeasurement collector = new CollectorMeasurement();
        try {
            callback.observe(collector);
        } catch (Exception e) {
            // Log and continue - don't let callback errors break collection
            return null;
        }

        if (collector.dataPoints.isEmpty()) {
            return null;
        }

        return MetricData.gauge(name)
                .setDescription(description)
                .setUnit(unit)
                .setNumberDataPoints(collector.dataPoints)
                .build();
    }

    /**
     * Internal measurement collector.
     */
    private static final class CollectorMeasurement implements ObservableMeasurement {

        final List<NumberDataPoint> dataPoints = new ArrayList<>();
        final long timeNano = System.currentTimeMillis() * 1_000_000L;

        @Override
        public void record(long value) {
            record(value, Attributes.empty());
        }

        @Override
        public void record(long value, Attributes attributes) {
            dataPoints.add(new NumberDataPoint(attributes, timeNano, timeNano, value));
        }

        @Override
        public void record(double value) {
            record(value, Attributes.empty());
        }

        @Override
        public void record(double value, Attributes attributes) {
            dataPoints.add(new NumberDataPoint(attributes, timeNano, timeNano, value));
        }
    }

    /**
     * Builder for ObservableGauge.
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

        /**
         * Builds the gauge with the provided callback.
         * The callback will be invoked at collection time.
         */
        public ObservableGauge buildWithCallback(ObservableCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            return new ObservableGauge(name, description, unit, callback);
        }
    }

}

