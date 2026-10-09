/*
 * BatchingExporterTest.java
 * Copyright (C) 2026 Chris Burdess
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BatchingExporterTest {

    private static final class Probe extends BatchingExporter {
        @Override
        public boolean accepts(LogLevel level) {
            return takesLevel(level);
        }

        @Override
        public boolean acceptsTraces() {
            return false;
        }

        @Override
        public void export(Trace trace) {
        }

        @Override
        public void export(LogRecord record) {
        }

        @Override
        public void export(List<MetricData> metrics) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void shutdown() {
        }
    }

    @Test
    public void defaults() {
        Probe p = new Probe();
        assertEquals(512, p.getBatchSize());
        assertEquals(5000L, p.getFlushIntervalMs());
        assertEquals(2048, p.getMaxQueueSize());
        assertEquals(60000L, p.getMetricsIntervalMs());
        assertEquals(AggregationTemporality.CUMULATIVE, p.getMetricsTemporality());
    }

    @Test
    public void settingsRoundTrip() {
        Probe p = new Probe();
        p.batchSize(10);
        p.flushIntervalMs(20L);
        p.maxQueueSize(30);
        p.metricsIntervalMs(1000L);
        p.metricsTemporality(AggregationTemporality.DELTA);
        assertEquals(10, p.getBatchSize());
        assertEquals(20L, p.getFlushIntervalMs());
        assertEquals(30, p.getMaxQueueSize());
        assertEquals(1000L, p.getMetricsIntervalMs());
        assertEquals(AggregationTemporality.DELTA, p.getMetricsTemporality());
    }

    @Test
    public void operationalLevelsAreTakenByDefault() {
        Probe p = new Probe();
        assertTrue(p.accepts(LogLevel.INFO));
        assertTrue(p.accepts(LogLevel.WARN));
        assertTrue(p.accepts(LogLevel.ERROR));
        assertFalse(p.accepts(LogLevel.ACCESS));
        assertFalse(p.accepts(LogLevel.QLOG));
    }

    @Test
    public void levelsAreReplacedNotAdded() {
        Probe p = new Probe();
        p.levels(LogLevel.ACCESS);
        assertTrue(p.accepts(LogLevel.ACCESS));
        assertFalse(p.accepts(LogLevel.INFO));
    }
}
