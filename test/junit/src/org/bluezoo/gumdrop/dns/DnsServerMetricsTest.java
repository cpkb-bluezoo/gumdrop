/*
 * DnsServerMetricsTest.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.dns;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnsServerMetrics}: each event lands in the expected
 * instrument of the shared DNS meter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerMetricsTest {

    private static Set<String> collectedNames(TelemetryConfig config) {
        Set<String> names = new HashSet<String>();
        for (Meter meter : config.getMeters().values()) {
            List<MetricData> data = meter.collect(AggregationTemporality.CUMULATIVE);
            for (MetricData d : data) {
                names.add(d.getName());
            }
        }
        return names;
    }

    @Test
    public void noMetricsUntilEventsRecorded() {
        TelemetryConfig config = new TelemetryConfig();
        new DnsServerMetrics(config);
        assertTrue(collectedNames(config).isEmpty());
    }

    @Test
    public void eachEventPopulatesItsInstrument() {
        TelemetryConfig config = new TelemetryConfig();
        DnsServerMetrics metrics = new DnsServerMetrics(config);
        metrics.queryReceived("A", "udp");
        metrics.responseSent("NOERROR", 1.5d, "udp");
        metrics.cacheHit();
        metrics.cacheMiss();
        metrics.cacheStaleServed();
        metrics.cacheAggressiveNsecServed();
        metrics.upstreamQuery(12.0d);
        metrics.upstreamFailure();

        Set<String> names = collectedNames(config);
        assertTrue(names.contains("dns.server.queries"));
        assertTrue(names.contains("dns.server.responses"));
        assertTrue(names.contains("dns.server.query.duration"));
        assertTrue(names.contains("dns.server.cache.hits"));
        assertTrue(names.contains("dns.server.cache.misses"));
        assertTrue(names.contains("dns.server.cache.stale_served"));
        assertTrue(names.contains("dns.server.cache.aggressive_nsec"));
        assertTrue(names.contains("dns.server.upstream.queries"));
        assertTrue(names.contains("dns.server.upstream.duration"));
        assertTrue(names.contains("dns.server.upstream.failures"));
    }
}
