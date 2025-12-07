/*
 * MeterTest.java
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

package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

import org.bluezoo.gumdrop.telemetry.metrics.*;

/**
 * Unit tests for {@link Meter} and metric instruments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MeterTest {

    // ========================================================================
    // Meter Construction Tests
    // ========================================================================

    @Test
    public void testMeterConstruction() {
        Meter meter = new Meter("test.meter");
        
        assertEquals("test.meter", meter.getName());
        assertNull(meter.getVersion());
        assertNull(meter.getSchemaUrl());
    }

    @Test
    public void testMeterConstructionWithVersion() {
        Meter meter = new Meter("test.meter", "1.0.0");
        
        assertEquals("test.meter", meter.getName());
        assertEquals("1.0.0", meter.getVersion());
        assertNull(meter.getSchemaUrl());
    }

    @Test
    public void testMeterConstructionFull() {
        Meter meter = new Meter("test.meter", "1.0.0", "https://schema.example.com");
        
        assertEquals("test.meter", meter.getName());
        assertEquals("1.0.0", meter.getVersion());
        assertEquals("https://schema.example.com", meter.getSchemaUrl());
    }

    @Test
    public void testMeterInstrumentsInitiallyEmpty() {
        Meter meter = new Meter("test.meter");
        
        assertTrue(meter.getInstruments().isEmpty());
    }

    // ========================================================================
    // LongCounter Tests
    // ========================================================================

    @Test
    public void testCounterBuilder() {
        Meter meter = new Meter("test.meter");
        
        LongCounter counter = meter.counterBuilder("requests.total")
                .setDescription("Total requests")
                .setUnit("1")
                .build();
        
        assertNotNull(counter);
        assertEquals("requests.total", counter.getName());
        assertEquals("Total requests", counter.getDescription());
        assertEquals("1", counter.getUnit());
    }

    @Test
    public void testCounterAdd() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(1);
        counter.add(5);
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
        
        MetricData data = metrics.get(0);
        assertEquals("requests", data.getName());
    }

    @Test
    public void testCounterWithAttributes() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(1, Attributes.of("method", "GET"));
        counter.add(2, Attributes.of("method", "POST"));
        counter.add(3, Attributes.of("method", "GET"));
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCounterNegativeValue() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(-1); // Should throw
    }

    @Test
    public void testCounterNullAttributes() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(1, null); // Should use empty attributes
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    @Test
    public void testCounterRegisteredWithMeter() {
        Meter meter = new Meter("test.meter");
        
        LongCounter counter = meter.counterBuilder("requests").build();
        
        List<Instrument> instruments = meter.getInstruments();
        assertEquals(1, instruments.size());
        assertSame(counter, instruments.get(0));
    }

    // ========================================================================
    // DoubleHistogram Tests
    // ========================================================================

    @Test
    public void testHistogramBuilder() {
        Meter meter = new Meter("test.meter");
        
        DoubleHistogram histogram = meter.histogramBuilder("latency")
                .setDescription("Request latency")
                .setUnit("ms")
                .build();
        
        assertNotNull(histogram);
        assertEquals("latency", histogram.getName());
        assertEquals("Request latency", histogram.getDescription());
        assertEquals("ms", histogram.getUnit());
    }

    @Test
    public void testHistogramRecord() {
        Meter meter = new Meter("test.meter");
        DoubleHistogram histogram = meter.histogramBuilder("latency").build();
        
        histogram.record(10.5);
        histogram.record(25.0);
        histogram.record(100.0);
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
        assertEquals("latency", metrics.get(0).getName());
    }

    @Test
    public void testHistogramWithAttributes() {
        Meter meter = new Meter("test.meter");
        DoubleHistogram histogram = meter.histogramBuilder("latency").build();
        
        histogram.record(10.0, Attributes.of("endpoint", "/api/v1"));
        histogram.record(20.0, Attributes.of("endpoint", "/api/v2"));
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    @Test
    public void testHistogramCustomBuckets() {
        Meter meter = new Meter("test.meter");
        DoubleHistogram histogram = meter.histogramBuilder("size")
                .setExplicitBuckets(100, 500, 1000, 5000)
                .build();
        
        histogram.record(50);    // Bucket 0 (< 100)
        histogram.record(250);   // Bucket 1 (100-500)
        histogram.record(750);   // Bucket 2 (500-1000)
        histogram.record(2000);  // Bucket 3 (1000-5000)
        histogram.record(10000); // Bucket 4 (>= 5000)
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    @Test
    public void testHistogramNullAttributes() {
        Meter meter = new Meter("test.meter");
        DoubleHistogram histogram = meter.histogramBuilder("latency").build();
        
        histogram.record(10.0, null); // Should use empty attributes
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    // ========================================================================
    // LongUpDownCounter Tests
    // ========================================================================

    @Test
    public void testUpDownCounterBuilder() {
        Meter meter = new Meter("test.meter");
        
        LongUpDownCounter counter = meter.upDownCounterBuilder("active.connections")
                .setDescription("Active connections")
                .setUnit("1")
                .build();
        
        assertNotNull(counter);
        assertEquals("active.connections", counter.getName());
    }

    @Test
    public void testUpDownCounterAddAndSubtract() {
        Meter meter = new Meter("test.meter");
        LongUpDownCounter counter = meter.upDownCounterBuilder("queue.size").build();
        
        counter.add(5);
        counter.add(-2);
        counter.add(3);
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    // ========================================================================
    // Attributes Tests
    // ========================================================================

    @Test
    public void testAttributesEmpty() {
        Attributes empty = Attributes.empty();
        
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
    }

    @Test
    public void testAttributesOf() {
        Attributes attrs = Attributes.of(
                "method", "GET",
                "status", 200
        );
        
        assertFalse(attrs.isEmpty());
        assertEquals(2, attrs.size());
    }

    @Test
    public void testAttributesOfVariousTypes() {
        Attributes attrs = Attributes.of(
                "string", "value",
                "long", 42L,
                "int", 123,
                "double", 3.14,
                "float", 2.5f,
                "bool", true
        );
        
        assertEquals(6, attrs.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttributesOddLength() {
        Attributes.of("key1", "value1", "key2"); // Odd length - should throw
    }

    @Test
    public void testAttributesEquality() {
        Attributes attrs1 = Attributes.of("a", "1", "b", "2");
        Attributes attrs2 = Attributes.of("b", "2", "a", "1"); // Different order
        
        assertEquals(attrs1, attrs2);
        assertEquals(attrs1.hashCode(), attrs2.hashCode());
    }

    @Test
    public void testAttributesToString() {
        Attributes attrs = Attributes.of("method", "GET");
        String str = attrs.toString();
        
        assertTrue(str.contains("method"));
        assertTrue(str.contains("GET"));
    }

    @Test
    public void testAttributesEmptyToString() {
        assertEquals("{}", Attributes.empty().toString());
    }

    // ========================================================================
    // Collection Tests
    // ========================================================================

    @Test
    public void testCollectEmpty() {
        Meter meter = new Meter("test.meter");
        
        // Create counter but don't record anything
        meter.counterBuilder("empty").build();
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertTrue(metrics.isEmpty());
    }

    @Test
    public void testCollectMultipleInstruments() {
        Meter meter = new Meter("test.meter");
        
        LongCounter counter = meter.counterBuilder("requests").build();
        DoubleHistogram histogram = meter.histogramBuilder("latency").build();
        
        counter.add(10);
        histogram.record(50.0);
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(2, metrics.size());
    }

    @Test
    public void testCollectDeltaTemporality() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(10);
        
        // First collection
        List<MetricData> metrics1 = meter.collect(AggregationTemporality.DELTA);
        assertEquals(1, metrics1.size());
        
        // Add more
        counter.add(5);
        
        // Second collection should only show delta
        List<MetricData> metrics2 = meter.collect(AggregationTemporality.DELTA);
        assertEquals(1, metrics2.size());
    }

    @Test
    public void testCollectCumulativeTemporality() {
        Meter meter = new Meter("test.meter");
        LongCounter counter = meter.counterBuilder("requests").build();
        
        counter.add(10);
        
        List<MetricData> metrics1 = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics1.size());
        
        counter.add(5);
        
        // Cumulative should show total
        List<MetricData> metrics2 = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics2.size());
    }

    // ========================================================================
    // Instrument Registration Tests
    // ========================================================================

    @Test
    public void testMultipleInstrumentsRegistered() {
        Meter meter = new Meter("test.meter");
        
        meter.counterBuilder("counter1").build();
        meter.counterBuilder("counter2").build();
        meter.histogramBuilder("histogram1").build();
        
        List<Instrument> instruments = meter.getInstruments();
        assertEquals(3, instruments.size());
    }

    @Test
    public void testInstrumentsListDefensiveCopy() {
        Meter meter = new Meter("test.meter");
        meter.counterBuilder("counter").build();
        
        List<Instrument> instruments1 = meter.getInstruments();
        List<Instrument> instruments2 = meter.getInstruments();
        
        assertNotSame(instruments1, instruments2);
    }

    // ========================================================================
    // Thread Safety Tests (basic)
    // ========================================================================

    @Test
    public void testCounterConcurrentAdd() throws InterruptedException {
        final Meter meter = new Meter("test.meter");
        final LongCounter counter = meter.counterBuilder("concurrent").build();
        
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000; i++) {
                    counter.add(1);
                }
            }
        });
        
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000; i++) {
                    counter.add(1);
                }
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        // Should have recorded all increments
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

    @Test
    public void testHistogramConcurrentRecord() throws InterruptedException {
        final Meter meter = new Meter("test.meter");
        final DoubleHistogram histogram = meter.histogramBuilder("concurrent").build();
        
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    histogram.record(i * 1.0);
                }
            }
        });
        
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    histogram.record(i * 2.0);
                }
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
    }

}

