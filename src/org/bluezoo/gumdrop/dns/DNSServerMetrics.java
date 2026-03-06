/*
 * DNSServerMetrics.java
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

package org.bluezoo.gumdrop.dns;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for DNS servers.
 *
 * <p>This class provides standardized DNS server metrics for monitoring
 * name resolution operations.
 *
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code dns.server.queries} - Total queries received (by type)</li>
 *   <li>{@code dns.server.responses} - Responses sent (by rcode)</li>
 *   <li>{@code dns.server.query.duration} - Query processing duration</li>
 *   <li>{@code dns.server.cache.hits} - Cache hits</li>
 *   <li>{@code dns.server.cache.misses} - Cache misses</li>
 *   <li>{@code dns.server.upstream.queries} - Queries forwarded upstream</li>
 *   <li>{@code dns.server.upstream.duration} - Upstream query duration</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.dns";

    private final LongCounter queryCounter;
    private final LongCounter responseCounter;
    private final DoubleHistogram queryDuration;

    private final LongCounter cacheHits;
    private final LongCounter cacheMisses;

    private final LongCounter upstreamQueries;
    private final DoubleHistogram upstreamDuration;
    private final LongCounter upstreamFailures;

    /**
     * Creates DNS server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public DNSServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        this.queryCounter = meter.counterBuilder("dns.server.queries")
                .setDescription("Total DNS queries received")
                .setUnit("queries")
                .build();

        this.responseCounter = meter.counterBuilder("dns.server.responses")
                .setDescription("Total DNS responses sent")
                .setUnit("responses")
                .build();

        this.queryDuration = meter.histogramBuilder("dns.server.query.duration")
                .setDescription("DNS query processing duration")
                .setUnit("ms")
                .setExplicitBuckets(0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000)
                .build();

        this.cacheHits = meter.counterBuilder("dns.server.cache.hits")
                .setDescription("DNS cache hits")
                .setUnit("hits")
                .build();

        this.cacheMisses = meter.counterBuilder("dns.server.cache.misses")
                .setDescription("DNS cache misses")
                .setUnit("misses")
                .build();

        this.upstreamQueries = meter.counterBuilder("dns.server.upstream.queries")
                .setDescription("DNS queries forwarded to upstream servers")
                .setUnit("queries")
                .build();

        this.upstreamDuration = meter.histogramBuilder("dns.server.upstream.duration")
                .setDescription("Upstream DNS query duration")
                .setUnit("ms")
                .setExplicitBuckets(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2000, 5000)
                .build();

        this.upstreamFailures = meter.counterBuilder("dns.server.upstream.failures")
                .setDescription("Failed upstream DNS queries")
                .setUnit("failures")
                .build();
    }

    /**
     * Records a DNS query received.
     *
     * @param queryType the query type name (e.g. "A", "AAAA", "MX")
     * @param transport the transport protocol (e.g. "udp", "dot", "doq")
     */
    public void queryReceived(String queryType, String transport) {
        queryCounter.add(1, Attributes.of(
                "dns.question.type", queryType,
                "dns.transport", transport));
    }

    /**
     * Records a DNS response sent.
     *
     * @param rcode the response code name (e.g. "NOERROR", "NXDOMAIN", "SERVFAIL")
     * @param durationMs the query processing duration in milliseconds
     * @param transport the transport protocol (e.g. "udp", "dot", "doq")
     */
    public void responseSent(String rcode, double durationMs,
                             String transport) {
        Attributes attrs = Attributes.of(
                "dns.response.code", rcode,
                "dns.transport", transport);
        responseCounter.add(1, attrs);
        queryDuration.record(durationMs, attrs);
    }

    /**
     * Records a cache hit.
     */
    public void cacheHit() {
        cacheHits.add(1);
    }

    /**
     * Records a cache miss.
     */
    public void cacheMiss() {
        cacheMisses.add(1);
    }

    /**
     * Records an upstream query.
     *
     * @param durationMs the upstream query duration in milliseconds
     */
    public void upstreamQuery(double durationMs) {
        upstreamQueries.add(1);
        upstreamDuration.record(durationMs);
    }

    /**
     * Records a failed upstream query.
     */
    public void upstreamFailure() {
        upstreamFailures.add(1);
    }
}
