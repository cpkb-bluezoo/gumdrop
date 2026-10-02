/*
 * MeterConcurrencyIntegrationTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;
import org.junit.Test;

/**
 * Two real threads recording into one {@link Meter} instrument at the same
 * time: no increment or sample may be lost.
 *
 * <p>Integration test: the property under test is thread safety, which only
 * real threads can exercise. The totals are exact, so the outcome does not
 * depend on how the threads interleave. These two tests were
 * previously unit tests in {@code MeterTest} that asserted only that one
 * metric existed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MeterConcurrencyIntegrationTest {

    private static final long HANG_GUARD_SECONDS = 60L;

    /** Runs two tasks on separate threads released together, and joins them. */
    private static void runConcurrently(Runnable first, Runnable second) throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        Runnable[] tasks = new Runnable[] {first, second};
        for (int i = 0; i < tasks.length; i++) {
            final Runnable task = tasks[i];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue("both recorders must finish", done.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void testCounterConcurrentAdd() throws InterruptedException {
        Meter meter = new Meter("test.meter");
        final LongCounter counter = meter.counterBuilder("concurrent").build();
        Runnable adder = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000; i++) {
                    counter.add(1);
                }
            }
        };

        runConcurrently(adder, adder);

        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
        List<NumberDataPoint> points = metrics.get(0).getNumberDataPoints();
        assertEquals(1, points.size());
        assertEquals("every increment must be recorded", 2000L, points.get(0).getLongValue());
    }

    @Test
    public void testHistogramConcurrentRecord() throws InterruptedException {
        Meter meter = new Meter("test.meter");
        final DoubleHistogram histogram = meter.histogramBuilder("concurrent").build();
        Runnable ones = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    histogram.record(i * 1.0);
                }
            }
        };
        Runnable twos = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    histogram.record(i * 2.0);
                }
            }
        };

        runConcurrently(ones, twos);

        List<MetricData> metrics = meter.collect(AggregationTemporality.CUMULATIVE);
        assertEquals(1, metrics.size());
        List<HistogramDataPoint> points = metrics.get(0).getHistogramDataPoints();
        assertEquals(1, points.size());
        HistogramDataPoint point = points.get(0);
        assertEquals("every sample must be recorded", 200L, point.getCount());
        // sum of 0..99 plus twice the sum of 0..99
        assertEquals(14850.0, point.getSum(), 0.0001);
    }
}
