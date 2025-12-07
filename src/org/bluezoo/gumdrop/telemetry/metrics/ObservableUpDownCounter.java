/*
 * ObservableUpDownCounter.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An asynchronous up-down counter that reports values via callback.
 * Useful for externally maintained values that can increase or decrease.
 *
 * <p>Example usage:
 * <pre>
 * meter.observableUpDownCounterBuilder("process.threads")
 *     .setDescription("Number of threads in process")
 *     .buildWithCallback(new ObservableCallback() {
 *         public void observe(ObservableMeasurement measurement) {
 *             measurement.record(Thread.activeCount());
 *         }
 *     });
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ObservableUpDownCounter implements Instrument {

    private final String name;
    private final String description;
    private final String unit;
    private final ObservableCallback callback;
    private final long startTimeNano;

    private final Map<Attributes, Long> lastObservedLongValues;
    private final Map<Attributes, Double> lastObservedDoubleValues;

    ObservableUpDownCounter(String name, String description, String unit, ObservableCallback callback) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.callback = callback;
        this.startTimeNano = System.currentTimeMillis() * 1_000_000L;
        this.lastObservedLongValues = new HashMap<>();
        this.lastObservedDoubleValues = new HashMap<>();
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
        CollectorMeasurement collector = new CollectorMeasurement();
        try {
            callback.observe(collector);
        } catch (Exception e) {
            return null;
        }

        if (collector.longMeasurements.isEmpty() && collector.doubleMeasurements.isEmpty()) {
            return null;
        }

        long nowNano = System.currentTimeMillis() * 1_000_000L;
        List<NumberDataPoint> dataPoints = new ArrayList<>();

        // Process long measurements
        for (Map.Entry<Attributes, Long> entry : collector.longMeasurements.entrySet()) {
            Attributes attrs = entry.getKey();
            long currentValue = entry.getValue();

            long reportedValue;
            long startTime;

            if (temporality == AggregationTemporality.DELTA) {
                Long lastValue = lastObservedLongValues.get(attrs);
                long previousValue = lastValue != null ? lastValue : 0L;
                reportedValue = currentValue - previousValue;
                lastObservedLongValues.put(attrs, currentValue);
                startTime = nowNano;
            } else {
                reportedValue = currentValue;
                startTime = startTimeNano;
            }

            dataPoints.add(new NumberDataPoint(attrs, startTime, nowNano, reportedValue));
        }

        // Process double measurements
        for (Map.Entry<Attributes, Double> entry : collector.doubleMeasurements.entrySet()) {
            Attributes attrs = entry.getKey();
            double currentValue = entry.getValue();

            double reportedValue;
            long startTime;

            if (temporality == AggregationTemporality.DELTA) {
                Double lastValue = lastObservedDoubleValues.get(attrs);
                double previousValue = lastValue != null ? lastValue : 0.0;
                reportedValue = currentValue - previousValue;
                lastObservedDoubleValues.put(attrs, currentValue);
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
                .setMonotonic(false)
                .setNumberDataPoints(dataPoints)
                .build();
    }

    /**
     * Internal measurement collector.
     */
    private static final class CollectorMeasurement implements ObservableMeasurement {

        final Map<Attributes, Long> longMeasurements = new HashMap<>();
        final Map<Attributes, Double> doubleMeasurements = new HashMap<>();

        @Override
        public void record(long value) {
            record(value, Attributes.empty());
        }

        @Override
        public void record(long value, Attributes attributes) {
            longMeasurements.put(attributes, value);
        }

        @Override
        public void record(double value) {
            record(value, Attributes.empty());
        }

        @Override
        public void record(double value, Attributes attributes) {
            doubleMeasurements.put(attributes, value);
        }
    }

    /**
     * Builder for ObservableUpDownCounter.
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

        public ObservableUpDownCounter buildWithCallback(ObservableCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            return new ObservableUpDownCounter(name, description, unit, callback);
        }
    }

}

