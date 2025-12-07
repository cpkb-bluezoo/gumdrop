/*
 * Meter.java
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Factory for creating metric instruments.
 * A Meter is associated with an instrumentation scope (library name/version).
 *
 * <p>Example usage:
 * <pre>
 * Meter meter = telemetryConfig.getMeter("org.bluezoo.gumdrop.http");
 * 
 * LongCounter counter = meter.counterBuilder("http.requests")
 *     .setDescription("Total HTTP requests")
 *     .build();
 *     
 * DoubleHistogram histogram = meter.histogramBuilder("http.duration")
 *     .setDescription("Request duration")
 *     .setUnit("ms")
 *     .build();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Meter {

    private final String name;
    private final String version;
    private final String schemaUrl;

    // All instruments created by this meter
    private final List<Instrument> instruments;

    /**
     * Creates a new meter.
     *
     * @param name the instrumentation scope name
     */
    public Meter(String name) {
        this(name, null, null);
    }

    /**
     * Creates a new meter with version.
     *
     * @param name the instrumentation scope name
     * @param version the instrumentation scope version
     */
    public Meter(String name, String version) {
        this(name, version, null);
    }

    /**
     * Creates a new meter with version and schema URL.
     *
     * @param name the instrumentation scope name
     * @param version the instrumentation scope version
     * @param schemaUrl the schema URL for semantic conventions
     */
    public Meter(String name, String version, String schemaUrl) {
        this.name = name;
        this.version = version;
        this.schemaUrl = schemaUrl;
        this.instruments = new CopyOnWriteArrayList<>();
    }

    /**
     * Returns the instrumentation scope name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the instrumentation scope version.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the schema URL.
     */
    public String getSchemaUrl() {
        return schemaUrl;
    }

    /**
     * Creates a builder for a LongCounter.
     *
     * @param name the counter name
     * @return the builder
     */
    public LongCounter.Builder counterBuilder(String name) {
        return new LongCounter.Builder(name) {
            @Override
            public LongCounter build() {
                LongCounter counter = super.build();
                instruments.add(counter);
                return counter;
            }
        };
    }

    /**
     * Creates a builder for a LongUpDownCounter.
     *
     * @param name the counter name
     * @return the builder
     */
    public LongUpDownCounter.Builder upDownCounterBuilder(String name) {
        return new LongUpDownCounter.Builder(name) {
            @Override
            public LongUpDownCounter build() {
                LongUpDownCounter counter = super.build();
                instruments.add(counter);
                return counter;
            }
        };
    }

    /**
     * Creates a builder for a DoubleHistogram.
     *
     * @param name the histogram name
     * @return the builder
     */
    public DoubleHistogram.Builder histogramBuilder(String name) {
        return new DoubleHistogram.Builder(name) {
            @Override
            public DoubleHistogram build() {
                DoubleHistogram histogram = super.build();
                instruments.add(histogram);
                return histogram;
            }
        };
    }

    /**
     * Creates a builder for an ObservableGauge.
     *
     * @param name the gauge name
     * @return the builder
     */
    public ObservableGauge.Builder gaugeBuilder(String name) {
        return new ObservableGauge.Builder(name) {
            @Override
            public ObservableGauge buildWithCallback(ObservableCallback callback) {
                ObservableGauge gauge = super.buildWithCallback(callback);
                instruments.add(gauge);
                return gauge;
            }
        };
    }

    /**
     * Creates a builder for an ObservableCounter.
     *
     * @param name the counter name
     * @return the builder
     */
    public ObservableCounter.Builder observableCounterBuilder(String name) {
        return new ObservableCounter.Builder(name) {
            @Override
            public ObservableCounter buildWithCallback(ObservableCallback callback) {
                ObservableCounter counter = super.buildWithCallback(callback);
                instruments.add(counter);
                return counter;
            }
        };
    }

    /**
     * Creates a builder for an ObservableUpDownCounter.
     *
     * @param name the counter name
     * @return the builder
     */
    public ObservableUpDownCounter.Builder observableUpDownCounterBuilder(String name) {
        return new ObservableUpDownCounter.Builder(name) {
            @Override
            public ObservableUpDownCounter buildWithCallback(ObservableCallback callback) {
                ObservableUpDownCounter counter = super.buildWithCallback(callback);
                instruments.add(counter);
                return counter;
            }
        };
    }

    /**
     * Collects all metrics from this meter.
     *
     * @param temporality the aggregation temporality to use
     * @return list of metric data ready for export
     */
    public List<MetricData> collect(AggregationTemporality temporality) {
        List<MetricData> metrics = new ArrayList<>();
        for (Instrument instrument : instruments) {
            MetricData data = instrument.collect(temporality);
            if (data != null) {
                metrics.add(data);
            }
        }
        return metrics;
    }

    /**
     * Returns all instruments registered with this meter.
     */
    public List<Instrument> getInstruments() {
        return new ArrayList<>(instruments);
    }

}

