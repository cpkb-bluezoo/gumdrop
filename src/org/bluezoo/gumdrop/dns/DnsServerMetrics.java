/*
 * DnsServerMetrics.java
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
 *   <li>{@code dns.server.cache.stale_served} - RFC 8767 stale answers served</li>
 *   <li>{@code dns.server.cache.aggressive_nsec} - RFC 8198 NSEC-synthesized answers</li>
 *   <li>{@code dns.server.upstream.queries} - Queries forwarded upstream</li>
 *   <li>{@code dns.server.upstream.duration} - Upstream query duration</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.dns";

    private final LongCounter queryCounter;
    private final LongCounter responseCounter;
    private final DoubleHistogram queryDuration;

    private final LongCounter cacheHits;
    private final LongCounter cacheMisses;
    private final LongCounter cacheStaleServed;
    private final LongCounter cacheAggressiveNsecServed;

    private final LongCounter upstreamQueries;
    private final DoubleHistogram upstreamDuration;
    private final LongCounter upstreamFailures;
    private final org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter upstreamInFlight;
    private final LongCounter upstreamIdExhaustions;

    /**
     * Creates DNS server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public DnsServerMetrics(TelemetryConfig config) {
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

        this.cacheStaleServed = meter.counterBuilder("dns.server.cache.stale_served")
                .setDescription("DNS answers served stale after upstream failure")
                .setUnit("responses")
                .build();

        this.cacheAggressiveNsecServed = meter.counterBuilder(
                        "dns.server.cache.aggressive_nsec")
                .setDescription("DNS answers synthesized from validated NSEC cache")
                .setUnit("responses")
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

        this.upstreamInFlight = meter.upDownCounterBuilder("dns.server.upstream.inflight")
                .setDescription("DNS queries in flight to upstream servers")
                .setUnit("queries")
                .build();

        this.upstreamIdExhaustions = meter.counterBuilder("dns.server.upstream.id_exhaustions")
                .setDescription("Upstream DNS queries held back because every query ID "
                        + "on the connection was in use")
                .setUnit("queries")
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
     * Records an RFC 8767 serve-stale response.
     */
    public void cacheStaleServed() {
        cacheStaleServed.add(1);
    }

    /**
     * Records an RFC 8198 aggressively cached NSEC/NSEC3 synthesis.
     */
    public void cacheAggressiveNsecServed() {
        cacheAggressiveNsecServed.add(1);
    }

    /**
     * Records an upstream query.
     *
     * @param upstream the upstream server's address, as {@code host:port}
     * @param transport the transport to it ("udp", "dot" or "doq")
     * @param durationMs the upstream query duration in milliseconds
     */
    public void upstreamQuery(String upstream, String transport, double durationMs) {
        Attributes attrs = Attributes.of(
                "dns.upstream.address", upstream,
                "dns.transport", transport);
        upstreamQueries.add(1, attrs);
        upstreamDuration.record(durationMs, attrs);
    }

    /**
     * Records a failed upstream query.
     *
     * @param upstream the upstream server's address, as {@code host:port}
     * @param transport the transport to it ("udp", "dot" or "doq")
     */
    public void upstreamFailure(String upstream, String transport) {
        upstreamFailures.add(1, Attributes.of(
                "dns.upstream.address", upstream,
                "dns.transport", transport));
    }

    /**
     * Records queries entering or leaving flight to an upstream server over
     * a connection: those sent and not yet answered, timed out or lost.
     * Summed over all the connections to the upstream.
     *
     * @param upstream the upstream server's address, as {@code host:port}
     * @param transport the transport to it ("dot" or "doq")
     * @param delta how many entered flight, or minus how many left it
     */
    public void upstreamInFlight(String upstream, String transport, long delta) {
        upstreamInFlight.add(delta, Attributes.of(
                "dns.upstream.address", upstream,
                "dns.transport", transport));
    }

    /**
     * Records a query that had to wait because every query ID on the
     * connection it was bound for was in use.
     *
     * @param upstream the upstream server's address, as {@code host:port}
     * @param transport the transport to it ("dot" or "doq")
     */
    public void upstreamIdExhausted(String upstream, String transport) {
        upstreamIdExhaustions.add(1, Attributes.of(
                "dns.upstream.address", upstream,
                "dns.transport", transport));
    }
}
