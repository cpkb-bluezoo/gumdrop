/*
 * DnsServer.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsCookie;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsListener;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsMultiQType;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsServerMetrics;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.DoQListener;
import org.bluezoo.gumdrop.dns.DoTListener;

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
import org.bluezoo.gumdrop.dns.DnsQueryIdGenerator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * DNS protocol server — listeners, validation, and query dispatch to a
 * {@link DnsQueryHandler}. Default handler returns empty results.
 * RFC 1035 section 6: name server implementation. This service operates
 * as a caching forwarder (RFC 1035 section 7) rather than an authoritative
 * server. It validates incoming queries (section 4.1.1) and returns
 * appropriate error codes (FORMERR, NOTIMP, SERVFAIL, NXDOMAIN).
 *
 * <p>The default implementation proxies all queries to configured upstream
 * DNS servers. Subclasses can override {@link #resolve(DnsMessage)} to
 * provide custom name resolution.
 *
 * <p>Upstream forwarding is fully asynchronous: it runs on
 * {@link org.bluezoo.gumdrop.UdpEndpoint}/{@link
 * org.bluezoo.gumdrop.TcpEndpoint} client-mode connections registered
 * with a {@link SelectorLoop}, the same core transport primitives
 * {@code UdpListener}/{@code TcpListener} themselves are built on --
 * never a blocking socket, and never the {@code dns.client} resolver
 * stack (that's a stub-resolver abstraction for applications, not for
 * a server's own internal forwarding).
 *
 * <p>The service manages one or more listeners. Currently supported:
 * <ul>
 *   <li>{@link DnsListener} &ndash; standard DNS over UDP</li>
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
 * <service class="org.bluezoo.gumdrop.dns.DnsServer">
 *   <property name="upstream-servers">8.8.8.8 1.1.1.1</property>
 *   <property name="cache-enabled">true</property>
 *   <listener class="org.bluezoo.gumdrop.dns.DnsListener"
 *           port="5353"/>
 * </service>
 * }</pre>
 *
 * <p>Example subclass for custom resolution:
 * <pre>{@code
 * public class MyDNSService extends DnsServer {
 *     @Override
 *     protected DnsMessage resolve(DnsMessage query) {
 *         DnsQuestion question = query.getQuestions().get(0);
 *         if ("internal.example.com".equals(question.getName())) {
 *             List answers = new ArrayList();
 *             answers.add(DnsResourceRecord.a("internal.example.com", 300,
 *                     InetAddress.getByName("10.0.0.1")));
 *             return query.createResponse(answers);
 *         }
 *         return null; // Fall through to upstream
 *     }
 * }
 * }</pre>
 *
 * <p>Non-QUERY opcodes (RFC 1996 NOTIFY, RFC 2136 dynamic update, and
 * others) are dispatched to {@link DnsQueryHandler#handleNonQueryOpcode}.
 * Handlers return {@code false} when they do not support an opcode; the
 * default when no handler claims the message is {@code NOTIMP}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see DnsListener
 */
public class DnsServer implements Server {

    private static final Logger LOGGER =
            Logger.getLogger(DnsServer.class.getName());
    public static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long UPSTREAM_TIMEOUT_MS = 5000;
    // RFC 6891 section 6.2.5: with EDNS0, the UDP payload size is
    // negotiated via the OPT record. We use 4096 as the default.
    private static final int MAX_DNS_MESSAGE_SIZE =
            DnsMessage.DEFAULT_EDNS_UDP_SIZE;
    // RFC 1035 section 4.2.2: 2-byte big-endian length prefix
    private static final int TCP_LENGTH_PREFIX_SIZE = 2;
    private static final int MAX_TCP_MESSAGE_SIZE = 65535;

    private final List<Listener> listeners = new ArrayList<Listener>();

    // ── Configuration ──

    private int maxMQTypes = DnsMultiQType.DEFAULT_MAX_MQTYPES;
    private DnsServerMetrics metrics;

    private final DnsCookie dnsCookie = new DnsCookie();

    private DnsQueryHandler queryHandler;
    private DnsQueryHandler activeHandler;
    private UpstreamRelayHandler legacyRelay;

    /**
     * Creates a new DNS service.
     */
    public DnsServer() {
    }

    /**
     * Starts fluent composition of a concrete {@link DnsServer}.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * @deprecated use {@link #compose()}.
     */
    @Deprecated
    public static Composer builder() {
        return compose();
    }

    /**
     * Sets the query handler for composed servers.
     */
    public void setHandler(DnsQueryHandler handler) {
        this.queryHandler = handler;
    }

    // ── Listener management ──

    /**
     * Adds a UDP DNS listener.
     *
     * @param endpoint the DNS server endpoint
     */
    public void addListener(DnsListener endpoint) {
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
     * a {@link DnsListener}, {@link DoTListener}, or
     * {@link DoQListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof DnsListener) {
                addListener((DnsListener) item);
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
        legacyRelay().setUpstreamServers(servers);
    }

    /**
     * Sets whether to use system resolvers from /etc/resolv.conf.
     *
     * @param useSystemResolvers true to use system resolvers as fallback
     */
    public void setUseSystemResolvers(boolean useSystemResolvers) {
        legacyRelay().setUseSystemResolvers(useSystemResolvers);
    }

    /**
     * Sets whether response caching is enabled.
     *
     * @param cacheEnabled true to enable caching
     */
    public void setCacheEnabled(boolean cacheEnabled) {
        legacyRelay().setCacheEnabled(cacheEnabled);
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
        legacyRelay().setDnssecEnabled(dnssecEnabled);
    }

    /**
     * Returns true if DNSSEC-aware proxying is enabled.
     *
     * @return true if DNSSEC is enabled
     */
    public boolean isDnssecEnabled() {
        if (activeHandler instanceof UpstreamRelayHandler) {
            return ((UpstreamRelayHandler) activeHandler).isDnssecEnabled();
        }
        UpstreamRelayHandler relay = getLegacyRelayIfConfigured();
        return relay != null && relay.isDnssecEnabled();
    }

    /**
     * Sets the maximum number of additional RRTYPEs this server will
     * merge into one response via RFC 10029 (DNS Multiple QTYPEs). A
     * client's {@code MQTYPE-Query} option requesting more than this
     * many types is rejected with FORMERR.
     *
     * @param maxMQTypes the cap; defaults to
     *                   {@link DnsMultiQType#DEFAULT_MAX_MQTYPES}
     */
    public void setMaxMQTypes(int maxMQTypes) {
        this.maxMQTypes = maxMQTypes;
    }

    /**
     * Returns the DNS cache.
     *
     * @return the cache, or null if caching is disabled
     */
    public DnsCache getCache() {
        UpstreamRelayHandler relay = getLegacyRelayIfConfigured();
        return relay != null ? relay.getCache() : null;
    }

    /**
     * Returns the DNS server metrics, or null if metrics are disabled.
     *
     * @return the metrics instance
     */
    public DnsServerMetrics getMetrics() {
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
    public void start(Gumdrop gumdrop) {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof Listener) {
                Listener l = (Listener) listener;
                TelemetryConfig tc = l.getTelemetryConfig();
                if (tc != null && tc.isMetricsEnabled()) {
                    metrics = new DnsServerMetrics(tc);
                    break;
                }
            }
        }

        activeHandler = resolveActiveHandler();
        if (metrics != null && activeHandler instanceof UpstreamRelayHandler) {
            ((UpstreamRelayHandler) activeHandler).setMetrics(metrics);
        }
        activeHandler.start(gumdrop);

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener);
            startListener(gumdrop, listener);
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        if (activeHandler != null) {
            activeHandler.stop();
            activeHandler = null;
        }
        destroyService();
    }

    // ── Query handling ──

    /**
     * Handles a DNS datagram received from a UDP listener.
     *
     * <p>This is called by {@link DnsListener} when a datagram
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
    public void handleDatagram(final DnsListener origin,
                        ByteBuffer data,
                        final InetSocketAddress source,
                        final Runnable onComplete) {
        try {
            final DnsMessage query = DnsMessage.parse(data);

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.received_query");
                message = MessageFormat.format(message, query, source);
                LOGGER.fine(message);
            }

            if (metrics != null && !query.getQuestions().isEmpty()) {
                DnsQuestion q = query.getQuestions().get(0);
                metrics.queryReceived(q.getType().name(), "udp");
            }

            if (!isStandardQuery(query)) {
                dispatchNonQueryOpcode(query, origin.getSelectorLoop(),
                        new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        sendResponse(origin, response, source);
                        onComplete.run();
                    }

                    @Override
                    public void onError(String error) {
                        sendResponse(origin, query.createErrorResponse(
                                DnsMessage.RCODE_SERVFAIL), source);
                        onComplete.run();
                    }
                });
                return;
            }

            // RFC 1035 section 4.1.2: question section must not be empty
            if (query.getQuestions().isEmpty()) {
                DnsMessage error = query.createErrorResponse(
                        DnsMessage.RCODE_FORMERR);
                sendResponse(origin, error, source);
                onComplete.run();
                return;
            }

            // RFC 7873: when a client cookie is present but the server
            // cookie is missing or invalid, return a cookie-only response
            // instead of resolving the query (anti-amplification).
            final byte[] requestCookie = findRequestCookie(query);
            if (requestCookie != null) {
                if (requestCookie.length < DnsCookie.CLIENT_COOKIE_LENGTH) {
                    sendResponse(origin, query.createErrorResponse(
                            DnsMessage.RCODE_FORMERR), source);
                    onComplete.run();
                    return;
                }
                byte[] clientCookie = Arrays.copyOf(requestCookie,
                        DnsCookie.CLIENT_COOKIE_LENGTH);
                if (requestCookie.length == DnsCookie.CLIENT_COOKIE_LENGTH
                        || !dnsCookie.validateServerCookie(
                                source.getAddress().getAddress(),
                                clientCookie,
                                Arrays.copyOfRange(requestCookie,
                                        DnsCookie.CLIENT_COOKIE_LENGTH,
                                        requestCookie.length))) {
                    sendResponse(origin, createCookieOnlyResponse(query,
                            source, clientCookie), source);
                    onComplete.run();
                    return;
                }
            }

            final long startNanos = System.nanoTime();
            SelectorLoop loop = origin.getSelectorLoop();
            processQuery(query, loop, new DnsQueryCallback() {
                @Override
                public void onResponse(DnsMessage response) {
                    // RFC 4035 section 3.2.1: strip DNSSEC records from
                    // responses when the client did not set DO.
                    DnsMessage finalResponse = response;
                    if (isDnssecEnabled() && !query.hasDO()) {
                        finalResponse = stripDNSSECRecords(finalResponse);
                    }

                    if (requestCookie != null) {
                        byte[] clientCookie = Arrays.copyOf(requestCookie,
                                DnsCookie.CLIENT_COOKIE_LENGTH);
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
                            DnsMessage.RCODE_SERVFAIL), source);
                    onComplete.run();
                }
            });

        } catch (DnsFormatException e) {
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
    public void processQuery(final DnsMessage query, final SelectorLoop loop,
                             final DnsQueryCallback callback) {
        final DnsQuestion question = query.getQuestions().get(0);
        DnsQueryHandler handler = activeHandler != null
                ? activeHandler : resolveActiveHandler();
        handler.handleQuery(query, loop, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                withMQTypeResponse(query, question, response, loop, callback);
            }

            @Override
            public void onError(String error) {
                callback.onResponse(query.createErrorResponse(
                        DnsMessage.RCODE_SERVFAIL));
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
    private void withMQTypeResponse(final DnsMessage query,
                                    final DnsQuestion primaryQuestion,
                                    final DnsMessage primaryResponse,
                                    final SelectorLoop loop,
                                    final DnsQueryCallback callback) {
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
        final List<DnsType> requested;
        try {
            requested = DnsMultiQType.parseMQTypeQueryOption(optionData);
        } catch (DnsFormatException e) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_FORMERR));
            return;
        }
        if (requested.isEmpty()
                || requested.size() > maxMQTypes
                || requested.contains(DnsType.ANY)
                || requested.contains(primaryQuestion.getType())
                || new HashSet<DnsType>(requested).size() != requested.size()) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_FORMERR));
            return;
        }

        final int n = requested.size();
        final DnsMessage[] subResponses = new DnsMessage[n];
        final int[] remaining = { n };

        for (int idx = 0; idx < n; idx++) {
            final int i = idx;
            DnsType type = requested.get(i);
            resolveAdditionalType(query, primaryQuestion, type, loop,
                    new DnsQueryCallback() {
                        @Override
                        public void onResponse(DnsMessage subResponse) {
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
    private DnsMessage mergeMQTypeResponses(DnsMessage query,
                                            DnsMessage primaryResponse,
                                            List<DnsType> requested,
                                            DnsMessage[] subResponses) {
        int payloadLimit = udpPayloadSizeOf(query);
        List<DnsResourceRecord> mergedAnswers = new ArrayList<DnsResourceRecord>(
                primaryResponse.getAnswers());
        List<DnsType> covered = new ArrayList<DnsType>();
        for (int i = 0; i < requested.size(); i++) {
            DnsMessage subResponse = subResponses[i];
            if (subResponse == null) {
                continue;
            }
            if (subResponse.getRcode() != primaryResponse.getRcode()
                    || subResponse.isAuthoritative() != primaryResponse.isAuthoritative()
                    || subResponse.isAuthenticatedData() != primaryResponse.isAuthenticatedData()) {
                continue;
            }
            List<DnsResourceRecord> subAnswers = subResponse.getAnswers();
            if (subAnswers.isEmpty()) {
                continue;
            }
            List<DnsResourceRecord> candidateAnswers =
                    new ArrayList<DnsResourceRecord>(mergedAnswers);
            candidateAnswers.addAll(subAnswers);
            List<DnsType> candidateCovered = new ArrayList<DnsType>(covered);
            candidateCovered.add(requested.get(i));
            DnsMessage candidate = buildMergedResponse(
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

    private void resolveAdditionalType(DnsMessage query, DnsQuestion primaryQuestion,
                                       DnsType type, SelectorLoop loop,
                                       DnsQueryCallback callback) {
        DnsQuestion subQuestion = new DnsQuestion(
                primaryQuestion.getName(), type, primaryQuestion.getDNSClass());
        int subId = DnsQueryIdGenerator.allocateSynthetic();
        List<DnsResourceRecord> emptyList = Collections.emptyList();
        // No OPT/additionals carried over: the client's MQTYPE-Query
        // (and any cookie) is scoped to the original multi-type
        // request, not to this single-type sub-resolution -- forwarding
        // it upstream unchanged would be meaningless at best. resolve()
        // and proxyToUpstream() apply their own EDNS/DO handling for a
        // query that arrives without an OPT record, same as any other
        // EDNS0-less query.
        DnsMessage subQuery = new DnsMessage(subId, query.getFlags(),
                Collections.singletonList(subQuestion),
                emptyList, emptyList, emptyList);
        processQuery(subQuery, loop, callback);
    }

    private DnsMessage buildMergedResponse(DnsMessage query, DnsMessage primaryResponse,
                                           List<DnsResourceRecord> answers,
                                           List<DnsType> covered) {
        byte[] mqtypeResponseOption = DnsMultiQType.buildMQTypeResponseOption(covered);
        List<DnsResourceRecord> additionals = mergeOptionIntoAdditionals(
                primaryResponse.getAdditionals(), mqtypeResponseOption,
                udpPayloadSizeOf(query));
        return query.createResponse(answers, primaryResponse.getAuthorities(), additionals);
    }

    private static int udpPayloadSizeOf(DnsMessage query) {
        for (DnsResourceRecord rr : query.getAdditionals()) {
            if (rr.getType() == DnsType.OPT) {
                return rr.getUdpPayloadSize();
            }
        }
        return MAX_DNS_MESSAGE_SIZE;
    }

    private static byte[] findMQTypeQueryOption(DnsMessage query) {
        List<DnsResourceRecord> additionals = query.getAdditionals();
        for (int i = 0; i < additionals.size(); i++) {
            DnsResourceRecord rr = additionals.get(i);
            if (rr.getType() == DnsType.OPT) {
                return DnsCookie.findEdnsOption(rr.getRData(),
                        DnsMultiQType.EDNS_OPTION_MQTYPE_QUERY);
            }
        }
        return null;
    }

    // RFC 6891 section 6.1.2: EDNS0 options are concatenated back to
    // back within one OPT record's RDATA -- same merge pattern as
    // mergeCookieIntoAdditionals, generalized to any option's bytes.
    private static List<DnsResourceRecord> mergeOptionIntoAdditionals(
            List<DnsResourceRecord> existing, byte[] optionBytes, int udpPayloadSize) {
        List<DnsResourceRecord> result = new ArrayList<DnsResourceRecord>(existing);
        for (int i = 0; i < result.size(); i++) {
            DnsResourceRecord rr = result.get(i);
            if (rr.getType() == DnsType.OPT) {
                byte[] rdata = rr.getRData();
                byte[] merged = new byte[rdata.length + optionBytes.length];
                System.arraycopy(rdata, 0, merged, 0, rdata.length);
                System.arraycopy(optionBytes, 0, merged, rdata.length, optionBytes.length);
                result.set(i, DnsResourceRecord.opt(
                        rr.getUdpPayloadSize(), rr.getEDNSFlags(), merged));
                return result;
            }
        }
        result.add(DnsResourceRecord.opt(udpPayloadSize, optionBytes));
        return result;
    }

    /**
     * Returns true if {@code query} is a standard QUERY (RFC 1035
     * section 4.1.1).
     */
    public static boolean isStandardQuery(DnsMessage query) {
        return query.isQuery()
                && query.getOpcode() == DnsMessage.OPCODE_QUERY;
    }

    /**
     * Dispatches a non-{@code OPCODE_QUERY} message to the active
     * {@link DnsQueryHandler}. DoT and DoQ listeners delegate here.
     *
     * @param query the parsed DNS message
     * @param loop the selector loop the message arrived on
     * @param callback invoked exactly once with the response
     */
    public void dispatchNonQueryOpcode(final DnsMessage query,
                                       final SelectorLoop loop,
                                       final DnsQueryCallback callback) {
        DnsQueryHandler handler = activeHandler != null
                ? activeHandler : resolveActiveHandler();
        if (handler.handleNonQueryOpcode(query, loop, callback)) {
            return;
        }
        callback.onResponse(query.createErrorResponse(
                DnsMessage.RCODE_NOTIMP));
    }

    /**
     * Override this method to provide custom name resolution.
     *
     * <p>Return a response message to handle the query locally,
     * or return {@code null} for an empty {@code NOERROR} response when
     * no explicit handler is configured.
     *
     * @param query the DNS query
     * @return a response message, or {@code null} for empty {@code NOERROR}
     */
    protected DnsMessage resolve(DnsMessage query) {
        return null;
    }

    // ── DNSSEC helpers ──

    /**
     * RFC 4035 section 3.2.1: removes DNSSEC-specific records
     * (RRSIG, DNSKEY, DS, NSEC, NSEC3, NSEC3PARAM) from a response
     * when the client did not set DO. Also clears the AD bit.
     */
    private DnsMessage stripDNSSECRecords(DnsMessage response) {
        List<DnsResourceRecord> answers =
                filterNonDNSSEC(response.getAnswers());
        List<DnsResourceRecord> authorities =
                filterNonDNSSEC(response.getAuthorities());
        List<DnsResourceRecord> additionals =
                filterNonDNSSEC(response.getAdditionals());

        int flags = response.getFlags() & ~DnsMessage.FLAG_AD;

        return new DnsMessage(response.getId(), flags,
                response.getQuestions(), answers, authorities,
                additionals);
    }

    private static List<DnsResourceRecord> filterNonDNSSEC(
            List<DnsResourceRecord> records) {
        List<DnsResourceRecord> filtered =
                new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            DnsResourceRecord rr = records.get(i);
            DnsType type = rr.getType();
            if (type != DnsType.RRSIG && type != DnsType.DNSKEY
                    && type != DnsType.DS && type != DnsType.NSEC
                    && type != DnsType.NSEC3
                    && type != DnsType.NSEC3PARAM) {
                filtered.add(rr);
            }
        }
        return filtered;
    }

    // ── Internal helpers ──

    public static String rcodeToString(int rcode) {
        switch (rcode) {
            case DnsMessage.RCODE_NOERROR:  return "NOERROR";
            case DnsMessage.RCODE_FORMERR:  return "FORMERR";
            case DnsMessage.RCODE_SERVFAIL: return "SERVFAIL";
            case DnsMessage.RCODE_NXDOMAIN: return "NXDOMAIN";
            case DnsMessage.RCODE_NOTIMP:   return "NOTIMP";
            case DnsMessage.RCODE_REFUSED:  return "REFUSED";
            default:                        return String.valueOf(rcode);
        }
    }

    /**
     * Returns the EDNS cookie option from a query, or null if absent.
     */
    private static byte[] findRequestCookie(DnsMessage query) {
        List<DnsResourceRecord> additionals = query.getAdditionals();
        for (int i = 0; i < additionals.size(); i++) {
            DnsResourceRecord rr = additionals.get(i);
            if (rr.getType() == DnsType.OPT) {
                return DnsCookie.findEdnsOption(rr.getRData(),
                        DnsCookie.EDNS_OPTION_COOKIE);
            }
        }
        return null;
    }

    /**
     * RFC 7873 section 5.2.3: cookie-only response with no answer data.
     */
    private DnsMessage createCookieOnlyResponse(DnsMessage query,
            InetSocketAddress source, byte[] clientCookie) {
        byte[] serverCookie = dnsCookie.generateServerCookie(
                source.getAddress().getAddress(), clientCookie);
        byte[] cookieOption = buildCookieOptionBytes(
                clientCookie, serverCookie);
        List<DnsResourceRecord> additionals = Collections.singletonList(
                DnsResourceRecord.opt(DnsMessage.DEFAULT_EDNS_UDP_SIZE,
                        cookieOption));
        return query.createResponse(Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(), additionals);
    }

    /**
     * RFC 7873 section 5.2: echo client and server cookies on responses.
     */
    private DnsMessage attachResponseCookie(DnsMessage response,
            DnsMessage query, InetSocketAddress source,
            byte[] clientCookie) {
        byte[] serverCookie = dnsCookie.generateServerCookie(
                source.getAddress().getAddress(), clientCookie);
        byte[] cookieOption = buildCookieOptionBytes(
                clientCookie, serverCookie);
        List<DnsResourceRecord> additionals = mergeCookieIntoAdditionals(
                response.getAdditionals(), cookieOption);
        return query.createResponse(response.getAnswers(),
                response.getAuthorities(), additionals);
    }

    private static byte[] buildCookieOptionBytes(byte[] clientCookie,
            byte[] serverCookie) {
        int dataLen = clientCookie.length + serverCookie.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + dataLen);
        buf.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) dataLen);
        buf.put(clientCookie);
        buf.put(serverCookie);
        return buf.array();
    }

    private static List<DnsResourceRecord> mergeCookieIntoAdditionals(
            List<DnsResourceRecord> existing, byte[] cookieOption) {
        List<DnsResourceRecord> result = new ArrayList<DnsResourceRecord>(existing);
        for (int i = 0; i < result.size(); i++) {
            DnsResourceRecord rr = result.get(i);
            if (rr.getType() == DnsType.OPT) {
                byte[] rdata = rr.getRData();
                byte[] merged = new byte[rdata.length + cookieOption.length];
                System.arraycopy(rdata, 0, merged, 0, rdata.length);
                System.arraycopy(cookieOption, 0, merged, rdata.length,
                        cookieOption.length);
                result.set(i, DnsResourceRecord.opt(
                        rr.getUdpPayloadSize(), rr.getEDNSFlags(), merged));
                return result;
            }
        }
        result.add(DnsResourceRecord.opt(
                DnsMessage.DEFAULT_EDNS_UDP_SIZE, cookieOption));
        return result;
    }

    private void sendResponse(DnsListener origin,
                              DnsMessage response,
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
        if (listener instanceof DnsListener) {
            ((DnsListener) listener).setServer(this);
        } else if (listener instanceof DoTListener) {
            ((DoTListener) listener).setServer(this);
        } else if (listener instanceof DoQListener) {
            ((DoQListener) listener).setServer(this);
        }
    }

    private void startListener(Gumdrop gumdrop, Object listener) {
        if (listener instanceof DoQListener) {
            DoQListener doq = (DoQListener) listener;
            if (doq.getSelectorLoop() == null) {
                doq.setSelectorLoop(gumdrop.nextWorkerLoop());
            }
        }
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start(gumdrop);
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

    private UpstreamRelayHandler legacyRelay() {
        if (legacyRelay == null) {
            legacyRelay = new UpstreamRelayHandler();
        }
        return legacyRelay;
    }

    private boolean legacyRelayConfigured() {
        return legacyRelay != null;
    }

    private UpstreamRelayHandler getLegacyRelayIfConfigured() {
        return legacyRelayConfigured() ? legacyRelay : null;
    }

    private DnsQueryHandler resolveActiveHandler() {
        if (queryHandler != null) {
            return queryHandler;
        }
        if (getClass() != DnsServer.class) {
            DnsQueryHandler resolveHandler = new LegacyResolveDnsHandler(this);
            if (legacyRelayConfigured()) {
                return DnsQueryHandlers.chain(resolveHandler, legacyRelay());
            }
            return resolveHandler;
        }
        if (legacyRelayConfigured()) {
            return legacyRelay();
        }
        return DnsQueryHandlers.empty();
    }

    /**
     * Fluent composition of listeners and an optional query handler.
     */
    public static final class Composer {

        private final List<Listener> listeners = new ArrayList<Listener>();
        private DnsQueryHandler handler;

        private Composer() {
        }

        public Composer listener(DnsListener listener) {
            listeners.add(listener);
            return this;
        }

        public Composer listener(DoTListener listener) {
            listeners.add(listener);
            return this;
        }

        public Composer listener(DoQListener listener) {
            listeners.add(listener);
            return this;
        }

        public Composer handler(DnsQueryHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Creates the composed server. At least one listener is required.
         */
        public DnsServer server() {
            if (listeners.isEmpty()) {
                throw new IllegalStateException("at least one listener is required");
            }
            DnsServer server = new DnsServer();
            if (handler != null) {
                server.setHandler(handler);
            }
            for (int i = 0; i < listeners.size(); i++) {
                server.listeners.add(listeners.get(i));
            }
            return server;
        }

        /**
         * @deprecated use {@link #server()}.
         */
        @Deprecated
        public DnsServer build() {
            return server();
        }
    }

}
