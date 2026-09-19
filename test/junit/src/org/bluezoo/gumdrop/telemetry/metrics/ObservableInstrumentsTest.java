/*
 * ObservableInstrumentsTest.java
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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the callback-driven instruments: {@link ObservableGauge},
 * {@link ObservableCounter} and {@link ObservableUpDownCounter}, and their
 * registration through {@link Meter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ObservableInstrumentsTest {

    /** Reports a mutable long value under empty attributes. */
    private static final class LongSource implements ObservableCallback {
        long value;

        @Override
        public void observe(ObservableMeasurement measurement) {
            measurement.record(value);
        }
    }

    private static final class FailingSource implements ObservableCallback {
        @Override
        public void observe(ObservableMeasurement measurement) {
            throw new IllegalStateException("callback failed");
        }
    }

    private static final class SilentSource implements ObservableCallback {
        @Override
        public void observe(ObservableMeasurement measurement) {
        }
    }

    private static final class MixedSource implements ObservableCallback {
        @Override
        public void observe(ObservableMeasurement measurement) {
            Attributes a = Attributes.of("k", "a");
            Attributes b = Attributes.of("k", "b");
            measurement.record(5L, a);
            measurement.record(2.5d, b);
            measurement.record(1.5d);
        }
    }

    @Test
    public void gaugeReportsCurrentValue() {
        LongSource source = new LongSource();
        source.value = 42L;
        ObservableGauge.Builder builder = new ObservableGauge.Builder("g");
        builder.setDescription("desc");
        builder.setUnit("By");
        ObservableGauge gauge = builder.buildWithCallback(source);

        assertEquals("g", gauge.getName());
        assertEquals("desc", gauge.getDescription());
        assertEquals("By", gauge.getUnit());

        MetricData data = gauge.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(MetricData.Type.GAUGE, data.getType());
        List<NumberDataPoint> points = data.getNumberDataPoints();
        assertEquals(1, points.size());
        assertEquals(42L, points.get(0).getLongValue());
    }

    @Test
    public void gaugeRecordsDoublesAndAttributes() {
        ObservableGauge gauge = new ObservableGauge.Builder("g").buildWithCallback(new MixedSource());
        MetricData data = gauge.collect(AggregationTemporality.DELTA);
        assertEquals(3, data.getNumberDataPoints().size());
    }

    @Test
    public void gaugeCallbackFailureYieldsNull() {
        ObservableGauge gauge = new ObservableGauge.Builder("g").buildWithCallback(new FailingSource());
        assertNull(gauge.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test
    public void gaugeWithNoMeasurementsYieldsNull() {
        ObservableGauge gauge = new ObservableGauge.Builder("g").buildWithCallback(new SilentSource());
        assertNull(gauge.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gaugeRejectsNullCallback() {
        new ObservableGauge.Builder("g").buildWithCallback(null);
    }

    @Test
    public void counterCumulativeReportsAbsoluteValue() {
        LongSource source = new LongSource();
        source.value = 10L;
        ObservableCounter counter = new ObservableCounter.Builder("c").buildWithCallback(source);
        MetricData first = counter.collect(AggregationTemporality.CUMULATIVE);
        assertTrue(first.isMonotonic());
        assertEquals(10L, first.getNumberDataPoints().get(0).getLongValue());
        source.value = 15L;
        MetricData second = counter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(15L, second.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void counterDeltaReportsDifferencesAndSkipsUnchanged() {
        LongSource source = new LongSource();
        source.value = 10L;
        ObservableCounter counter = new ObservableCounter.Builder("c").buildWithCallback(source);
        MetricData first = counter.collect(AggregationTemporality.DELTA);
        assertEquals(10L, first.getNumberDataPoints().get(0).getLongValue());
        source.value = 14L;
        MetricData second = counter.collect(AggregationTemporality.DELTA);
        assertEquals(4L, second.getNumberDataPoints().get(0).getLongValue());
        MetricData third = counter.collect(AggregationTemporality.DELTA);
        assertNull(third);
    }

    @Test
    public void counterDeltaHandlesDoubles() {
        ObservableCounter counter = new ObservableCounter.Builder("c").buildWithCallback(new MixedSource());
        MetricData first = counter.collect(AggregationTemporality.DELTA);
        assertEquals(3, first.getNumberDataPoints().size());
        assertNull(counter.collect(AggregationTemporality.DELTA));
        MetricData cumulative = counter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(3, cumulative.getNumberDataPoints().size());
    }

    @Test
    public void counterFailureOrEmptyYieldsNull() {
        ObservableCounter failing = new ObservableCounter.Builder("c").buildWithCallback(new FailingSource());
        assertNull(failing.collect(AggregationTemporality.CUMULATIVE));
        ObservableCounter silent = new ObservableCounter.Builder("c").buildWithCallback(new SilentSource());
        assertNull(silent.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void counterRejectsNullCallback() {
        new ObservableCounter.Builder("c").buildWithCallback(null);
    }

    @Test
    public void upDownCounterReportsDecreasesInDelta() {
        LongSource source = new LongSource();
        source.value = 10L;
        ObservableUpDownCounter counter = new ObservableUpDownCounter.Builder("u").buildWithCallback(source);
        assertEquals("u", counter.getName());
        MetricData first = counter.collect(AggregationTemporality.DELTA);
        assertFalse(first.isMonotonic());
        source.value = 4L;
        MetricData second = counter.collect(AggregationTemporality.DELTA);
        assertEquals(-6L, second.getNumberDataPoints().get(0).getLongValue());
        MetricData cumulative = counter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(4L, cumulative.getNumberDataPoints().get(0).getLongValue());
    }

    @Test
    public void upDownCounterDoublesFailureAndBuilderMetadata() {
        ObservableUpDownCounter.Builder builder = new ObservableUpDownCounter.Builder("u");
        builder.setDescription("d");
        builder.setUnit("1");
        ObservableUpDownCounter mixed = builder.buildWithCallback(new MixedSource());
        assertEquals("d", mixed.getDescription());
        assertEquals("1", mixed.getUnit());
        assertEquals(3, mixed.collect(AggregationTemporality.DELTA).getNumberDataPoints().size());
        assertEquals(3, mixed.collect(AggregationTemporality.CUMULATIVE).getNumberDataPoints().size());

        ObservableUpDownCounter failing =
            new ObservableUpDownCounter.Builder("u").buildWithCallback(new FailingSource());
        assertNull(failing.collect(AggregationTemporality.CUMULATIVE));
        ObservableUpDownCounter silent =
            new ObservableUpDownCounter.Builder("u").buildWithCallback(new SilentSource());
        assertNull(silent.collect(AggregationTemporality.CUMULATIVE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void upDownCounterRejectsNullCallback() {
        new ObservableUpDownCounter.Builder("u").buildWithCallback(null);
    }

    @Test
    public void meterRegistersAndCollectsObservables() {
        Meter meter = new Meter("test", "1.0", "http://schema");
        assertEquals("test", meter.getName());
        assertEquals("1.0", meter.getVersion());
        assertEquals("http://schema", meter.getSchemaUrl());

        LongSource source = new LongSource();
        source.value = 3L;
        meter.gaugeBuilder("g").buildWithCallback(source);
        meter.observableCounterBuilder("c").buildWithCallback(source);
        meter.observableUpDownCounterBuilder("u").buildWithCallback(source);
        assertEquals(3, meter.getInstruments().size());
        List<MetricData> collected = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(3, collected.size());
    }
}
