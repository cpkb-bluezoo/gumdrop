/*
 * DNSService.java
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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import org.bluezoo.gumdrop.dns.DNSQueryIdGenerator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UDPTransportFactory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * A DNS application service that resolves queries locally or proxies to
 * upstream servers.
 * RFC 1035 section 6: name server implementation. This service operates
 * as a caching forwarder (RFC 1035 section 7) rather than an authoritative
 * server. It validates incoming queries (section 4.1.1) and returns
 * appropriate error codes (FORMERR, NOTIMP, SERVFAIL, NXDOMAIN).
 *
 * <p>The default implementation proxies all queries to configured upstream
 * DNS servers. Subclasses can override {@link #resolve(DNSMessage)} to
 * provide custom name resolution.
 *
 * <p>Upstream forwarding is fully asynchronous: it runs on
 * {@link org.bluezoo.gumdrop.UDPEndpoint}/{@link
 * org.bluezoo.gumdrop.TCPEndpoint} client-mode connections registered
 * with a {@link SelectorLoop}, the same core transport primitives
 * {@code UDPListener}/{@code TCPListener} themselves are built on --
 * never a blocking socket, and never the {@code dns.client} resolver
 * stack (that's a stub-resolver abstraction for applications, not for
 * a server's own internal forwarding).
 *
 * <p>The service manages one or more listeners. Currently supported:
 * <ul>
 *   <li>{@link DNSListener} &ndash; standard DNS over UDP</li>
 *   <li>{@link DoTListener} &ndash; DNS over TLS (stub)</li>
 *   <li>{@link DoQListener} &ndash; DNS over QUIC (stub)</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 * <li>Configurable upstream DNS servers</li>
 * <li>Optional system resolver fallback</li>
 * <li>In-memory response caching with TTL support</li>
 * <li>Negative caching for NXDOMAIN responses</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.dns.DNSService">
 *   <property name="upstream-servers">8.8.8.8 1.1.1.1</property>
 *   <property name="cache-enabled">true</property>
 *   <listener class="org.bluezoo.gumdrop.dns.DNSListener"
 *           port="5353"/>
 * </service>
 * }</pre>
 *
 * <p>Example subclass for custom resolution:
 * <pre>{@code
 * public class MyDNSService extends DNSService {
 *     @Override
 *     protected DNSMessage resolve(DNSMessage query) {
 *         DNSQuestion question = query.getQuestions().get(0);
 *         if ("internal.example.com".equals(question.getName())) {
 *             List answers = new ArrayList();
 *             answers.add(DNSResourceRecord.a("internal.example.com", 300,
 *                     InetAddress.getByName("10.0.0.1")));
 *             return query.createResponse(answers);
 *         }
 *         return null; // Fall through to upstream
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see DNSListener
 */
public class DNSService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(DNSService.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long UPSTREAM_TIMEOUT_MS = 5000;
    // RFC 6891 section 6.2.5: with EDNS0, the UDP payload size is
    // negotiated via the OPT record. We use 4096 as the default.
    private static final int MAX_DNS_MESSAGE_SIZE =
            DNSMessage.DEFAULT_EDNS_UDP_SIZE;
    // RFC 1035 section 4.2.2: 2-byte big-endian length prefix
    private static final int TCP_LENGTH_PREFIX_SIZE = 2;
    private static final int MAX_TCP_MESSAGE_SIZE = 65535;

    private final List<Listener> listeners = new ArrayList<Listener>();

    // ── Configuration ──

    private final List<InetSocketAddress> upstreamServers = new ArrayList<InetSocketAddress>();
    private boolean useSystemResolvers = true;
    private boolean cacheEnabled = true;
    private boolean dnssecEnabled;
    private int maxMQTypes = DNSMultiQType.DEFAULT_MAX_MQTYPES;
    private DNSCache cache;
    private DNSServerMetrics metrics;

    private final DNSCookie dnsCookie = new DNSCookie();

    // Reused across every upstream attempt for this service's lifetime,
    // rather than constructed per-query.
    private UDPTransportFactory upstreamUdpFactory;
    private TCPTransportFactory upstreamTcpFactory;

    /**
     * Creates a new DNS service.
     */
    public DNSService() {
    }

    // ── Listener management ──

    /**
     * Adds a UDP DNS listener.
     *
     * @param endpoint the DNS server endpoint
     */
    public void addListener(DNSListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a DNS-over-TLS listener (stub).
     *
     * @param endpoint the DoT server endpoint
     */
    public void addListener(DoTListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a DNS-over-QUIC listener (stub).
     *
     * @param endpoint the DoQ server endpoint
     */
    public void addListener(DoQListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item must be
     * a {@link DNSListener}, {@link DoTListener}, or
     * {@link DoQListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof DNSListener) {
                addListener((DNSListener) item);
            } else if (item instanceof DoTListener) {
                addListener((DoTListener) item);
            } else if (item instanceof DoQListener) {
                addListener((DoQListener) item);
            }
        }
    }

    @Override
    public List<Listener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Configuration ──

    /**
     * Sets the upstream DNS servers to use for proxying.
     *
     * <p>Format: space-separated list of addresses with optional port.
     * Examples:
     * <ul>
     * <li>"8.8.8.8 1.1.1.1" &ndash; Google and Cloudflare DNS</li>
     * <li>"192.168.1.1:53" &ndash; Local router with explicit port</li>
     * </ul>
     *
     * @param servers space-separated list of server addresses
     */
    public void setUpstreamServers(String servers) {
        upstreamServers.clear();
        if (servers == null || servers.trim().isEmpty()) {
            return;
        }

        StringTokenizer st = new StringTokenizer(servers);
        while (st.hasMoreTokens()) {
            String server = st.nextToken();
            try {
                InetSocketAddress addr =
                        parseAddress(server, DEFAULT_PORT);
                upstreamServers.add(addr);
            } catch (Exception e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.invalid_upstream_server"),
                        server);
                LOGGER.log(Level.WARNING, msg, e);
            }
        }
    }

    /**
     * Sets whether to use system resolvers from /etc/resolv.conf.
     *
     * @param useSystemResolvers true to use system resolvers as fallback
     */
    public void setUseSystemResolvers(boolean useSystemResolvers) {
        this.useSystemResolvers = useSystemResolvers;
    }

    /**
     * Sets whether response caching is enabled.
     *
     * @param cacheEnabled true to enable caching
     */
    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * Enables DNSSEC-aware upstream proxying.
     * RFC 4035 section 3.2.1: when enabled, the DO bit is set in
     * upstream queries so that DNSSEC records are returned. The AD
     * bit on upstream responses is preserved when the upstream
     * validated the answer. DNSSEC records are stripped from
     * responses to clients that did not set DO.
     *
     * @param dnssecEnabled true to enable DNSSEC-aware proxying
     */
    public void setDnssecEnabled(boolean dnssecEnabled) {
        this.dnssecEnabled = dnssecEnabled;
    }

    /**
     * Returns true if DNSSEC-aware proxying is enabled.
     *
     * @return true if DNSSEC is enabled
     */
    public boolean isDnssecEnabled() {
        return dnssecEnabled;
    }

    /**
     * Sets the maximum number of additional RRTYPEs this server will
     * merge into one response via RFC 10029 (DNS Multiple QTYPEs). A
     * client's {@code MQTYPE-Query} option requesting more than this
     * many types is rejected with FORMERR.
     *
     * @param maxMQTypes the cap; defaults to
     *                   {@link DNSMultiQType#DEFAULT_MAX_MQTYPES}
     */
    public void setMaxMQTypes(int maxMQTypes) {
        this.maxMQTypes = maxMQTypes;
    }

    /**
     * Returns the DNS cache.
     *
     * @return the cache, or null if caching is disabled
     */
    public DNSCache getCache() {
        return cache;
    }

    /**
     * Returns the DNS server metrics, or null if metrics are disabled.
     *
     * @return the metrics instance
     */
    public DNSServerMetrics getMetrics() {
        return metrics;
    }

    // ── Lifecycle ──

    /**
     * Called during {@link #start()} before listeners are wired and
     * started. Subclasses can override to perform custom initialisation.
     */
    protected void initService() {
    }

    /**
     * Called during {@link #stop()} after listeners are stopped.
     * Subclasses can override to release resources.
     */
    protected void destroyService() {
    }

    @Override
    public void start() {
        initService();

        if (cacheEnabled) {
            cache = new DNSCache();
        }

        upstreamUdpFactory = new UDPTransportFactory();
        upstreamUdpFactory.start();
        upstreamTcpFactory = new TCPTransportFactory();
        upstreamTcpFactory.start();

        if (useSystemResolvers && upstreamServers.isEmpty()) {
            loadSystemResolvers();
        }

        if (upstreamServers.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.no_upstream_servers"));
        } else if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("info.upstream_servers");
            message = MessageFormat.format(message, upstreamServers);
            LOGGER.fine(message);
        }

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener);
            startListener(listener);
        }

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof Listener) {
                Listener l = (Listener) listener;
                TelemetryConfig tc = l.getTelemetryConfig();
                if (tc != null && tc.isMetricsEnabled()) {
                    metrics = new DNSServerMetrics(tc);
                    break;
                }
            }
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        if (cache != null) {
            cache.clear();
        }
        destroyService();
    }

    // ── Query handling ──

    /**
     * Handles a DNS datagram received from a UDP listener.
     *
     * <p>This is called by {@link DNSListener} when a datagram
     * arrives. The method parses the DNS message, processes it, and
     * sends the response back to the source. Resolution may involve
     * an asynchronous upstream round trip, so {@code onComplete} is
     * invoked exactly once, once a response has been sent (or the
     * query could not be processed at all), so the caller can release
     * any per-datagram accounting (e.g. connection/rate-limit tracking)
     * at the right time rather than immediately after this method
     * returns.
     *
     * @param origin  the endpoint that received the datagram
     * @param data    the raw datagram data
     * @param source  the sender's address
     * @param onComplete called exactly once when handling has finished
     */
    void handleDatagram(final DNSListener origin,
                        ByteBuffer data,
                        final InetSocketAddress source,
                        final Runnable onComplete) {
        try {
            final DNSMessage query = DNSMessage.parse(data);

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.received_query");
                message = MessageFormat.format(message, query, source);
                LOGGER.fine(message);
            }

            if (metrics != null && !query.getQuestions().isEmpty()) {
                DNSQuestion q = query.getQuestions().get(0);
                metrics.queryReceived(q.getType().name(), "udp");
            }

            // RFC 1035 section 4.1.1: only OPCODE_QUERY (standard query) is supported
            if (!query.isQuery()
                    || query.getOpcode() != DNSMessage.OPCODE_QUERY) {
                DNSMessage error = query.createErrorResponse(
                        DNSMessage.RCODE_NOTIMP);
                sendResponse(origin, error, source);
                onComplete.run();
                return;
            }

            // RFC 1035 section 4.1.2: question section must not be empty
            if (query.getQuestions().isEmpty()) {
                DNSMessage error = query.createErrorResponse(
                        DNSMessage.RCODE_FORMERR);
                sendResponse(origin, error, source);
                onComplete.run();
                return;
            }

            // RFC 7873: when a client cookie is present but the server
            // cookie is missing or invalid, return a cookie-only response
            // instead of resolving the query (anti-amplification).
            final byte[] requestCookie = findRequestCookie(query);
            if (requestCookie != null) {
                if (requestCookie.length < DNSCookie.CLIENT_COOKIE_LENGTH) {
                    sendResponse(origin, query.createErrorResponse(
                            DNSMessage.RCODE_FORMERR), source);
                    onComplete.run();
                    return;
                }
                byte[] clientCookie = Arrays.copyOf(requestCookie,
                        DNSCookie.CLIENT_COOKIE_LENGTH);
                if (requestCookie.length == DNSCookie.CLIENT_COOKIE_LENGTH
                        || !dnsCookie.validateServerCookie(
                                source.getAddress().getAddress(),
                                clientCookie,
                                Arrays.copyOfRange(requestCookie,
                                        DNSCookie.CLIENT_COOKIE_LENGTH,
                                        requestCookie.length))) {
                    sendResponse(origin, createCookieOnlyResponse(query,
                            source, clientCookie), source);
                    onComplete.run();
                    return;
                }
            }

            final long startNanos = System.nanoTime();
            SelectorLoop loop = origin.getSelectorLoop();
            processQuery(query, loop, new DNSQueryCallback() {
                @Override
                public void onResponse(DNSMessage response) {
                    // RFC 4035 section 3.2.1: strip DNSSEC records from
                    // responses when the client did not set DO.
                    DNSMessage finalResponse = response;
                    if (dnssecEnabled && !query.hasDO()) {
                        finalResponse = stripDNSSECRecords(finalResponse);
                    }

                    if (requestCookie != null) {
                        byte[] clientCookie = Arrays.copyOf(requestCookie,
                                DNSCookie.CLIENT_COOKIE_LENGTH);
                        finalResponse = attachResponseCookie(finalResponse,
                                query, source, clientCookie);
                    }

                    if (metrics != null) {
                        double durationMs =
                                (System.nanoTime() - startNanos) / 1_000_000.0;
                        metrics.responseSent(
                                rcodeToString(finalResponse.getRcode()),
                                durationMs, "udp");
                    }
                    sendResponse(origin, finalResponse, source);
                    onComplete.run();
                }

                @Override
                public void onError(String error) {
                    // processQuery's own pipeline never calls onError --
                    // an upstream failure is delivered as a SERVFAIL
                    // response, not an error -- but handle it defensively
                    // in case a resolve() override's async delegate does.
                    sendResponse(origin, query.createErrorResponse(
                            DNSMessage.RCODE_SERVFAIL), source);
                    onComplete.run();
                }
            });

        } catch (DNSFormatException e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.malformed_query"), source);
            LOGGER.log(Level.FINE, msg, e);
            onComplete.run();
        } catch (Exception e) {
            String msg = MessageFormat.format(
                    L10N.getString("err.query"), source);
            LOGGER.log(Level.WARNING, msg, e);
            onComplete.run();
        }
    }

    /**
     * Processes a DNS query through the resolution pipeline:
     * cache, custom resolve, upstream proxy.
     * RFC 1035 section 7.1-7.4: stub/caching resolver algorithm.
     * Pipeline: (1) cache lookup (section 7.4), (2) custom resolution,
     * (3) upstream forwarding (section 7.2), fully asynchronously --
     * upstream forwarding involves real network I/O, so {@code callback}
     * may be invoked immediately (cache hit, {@link #resolve} hit) or
     * only once an upstream round trip completes.
     *
     * <p>This method is public so that other listeners (DoT, DoQ)
     * can delegate to it.
     *
     * @param query the parsed DNS query message
     * @param loop the SelectorLoop to run any upstream client
     *             connections on -- normally the same loop the query
     *             arrived on; if null, a worker loop is obtained from
     *             {@link Gumdrop} (this only matters if upstream
     *             forwarding is actually needed)
     * @param callback receives the response; always called exactly
     *                 once, with a concrete response (never {@code
     *                 onError}) -- a total upstream failure is
     *                 delivered as a SERVFAIL response, matching what
     *                 a client would see from a normal resolver
     */
    public void processQuery(final DNSMessage query, final SelectorLoop loop,
                             final DNSQueryCallback callback) {
        final DNSQuestion question = query.getQuestions().get(0);

        // 1. Check cache
        if (cacheEnabled && cache != null) {
            if (cache.isNegativelyCached(question.getName())) {
                if (metrics != null) { metrics.cacheHit(); }
                callback.onResponse(query.createErrorResponse(
                        DNSMessage.RCODE_NXDOMAIN));
                return;
            }

            List<DNSResourceRecord> cached = cache.lookup(question);
            if (cached != null) {
                if (metrics != null) { metrics.cacheHit(); }
                if (LOGGER.isLoggable(Level.FINEST)) {
                    String msg = MessageFormat.format(
                            L10N.getString("debug.cache_hit"), question);
                    LOGGER.finest(msg);
                }
                withMQTypeResponse(query, question,
                        query.createResponse(cached), loop, callback);
                return;
            }
            if (metrics != null) { metrics.cacheMiss(); }
        }

        // 2. Try custom resolution
        DNSMessage customResponse = resolve(query);
        if (customResponse != null) {
            if (cacheEnabled && cache != null
                    && !customResponse.getAnswers().isEmpty()) {
                cache.cache(question, customResponse.getAnswers());
            }
            withMQTypeResponse(query, question, customResponse, loop, callback);
            return;
        }

        // 3. Proxy to upstream
        proxyToUpstream(query, loop, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage upstreamResponse) {
                // Internal convention: proxyToUpstream reports total
                // failure (no upstream servers configured, or every
                // configured server failed) as a null response, not
                // onError -- translate that into SERVFAIL here so it
                // never leaks past this method.
                if (upstreamResponse == null) {
                    callback.onResponse(query.createErrorResponse(
                            DNSMessage.RCODE_SERVFAIL));
                    return;
                }
                if (cacheEnabled && cache != null) {
                    if (upstreamResponse.getRcode()
                            == DNSMessage.RCODE_NXDOMAIN) {
                        cache.cacheNegative(question.getName(),
                                upstreamResponse.getAuthorities());
                    } else if (!upstreamResponse.getAnswers().isEmpty()) {
                        cache.cache(question,
                                upstreamResponse.getAnswers());
                    }
                }
                withMQTypeResponse(query, question, upstreamResponse, loop, callback);
            }

            @Override
            public void onError(String error) {
                callback.onResponse(query.createErrorResponse(
                        DNSMessage.RCODE_SERVFAIL));
            }
        });
    }

    /**
     * RFC 10029 (DNS Multiple QTYPEs): if {@code query} carries an
     * {@code MQTYPE-Query} EDNS0 option, resolves each additional
     * RRTYPE concurrently (via a recursive {@link #processQuery} call
     * per type for a synthetic single-question message -- reusing the
     * same cache/{@link #resolve}/upstream pipeline that answered the
     * primary question, including its own caching) and, once every
     * additional type has resolved, merges whichever ones are
     * consistent with {@code primaryResponse} and fit the negotiated
     * UDP payload size into one combined response, echoing back which
     * types were covered via {@code MQTYPE-Response}. Anything not
     * covered is simply omitted -- the client is responsible for
     * falling back to a standalone query for it.
     *
     * @param query the original (possibly MQTYPE-Query-bearing) query
     * @param primaryQuestion {@code query}'s own question
     * @param primaryResponse the response already computed for
     *                        {@code primaryQuestion}
     * @param loop the SelectorLoop for any upstream connections the
     *             per-type sub-resolutions need
     * @param callback receives {@code primaryResponse}, unchanged,
     *                 merged with covered additional types, or a
     *                 FORMERR response if the MQTYPE-Query option
     *                 itself is invalid
     */
    private void withMQTypeResponse(final DNSMessage query,
                                    final DNSQuestion primaryQuestion,
                                    final DNSMessage primaryResponse,
                                    final SelectorLoop loop,
                                    final DNSQueryCallback callback) {
        byte[] optionData = findMQTypeQueryOption(query);
        if (optionData == null) {
            callback.onResponse(primaryResponse);
            return;
        }
        // RFC 10029: don't attempt to merge additional types into a
        // response that's already truncated.
        if (primaryResponse.isTruncated()) {
            callback.onResponse(primaryResponse);
            return;
        }
        final List<DNSType> requested;
        try {
            requested = DNSMultiQType.parseMQTypeQueryOption(optionData);
        } catch (DNSFormatException e) {
            callback.onResponse(query.createErrorResponse(DNSMessage.RCODE_FORMERR));
            return;
        }
        if (requested.isEmpty()
                || requested.size() > maxMQTypes
                || requested.contains(DNSType.ANY)
                || requested.contains(primaryQuestion.getType())
                || new HashSet<DNSType>(requested).size() != requested.size()) {
            callback.onResponse(query.createErrorResponse(DNSMessage.RCODE_FORMERR));
            return;
        }

        final int n = requested.size();
        final DNSMessage[] subResponses = new DNSMessage[n];
        final int[] remaining = { n };

        for (int idx = 0; idx < n; idx++) {
            final int i = idx;
            DNSType type = requested.get(i);
            resolveAdditionalType(query, primaryQuestion, type, loop,
                    new DNSQueryCallback() {
                        @Override
                        public void onResponse(DNSMessage subResponse) {
                            subResponses[i] = subResponse;
                            complete();
                        }

                        @Override
                        public void onError(String error) {
                            // Not covered -- same effect as any other
                            // mismatch below.
                            subResponses[i] = null;
                            complete();
                        }

                        private void complete() {
                            if (--remaining[0] == 0) {
                                callback.onResponse(mergeMQTypeResponses(
                                        query, primaryResponse, requested,
                                        subResponses));
                            }
                        }
                    });
        }
    }

    /**
     * Applies RFC 10029's greedy merge: walk {@code requested} in
     * order, folding in each additional type's answers only if its
     * response is consistent with {@code primaryResponse} and the
     * running merged response still fits the negotiated payload size.
     */
    private DNSMessage mergeMQTypeResponses(DNSMessage query,
                                            DNSMessage primaryResponse,
                                            List<DNSType> requested,
                                            DNSMessage[] subResponses) {
        int payloadLimit = udpPayloadSizeOf(query);
        List<DNSResourceRecord> mergedAnswers = new ArrayList<DNSResourceRecord>(
                primaryResponse.getAnswers());
        List<DNSType> covered = new ArrayList<DNSType>();
        for (int i = 0; i < requested.size(); i++) {
            DNSMessage subResponse = subResponses[i];
            if (subResponse == null) {
                continue;
            }
            if (subResponse.getRcode() != primaryResponse.getRcode()
                    || subResponse.isAuthoritative() != primaryResponse.isAuthoritative()
                    || subResponse.isAuthenticatedData() != primaryResponse.isAuthenticatedData()) {
                continue;
            }
            List<DNSResourceRecord> subAnswers = subResponse.getAnswers();
            if (subAnswers.isEmpty()) {
                continue;
            }
            List<DNSResourceRecord> candidateAnswers =
                    new ArrayList<DNSResourceRecord>(mergedAnswers);
            candidateAnswers.addAll(subAnswers);
            List<DNSType> candidateCovered = new ArrayList<DNSType>(covered);
            candidateCovered.add(requested.get(i));
            DNSMessage candidate = buildMergedResponse(
                    query, primaryResponse, candidateAnswers, candidateCovered);
            if (candidate.wireSize() > payloadLimit) {
                // Doesn't fit -- leave it uncovered; the client falls
                // back to a standalone query for just this type.
                continue;
            }
            mergedAnswers = candidateAnswers;
            covered = candidateCovered;
        }
        if (covered.isEmpty()) {
            return primaryResponse;
        }
        return buildMergedResponse(query, primaryResponse, mergedAnswers, covered);
    }

    private void resolveAdditionalType(DNSMessage query, DNSQuestion primaryQuestion,
                                       DNSType type, SelectorLoop loop,
                                       DNSQueryCallback callback) {
        DNSQuestion subQuestion = new DNSQuestion(
                primaryQuestion.getName(), type, primaryQuestion.getDNSClass());
        int subId = DNSQueryIdGenerator.allocateSynthetic();
        List<DNSResourceRecord> emptyList = Collections.emptyList();
        // No OPT/additionals carried over: the client's MQTYPE-Query
        // (and any cookie) is scoped to the original multi-type
        // request, not to this single-type sub-resolution -- forwarding
        // it upstream unchanged would be meaningless at best. resolve()
        // and proxyToUpstream() apply their own EDNS/DO handling for a
        // query that arrives without an OPT record, same as any other
        // EDNS0-less query.
        DNSMessage subQuery = new DNSMessage(subId, query.getFlags(),
                Collections.singletonList(subQuestion),
                emptyList, emptyList, emptyList);
        processQuery(subQuery, loop, callback);
    }

    private DNSMessage buildMergedResponse(DNSMessage query, DNSMessage primaryResponse,
                                           List<DNSResourceRecord> answers,
                                           List<DNSType> covered) {
        byte[] mqtypeResponseOption = DNSMultiQType.buildMQTypeResponseOption(covered);
        List<DNSResourceRecord> additionals = mergeOptionIntoAdditionals(
                primaryResponse.getAdditionals(), mqtypeResponseOption,
                udpPayloadSizeOf(query));
        return query.createResponse(answers, primaryResponse.getAuthorities(), additionals);
    }

    private static int udpPayloadSizeOf(DNSMessage query) {
        for (DNSResourceRecord rr : query.getAdditionals()) {
            if (rr.getType() == DNSType.OPT) {
                return rr.getUdpPayloadSize();
            }
        }
        return MAX_DNS_MESSAGE_SIZE;
    }

    private static byte[] findMQTypeQueryOption(DNSMessage query) {
        List<DNSResourceRecord> additionals = query.getAdditionals();
        for (int i = 0; i < additionals.size(); i++) {
            DNSResourceRecord rr = additionals.get(i);
            if (rr.getType() == DNSType.OPT) {
                return DNSCookie.findEdnsOption(rr.getRData(),
                        DNSMultiQType.EDNS_OPTION_MQTYPE_QUERY);
            }
        }
        return null;
    }

    // RFC 6891 section 6.1.2: EDNS0 options are concatenated back to
    // back within one OPT record's RDATA -- same merge pattern as
    // mergeCookieIntoAdditionals, generalized to any option's bytes.
    private static List<DNSResourceRecord> mergeOptionIntoAdditionals(
            List<DNSResourceRecord> existing, byte[] optionBytes, int udpPayloadSize) {
        List<DNSResourceRecord> result = new ArrayList<DNSResourceRecord>(existing);
        for (int i = 0; i < result.size(); i++) {
            DNSResourceRecord rr = result.get(i);
            if (rr.getType() == DNSType.OPT) {
                byte[] rdata = rr.getRData();
                byte[] merged = new byte[rdata.length + optionBytes.length];
                System.arraycopy(rdata, 0, merged, 0, rdata.length);
                System.arraycopy(optionBytes, 0, merged, rdata.length, optionBytes.length);
                result.set(i, DNSResourceRecord.opt(
                        rr.getUdpPayloadSize(), rr.getEDNSFlags(), merged));
                return result;
            }
        }
        result.add(DNSResourceRecord.opt(udpPayloadSize, optionBytes));
        return result;
    }

    /**
     * Override this method to provide custom name resolution.
     *
     * <p>Return a response message to handle the query locally,
     * or return null to proxy to upstream servers.
     *
     * @param query the DNS query
     * @return a response message, or null to proxy to upstream
     */
    protected DNSMessage resolve(DNSMessage query) {
        return null;
    }

    // ── Upstream proxy ──

    /**
     * RFC 1035 section 7.2: forwards {@code query} to the configured
     * upstream servers in order, asynchronously, until one produces an
     * acceptable answer. Reports total failure (no upstream servers
     * configured, or every server exhausted without an acceptable
     * answer) by calling {@code callback.onResponse(null)} -- this is
     * an internal convention private to this method and its caller,
     * not part of {@link DNSQueryCallback}'s general contract.
     */
    private void proxyToUpstream(DNSMessage query, SelectorLoop loop,
                                 DNSQueryCallback callback) {
        if (upstreamServers.isEmpty()) {
            callback.onResponse(null);
            return;
        }

        int upstreamId = DNSQueryIdGenerator.allocateSynthetic();
        // RFC 6891 section 6.1.1: add OPT record to advertise EDNS0
        // support and signal our UDP payload size to the upstream.
        List<DNSResourceRecord> additionals =
                new ArrayList<>(query.getAdditionals());
        boolean hasOpt = false;
        for (DNSResourceRecord rr : additionals) {
            if (rr.getType() == DNSType.OPT) {
                hasOpt = true;
                break;
            }
        }
        if (!hasOpt) {
            // RFC 4035 section 3.2.1: set DO bit to request DNSSEC records
            int ednsFlags = dnssecEnabled
                    ? DNSResourceRecord.EDNS_FLAG_DO : 0;
            additionals.add(DNSResourceRecord.opt(
                    MAX_DNS_MESSAGE_SIZE, ednsFlags, new byte[0]));
        }
        DNSMessage upstreamQuery = new DNSMessage(
                upstreamId,
                query.getFlags(),
                query.getQuestions(),
                query.getAnswers(),
                query.getAuthorities(),
                additionals
        );

        ByteBuffer queryBytes = upstreamQuery.serialize();
        byte[] queryData = new byte[queryBytes.remaining()];
        queryBytes.get(queryData);

        SelectorLoop effectiveLoop = loop;
        if (effectiveLoop == null) {
            // No natural loop available (e.g. a query submitted
            // directly rather than via a bound listener) -- fall back
            // to a worker loop, the same way SMTPClient/HTTPClient do
            // for outbound connections with no inherited loop.
            Gumdrop gumdrop = Gumdrop.getInstance();
            gumdrop.start();
            effectiveLoop = gumdrop.nextWorkerLoop();
        }

        tryUpstreamServer(0, query, upstreamQuery, queryData, upstreamId,
                effectiveLoop, callback);
    }

    private void tryUpstreamServer(int index, DNSMessage originalQuery,
                                   DNSMessage upstreamQuery, byte[] queryData,
                                   int upstreamId, SelectorLoop loop,
                                   DNSQueryCallback callback) {
        if (index >= upstreamServers.size()) {
            LOGGER.fine(L10N.getString("err.upstream_failed"));
            callback.onResponse(null);
            return;
        }
        new UpstreamAttempt(index, originalQuery, upstreamQuery, queryData,
                upstreamId, loop, callback).start();
    }

    /**
     * One asynchronous attempt to resolve a query against a single
     * upstream server over UDP (RFC 1035 section 4.2.1), with a
     * timeout and automatic fallback to the next configured server.
     * A truncated response (TC bit) triggers a {@link TcpRetry} to the
     * same server before this attempt is considered complete.
     */
    private final class UpstreamAttempt implements ProtocolHandler {

        private final int index;
        private final InetSocketAddress upstream;
        private final DNSMessage originalQuery;
        private final DNSMessage upstreamQuery;
        private final byte[] queryData;
        private final int upstreamId;
        private final SelectorLoop loop;
        private final DNSQueryCallback callback;
        private final long startNanos;

        private Endpoint endpoint;
        private TimerHandle timeoutTimer;
        private boolean done;

        UpstreamAttempt(int index, DNSMessage originalQuery, DNSMessage upstreamQuery,
                        byte[] queryData, int upstreamId, SelectorLoop loop,
                        DNSQueryCallback callback) {
            this.index = index;
            this.upstream = upstreamServers.get(index);
            this.originalQuery = originalQuery;
            this.upstreamQuery = upstreamQuery;
            this.queryData = queryData;
            this.upstreamId = upstreamId;
            this.loop = loop;
            this.callback = callback;
            this.startNanos = System.nanoTime();
        }

        void start() {
            try {
                upstreamUdpFactory.connect(
                        upstream.getAddress(), upstream.getPort(), this, loop);
            } catch (IOException e) {
                fail("err.upstream", e);
            }
        }

        @Override
        public void connected(Endpoint ep) {
            this.endpoint = ep;
            ep.send(ByteBuffer.wrap(queryData));
            timeoutTimer = ep.scheduleTimer(UPSTREAM_TIMEOUT_MS, new Runnable() {
                @Override
                public void run() {
                    fail("err.timeout_upstream", null);
                }
            });
        }

        // RFC 5452 section 9.1's source-address check is enforced by
        // the OS here rather than in application code: this endpoint's
        // channel is connect()-ed to exactly one peer (see
        // UDPTransportFactory.connect), so the kernel already discards
        // any datagram not from that address:port before it ever
        // reaches receive().
        @Override
        public void receive(ByteBuffer data) {
            if (done) {
                return;
            }
            DNSMessage response;
            try {
                response = DNSMessage.parse(data);
            } catch (DNSFormatException e) {
                fail("err.upstream_malformed", e);
                return;
            }

            // RFC 5452: verify response ID matches the query to
            // prevent blind spoofing attacks.
            if (response.getId() != upstreamId) {
                failWarn("warn.upstream_id_mismatch",
                        upstream, upstreamId, response.getId());
                return;
            }
            // RFC 1035 section 4.1.1: QR bit must be set in a response.
            if (!response.isResponse()) {
                failWarn("warn.upstream_not_response", upstream);
                return;
            }
            // RFC 5452 section 9.1: question section must echo the query.
            if (!response.getQuestions().equals(upstreamQuery.getQuestions())) {
                failWarn("warn.upstream_question_mismatch", upstream);
                return;
            }

            done = true;
            cancelTimer();
            endpoint.close();

            // RFC 1035 section 4.2.1: if response is truncated, retry
            // the same query over TCP to get the full answer.
            if (response.isTruncated()) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Truncated response from " + upstream
                            + ", retrying over TCP");
                }
                final DNSMessage udpResponse = response;
                new TcpRetry(upstream, queryData, upstreamId, loop,
                        new TcpRetryCallback() {
                            @Override
                            public void onResult(DNSMessage tcpResponse) {
                                succeed(tcpResponse != null ? tcpResponse : udpResponse);
                            }
                        }).start();
                return;
            }
            succeed(response);
        }

        @Override
        public void disconnected() {
            fail("err.upstream", null);
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            fail("err.upstream", cause);
        }

        private void succeed(DNSMessage response) {
            if (metrics != null) {
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                metrics.upstreamQuery(durationMs);
            }
            DNSMessage finalResponse = new DNSMessage(
                    originalQuery.getId(),
                    response.getFlags(),
                    response.getQuestions(),
                    response.getAnswers(),
                    response.getAuthorities(),
                    response.getAdditionals()
            );
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = MessageFormat.format(
                        L10N.getString("debug.upstream_response"),
                        upstream, finalResponse);
                LOGGER.fine(message);
            }
            callback.onResponse(finalResponse);
        }

        private void failWarn(String key, Object... args) {
            if (done) {
                return;
            }
            done = true;
            cancelTimer();
            if (endpoint != null) {
                endpoint.close();
            }
            if (metrics != null) {
                metrics.upstreamFailure();
            }
            LOGGER.warning(MessageFormat.format(L10N.getString(key), args));
            next();
        }

        private void fail(String key, Exception cause) {
            if (done) {
                return;
            }
            done = true;
            cancelTimer();
            if (endpoint != null) {
                endpoint.close();
            }
            if (metrics != null) {
                metrics.upstreamFailure();
            }
            String msg = MessageFormat.format(L10N.getString(key), upstream);
            if (cause != null) {
                LOGGER.log(Level.FINE, msg, cause);
            } else {
                LOGGER.log(Level.FINE, msg);
            }
            next();
        }

        private void cancelTimer() {
            if (timeoutTimer != null) {
                timeoutTimer.cancel();
            }
        }

        private void next() {
            tryUpstreamServer(index + 1, originalQuery, upstreamQuery,
                    queryData, upstreamId, loop, callback);
        }
    }

    private interface TcpRetryCallback {
        /**
         * @param response the TCP response, or null if the fallback
         *                 failed for any reason (the caller falls back
         *                 to the truncated UDP response it already has)
         */
        void onResult(DNSMessage response);
    }

    /**
     * RFC 1035 section 4.2.1/4.2.2: retries a query over TCP when the
     * UDP response was truncated (TC bit set), using the same 2-byte
     * length-prefixed framing as DNS-over-TCP.
     */
    private final class TcpRetry implements ProtocolHandler {

        private final InetSocketAddress upstream;
        private final byte[] queryData;
        private final int expectedId;
        private final SelectorLoop loop;
        private final TcpRetryCallback resultCallback;

        private Endpoint endpoint;
        private TimerHandle timeoutTimer;
        private boolean done;
        private ByteBuffer accumulator = ByteBuffer.allocate(512);

        TcpRetry(InetSocketAddress upstream, byte[] queryData, int expectedId,
                SelectorLoop loop, TcpRetryCallback resultCallback) {
            this.upstream = upstream;
            this.queryData = queryData;
            this.expectedId = expectedId;
            this.loop = loop;
            this.resultCallback = resultCallback;
            this.accumulator.flip();
        }

        void start() {
            try {
                upstreamTcpFactory.connect(
                        upstream.getAddress(), upstream.getPort(), this, loop);
            } catch (IOException e) {
                finish(null, "TCP fallback to " + upstream + " failed", e);
            }
        }

        @Override
        public void connected(Endpoint ep) {
            this.endpoint = ep;
            int length = queryData.length;
            ByteBuffer frame = ByteBuffer.allocate(TCP_LENGTH_PREFIX_SIZE + length);
            frame.put((byte) ((length >> 8) & 0xFF));
            frame.put((byte) (length & 0xFF));
            frame.put(queryData);
            frame.flip();
            ep.send(frame);
            timeoutTimer = ep.scheduleTimer(UPSTREAM_TIMEOUT_MS, new Runnable() {
                @Override
                public void run() {
                    finish(null, "TCP fallback to " + upstream + " timed out", null);
                }
            });
        }

        @Override
        public void receive(ByteBuffer data) {
            if (done) {
                return;
            }
            appendToAccumulator(data);
            if (accumulator.remaining() < TCP_LENGTH_PREFIX_SIZE) {
                return;
            }
            accumulator.mark();
            int messageLength = ((accumulator.get() & 0xFF) << 8)
                    | (accumulator.get() & 0xFF);
            if (messageLength <= 0 || messageLength > MAX_TCP_MESSAGE_SIZE) {
                finish(null, "TCP fallback to " + upstream
                        + " sent an invalid message length: " + messageLength, null);
                return;
            }
            if (accumulator.remaining() < messageLength) {
                accumulator.reset();
                return;
            }
            byte[] respData = new byte[messageLength];
            accumulator.get(respData);
            try {
                DNSMessage response = DNSMessage.parse(ByteBuffer.wrap(respData));
                if (response.getId() != expectedId) {
                    finish(null, "TCP fallback to " + upstream
                            + " returned a mismatched response ID", null);
                    return;
                }
                finish(response, null, null);
            } catch (DNSFormatException e) {
                finish(null, "TCP fallback to " + upstream
                        + " returned a malformed response", e);
            }
        }

        @Override
        public void disconnected() {
            finish(null, "TCP fallback to " + upstream + " closed unexpectedly", null);
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            finish(null, "TCP fallback to " + upstream + " failed", cause);
        }

        private void appendToAccumulator(ByteBuffer data) {
            int needed = accumulator.remaining() + data.remaining();
            if (needed > accumulator.capacity()) {
                ByteBuffer bigger = ByteBuffer.allocate(
                        Math.max(needed, accumulator.capacity() * 2));
                bigger.put(accumulator);
                bigger.put(data);
                bigger.flip();
                accumulator = bigger;
            } else {
                accumulator.compact();
                accumulator.put(data);
                accumulator.flip();
            }
        }

        private void finish(DNSMessage response, String logMessage, Exception cause) {
            if (done) {
                return;
            }
            done = true;
            if (timeoutTimer != null) {
                timeoutTimer.cancel();
            }
            if (endpoint != null) {
                endpoint.close();
            }
            if (logMessage != null) {
                if (cause != null) {
                    LOGGER.log(Level.FINE, logMessage, cause);
                } else {
                    LOGGER.fine(logMessage);
                }
            }
            resultCallback.onResult(response);
        }
    }

    // ── DNSSEC helpers ──

    /**
     * RFC 4035 section 3.2.1: removes DNSSEC-specific records
     * (RRSIG, DNSKEY, DS, NSEC, NSEC3, NSEC3PARAM) from a response
     * when the client did not set DO. Also clears the AD bit.
     */
    private DNSMessage stripDNSSECRecords(DNSMessage response) {
        List<DNSResourceRecord> answers =
                filterNonDNSSEC(response.getAnswers());
        List<DNSResourceRecord> authorities =
                filterNonDNSSEC(response.getAuthorities());
        List<DNSResourceRecord> additionals =
                filterNonDNSSEC(response.getAdditionals());

        int flags = response.getFlags() & ~DNSMessage.FLAG_AD;

        return new DNSMessage(response.getId(), flags,
                response.getQuestions(), answers, authorities,
                additionals);
    }

    private static List<DNSResourceRecord> filterNonDNSSEC(
            List<DNSResourceRecord> records) {
        List<DNSResourceRecord> filtered =
                new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            DNSResourceRecord rr = records.get(i);
            DNSType type = rr.getType();
            if (type != DNSType.RRSIG && type != DNSType.DNSKEY
                    && type != DNSType.DS && type != DNSType.NSEC
                    && type != DNSType.NSEC3
                    && type != DNSType.NSEC3PARAM) {
                filtered.add(rr);
            }
        }
        return filtered;
    }

    // ── Internal helpers ──

    static String rcodeToString(int rcode) {
        switch (rcode) {
            case DNSMessage.RCODE_NOERROR:  return "NOERROR";
            case DNSMessage.RCODE_FORMERR:  return "FORMERR";
            case DNSMessage.RCODE_SERVFAIL: return "SERVFAIL";
            case DNSMessage.RCODE_NXDOMAIN: return "NXDOMAIN";
            case DNSMessage.RCODE_NOTIMP:   return "NOTIMP";
            case DNSMessage.RCODE_REFUSED:  return "REFUSED";
            default:                        return String.valueOf(rcode);
        }
    }

    /**
     * Returns the EDNS cookie option from a query, or null if absent.
     */
    private static byte[] findRequestCookie(DNSMessage query) {
        List<DNSResourceRecord> additionals = query.getAdditionals();
        for (int i = 0; i < additionals.size(); i++) {
            DNSResourceRecord rr = additionals.get(i);
            if (rr.getType() == DNSType.OPT) {
                return DNSCookie.findEdnsOption(rr.getRData(),
                        DNSCookie.EDNS_OPTION_COOKIE);
            }
        }
        return null;
    }

    /**
     * RFC 7873 section 5.2.3: cookie-only response with no answer data.
     */
    private DNSMessage createCookieOnlyResponse(DNSMessage query,
            InetSocketAddress source, byte[] clientCookie) {
        byte[] serverCookie = dnsCookie.generateServerCookie(
                source.getAddress().getAddress(), clientCookie);
        byte[] cookieOption = buildCookieOptionBytes(
                clientCookie, serverCookie);
        List<DNSResourceRecord> additionals = Collections.singletonList(
                DNSResourceRecord.opt(DNSMessage.DEFAULT_EDNS_UDP_SIZE,
                        cookieOption));
        return query.createResponse(Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(), additionals);
    }

    /**
     * RFC 7873 section 5.2: echo client and server cookies on responses.
     */
    private DNSMessage attachResponseCookie(DNSMessage response,
            DNSMessage query, InetSocketAddress source,
            byte[] clientCookie) {
        byte[] serverCookie = dnsCookie.generateServerCookie(
                source.getAddress().getAddress(), clientCookie);
        byte[] cookieOption = buildCookieOptionBytes(
                clientCookie, serverCookie);
        List<DNSResourceRecord> additionals = mergeCookieIntoAdditionals(
                response.getAdditionals(), cookieOption);
        return query.createResponse(response.getAnswers(),
                response.getAuthorities(), additionals);
    }

    private static byte[] buildCookieOptionBytes(byte[] clientCookie,
            byte[] serverCookie) {
        int dataLen = clientCookie.length + serverCookie.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + dataLen);
        buf.putShort((short) DNSCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) dataLen);
        buf.put(clientCookie);
        buf.put(serverCookie);
        return buf.array();
    }

    private static List<DNSResourceRecord> mergeCookieIntoAdditionals(
            List<DNSResourceRecord> existing, byte[] cookieOption) {
        List<DNSResourceRecord> result = new ArrayList<DNSResourceRecord>(existing);
        for (int i = 0; i < result.size(); i++) {
            DNSResourceRecord rr = result.get(i);
            if (rr.getType() == DNSType.OPT) {
                byte[] rdata = rr.getRData();
                byte[] merged = new byte[rdata.length + cookieOption.length];
                System.arraycopy(rdata, 0, merged, 0, rdata.length);
                System.arraycopy(cookieOption, 0, merged, rdata.length,
                        cookieOption.length);
                result.set(i, DNSResourceRecord.opt(
                        rr.getUdpPayloadSize(), rr.getEDNSFlags(), merged));
                return result;
            }
        }
        result.add(DNSResourceRecord.opt(
                DNSMessage.DEFAULT_EDNS_UDP_SIZE, cookieOption));
        return result;
    }

    private void sendResponse(DNSListener origin,
                              DNSMessage response,
                              InetSocketAddress destination) {
        ByteBuffer data = response.serialize();

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("debug.sending_response");
            message = MessageFormat.format(
                    message, response, destination);
            LOGGER.fine(message);
        }

        origin.sendTo(data, destination);
    }

    private void wireListener(Object listener) {
        if (listener instanceof DNSListener) {
            ((DNSListener) listener).setService(this);
        } else if (listener instanceof DoTListener) {
            ((DoTListener) listener).setService(this);
        } else if (listener instanceof DoQListener) {
            ((DoQListener) listener).setService(this);
        }
    }

    private void startListener(Object listener) {
        if (listener instanceof DoQListener) {
            DoQListener doq = (DoQListener) listener;
            if (doq.getSelectorLoop() == null) {
                doq.setSelectorLoop(
                        Gumdrop.getInstance().nextWorkerLoop());
            }
        }
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to start DNS listener: " + listener, e);
            }
        }
    }

    private void stopListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error stopping DNS listener: " + listener, e);
            }
        }
    }

    private static InetSocketAddress parseAddress(String address,
                                                  int defaultPort)
            throws Exception {
        int port = defaultPort;
        String host = address;

        if (address.startsWith("[")) {
            int bracketEnd = address.indexOf(']');
            if (bracketEnd > 0) {
                host = address.substring(1, bracketEnd);
                if (address.length() > bracketEnd + 2
                        && address.charAt(bracketEnd + 1) == ':') {
                    port = Integer.parseInt(
                            address.substring(bracketEnd + 2));
                }
            }
        } else if (address.indexOf(':') >= 0
                && address.indexOf("::") < 0) {
            int colonIdx = address.lastIndexOf(':');
            host = address.substring(0, colonIdx);
            port = Integer.parseInt(
                    address.substring(colonIdx + 1));
        }

        InetAddress inetAddr = InetAddress.getByName(host);
        return new InetSocketAddress(inetAddr, port);
    }

    private void loadSystemResolvers() {
        Path resolvConf = Paths.get("/etc/resolv.conf");
        if (Files.exists(resolvConf)) {
            try {
                BufferedReader reader = new BufferedReader(
                        new FileReader(resolvConf.toFile()));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("nameserver ")) {
                            String server = line.substring(11).trim();
                            try {
                                InetSocketAddress addr =
                                        parseAddress(server,
                                                DEFAULT_PORT);
                                upstreamServers.add(addr);
                                if (LOGGER.isLoggable(Level.FINE)) {
                                    String msg = MessageFormat.format(
                                            L10N.getString(
                                                    "debug.added_system_resolver"),
                                            addr);
                                    LOGGER.fine(msg);
                                }
                            } catch (Exception e) {
                                String msg = MessageFormat.format(
                                        L10N.getString(
                                                "debug.skip_invalid_resolver"),
                                        server);
                                LOGGER.log(Level.FINE, msg, e);
                            }
                        }
                    }
                } finally {
                    reader.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE,
                        L10N.getString("err.read_resolv_conf"), e);
            }
        }

        if (upstreamServers.isEmpty()) {
            try {
                InetAddress google =
                        InetAddress.getByName("8.8.8.8");
                upstreamServers.add(
                        new InetSocketAddress(google, DEFAULT_PORT));
                InetAddress cloudflare =
                        InetAddress.getByName("1.1.1.1");
                upstreamServers.add(
                        new InetSocketAddress(cloudflare, DEFAULT_PORT));
                LOGGER.fine(L10N.getString("debug.using_fallback"));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("warn.no_fallback"), e);
            }
        }
    }

}
