/*
 * UpstreamRelayHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQueryIdGenerator;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsServerMetrics;
import org.bluezoo.gumdrop.dns.DnsType;

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
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UdpTransportFactory;

/**
 * Caching DNS forwarder — proxies queries to configured upstream resolvers.
 *
 * <p>Compose explicitly on {@link DnsServer}; not the default behaviour.
 *
 * @see DnsQueryHandlers
 * @see EmptyDnsQueryHandler
 */
public final class UpstreamRelayHandler implements DnsQueryHandler {

    private static final Logger LOGGER =
            Logger.getLogger(UpstreamRelayHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long UPSTREAM_TIMEOUT_MS = 5000;
    private static final int MAX_DNS_MESSAGE_SIZE =
            DnsMessage.DEFAULT_EDNS_UDP_SIZE;
    private static final int TCP_LENGTH_PREFIX_SIZE = 2;
    private static final int MAX_TCP_MESSAGE_SIZE = 65535;

    private final List<InetSocketAddress> upstreamServers = new ArrayList<InetSocketAddress>();
    private boolean useSystemResolvers;
    private boolean cacheEnabled = true;
    private boolean dnssecEnabled;
    private boolean serveStaleEnabled = true;
    private int staleRetentionSeconds = DnsCache.DEFAULT_STALE_RETENTION_SECONDS;
    private int staleAnswerTtl = DnsCache.DEFAULT_STALE_ANSWER_TTL;
    private ServeStalePolicy serveStalePolicy = ServeStalePolicy.ENABLED;
    private NxDomainCutPolicy nxDomainCutPolicy = NxDomainCutPolicy.ENABLED;
    private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;
    private DnsCache cache;
    private DnsServerMetrics metrics;

    private UdpTransportFactory upstreamUdpFactory;
    private TcpTransportFactory upstreamTcpFactory;
    private Gumdrop gumdrop;

    public UpstreamRelayHandler() {
    }

    public static Builder builder() {
        return new Builder();
    }


    public void setUpstreamServers(String servers) {
        upstreamServers.clear();
        if (servers == null || servers.trim().isEmpty()) {
            return;
        }
        StringTokenizer st = new StringTokenizer(servers);
        while (st.hasMoreTokens()) {
            String server = st.nextToken();
            try {
                upstreamServers.add(parseAddress(server, DEFAULT_PORT));
            } catch (Exception e) {
                String msg = MessageFormat.format(
                        L10N.getString("err.invalid_upstream_server"), server);
                LOGGER.log(Level.WARNING, msg, e);
            }
        }
    }

    public void setUseSystemResolvers(boolean useSystemResolvers) {
        this.useSystemResolvers = useSystemResolvers;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public void setServeStaleEnabled(boolean serveStaleEnabled) {
        this.serveStaleEnabled = serveStaleEnabled;
    }

    public void setStaleRetentionSeconds(int staleRetentionSeconds) {
        this.staleRetentionSeconds = staleRetentionSeconds;
    }

    public void setStaleAnswerTtl(int staleAnswerTtl) {
        this.staleAnswerTtl = staleAnswerTtl;
    }

    public void setServeStalePolicy(ServeStalePolicy serveStalePolicy) {
        this.serveStalePolicy = serveStalePolicy != null
                ? serveStalePolicy : ServeStalePolicy.DISABLED;
    }

    public void setNxDomainCutPolicy(NxDomainCutPolicy nxDomainCutPolicy) {
        this.nxDomainCutPolicy = nxDomainCutPolicy != null
                ? nxDomainCutPolicy : NxDomainCutPolicy.DISABLED;
    }

    public void setMinimalAnyPolicy(MinimalAnyPolicy minimalAnyPolicy) {
        this.minimalAnyPolicy = minimalAnyPolicy != null
                ? minimalAnyPolicy : MinimalAnyPolicy.DISABLED;
    }

    public void setDnssecEnabled(boolean dnssecEnabled) {
        this.dnssecEnabled = dnssecEnabled;
    }

    public void setMetrics(DnsServerMetrics metrics) {
        this.metrics = metrics;
    }

    public boolean isDnssecEnabled() {
        return dnssecEnabled;
    }

    public DnsCache getCache() {
        return cache;
    }

    @Override
    public void start(Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
        start();
    }

    @Override
    public void start() {
        if (cacheEnabled) {
            cache = new DnsCache(DnsCache.DEFAULT_MAX_ENTRIES,
                    DnsCache.DEFAULT_NEGATIVE_TTL,
                    serveStaleEnabled ? staleRetentionSeconds : 0);
        }
        upstreamUdpFactory = new UdpTransportFactory();
        upstreamUdpFactory.start();
        upstreamTcpFactory = new TcpTransportFactory();
        upstreamTcpFactory.start();
        if (useSystemResolvers && upstreamServers.isEmpty()) {
            loadSystemResolvers();
        }
        if (upstreamServers.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.no_upstream_servers"));
        }
    }

    @Override
    public void stop() {
        if (cache != null) {
            cache.clear();
            cache = null;
        }
        upstreamUdpFactory = null;
        upstreamTcpFactory = null;
    }

    @Override
    public void handleQuery(final DnsMessage query, final SelectorLoop loop,
                            final DnsQueryCallback callback) {
        final DnsQuestion question = query.getQuestions().get(0);

        if (question.getType() == DnsType.ANY
                && minimalAnyPolicy.shouldReturnMinimalAny(question)) {
            callback.onResponse(MinimalAnyResponse.createResponse(query));
            return;
        }

        final boolean nxDomainCut =
                nxDomainCutPolicy.shouldApplyNxDomainCut(question);

        if (cacheEnabled && cache != null) {
            if (cache.isNegativelyCached(question.getName(), nxDomainCut)) {
                if (metrics != null) { metrics.cacheHit(); }
                callback.onResponse(query.createErrorResponse(
                        DnsMessage.RCODE_NXDOMAIN));
                return;
            }
            List<DnsResourceRecord> cached = cache.lookup(question);
            if (cached != null) {
                if (metrics != null) { metrics.cacheHit(); }
                callback.onResponse(query.createResponse(cached));
                return;
            }
            if (metrics != null) { metrics.cacheMiss(); }
        }

        proxyToUpstream(query, loop, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage upstreamResponse) {
                if (upstreamResponse == null) {
                    if (tryServeStale(query, question, loop, callback)) {
                        return;
                    }
                    callback.onResponse(query.createErrorResponse(
                            DnsMessage.RCODE_SERVFAIL));
                    return;
                }
                if (cacheEnabled && cache != null) {
                    if (upstreamResponse.getRcode() == DnsMessage.RCODE_NXDOMAIN) {
                        cache.cacheNegative(question.getName(),
                                upstreamResponse.getAuthorities());
                    } else if (!upstreamResponse.getAnswers().isEmpty()) {
                        cache.cache(question, upstreamResponse.getAnswers());
                    }
                }
                callback.onResponse(upstreamResponse);
            }

            @Override
            public void onError(String error) {
                if (tryServeStale(query, question, loop, callback)) {
                    return;
                }
                callback.onResponse(query.createErrorResponse(
                        DnsMessage.RCODE_SERVFAIL));
            }
        });
    }

    /**
     * RFC 8767: on upstream failure, return a stale cache entry when policy
     * allows and schedule a background refresh.
     *
     * @return {@code true} if {@code callback} was invoked with a stale answer
     */
    private boolean tryServeStale(final DnsMessage query,
                                  final DnsQuestion question,
                                  final SelectorLoop loop,
                                  final DnsQueryCallback callback) {
        if (!serveStaleEnabled || !cacheEnabled || cache == null
                || serveStalePolicy == ServeStalePolicy.DISABLED) {
            return false;
        }
        DnsCache.StaleHit staleHit =
                cache.lookupStale(question, staleAnswerTtl);
        boolean nxDomainCut =
                nxDomainCutPolicy.shouldApplyNxDomainCut(question);
        if (staleHit == null
                && cache.lookupStaleNegative(question.getName(), nxDomainCut)) {
            staleHit = DnsCache.StaleHit.negativeHit();
        }
        if (staleHit == null
                || !serveStalePolicy.shouldServeStale(question, staleHit)) {
            return false;
        }
        if (metrics != null) {
            metrics.cacheStaleServed();
        }
        if (staleHit.negative) {
            callback.onResponse(query.createErrorResponse(
                    DnsMessage.RCODE_NXDOMAIN));
        } else {
            callback.onResponse(query.createResponse(staleHit.records));
        }
        scheduleBackgroundRefresh(query, loop);
        return true;
    }

    private void scheduleBackgroundRefresh(final DnsMessage query,
                                           final SelectorLoop loop) {
        proxyToUpstream(query, loop, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage upstreamResponse) {
                if (upstreamResponse == null || !cacheEnabled || cache == null) {
                    return;
                }
                DnsQuestion question = query.getQuestions().get(0);
                if (upstreamResponse.getRcode() == DnsMessage.RCODE_NXDOMAIN) {
                    cache.cacheNegative(question.getName(),
                            upstreamResponse.getAuthorities());
                } else if (!upstreamResponse.getAnswers().isEmpty()) {
                    cache.cache(question, upstreamResponse.getAnswers());
                }
            }

            @Override
            public void onError(String error) {
                // Best-effort refresh; ignore.
            }
        });
    }

    private void proxyToUpstream(DnsMessage query, SelectorLoop loop,
                                 DnsQueryCallback callback) {
        if (upstreamServers.isEmpty()) {
            callback.onResponse(null);
            return;
        }

        int upstreamId = DnsQueryIdGenerator.allocateSynthetic();
        // RFC 6891 section 6.1.1: add OPT record to advertise EDNS0
        // support and signal our UDP payload size to the upstream.
        List<DnsResourceRecord> additionals =
                new ArrayList<>(query.getAdditionals());
        boolean hasOpt = false;
        for (DnsResourceRecord rr : additionals) {
            if (rr.getType() == DnsType.OPT) {
                hasOpt = true;
                break;
            }
        }
        if (!hasOpt) {
            // RFC 4035 section 3.2.1: set DO bit to request DNSSEC records
            int ednsFlags = dnssecEnabled
                    ? DnsResourceRecord.EDNS_FLAG_DO : 0;
            additionals.add(DnsResourceRecord.opt(
                    MAX_DNS_MESSAGE_SIZE, ednsFlags, new byte[0]));
        }
        DnsMessage upstreamQuery = new DnsMessage(
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
            // to a worker loop, the same way SmtpClient/HttpClient do
            // for outbound connections with no inherited loop.
            if (gumdrop == null) {
                throw new IllegalStateException(
                        "UpstreamRelayHandler.start(Gumdrop) was never called");
            }
            effectiveLoop = gumdrop.nextWorkerLoop();
        }

        tryUpstreamServer(0, query, upstreamQuery, queryData, upstreamId,
                effectiveLoop, callback);
    }

    private void tryUpstreamServer(int index, DnsMessage originalQuery,
                                   DnsMessage upstreamQuery, byte[] queryData,
                                   int upstreamId, SelectorLoop loop,
                                   DnsQueryCallback callback) {
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
        private final DnsMessage originalQuery;
        private final DnsMessage upstreamQuery;
        private final byte[] queryData;
        private final int upstreamId;
        private final SelectorLoop loop;
        private final DnsQueryCallback callback;
        private final long startNanos;

        private Endpoint endpoint;
        private TimerHandle timeoutTimer;
        private boolean done;

        UpstreamAttempt(int index, DnsMessage originalQuery, DnsMessage upstreamQuery,
                        byte[] queryData, int upstreamId, SelectorLoop loop,
                        DnsQueryCallback callback) {
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
                        gumdrop, upstream.getAddress(), upstream.getPort(), this, loop);
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
        // UdpTransportFactory.connect), so the kernel already discards
        // any datagram not from that address:port before it ever
        // reaches receive().
        @Override
        public void receive(ByteBuffer data) {
            if (done) {
                return;
            }
            DnsMessage response;
            try {
                response = DnsMessage.parse(data);
            } catch (DnsFormatException e) {
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
                final DnsMessage udpResponse = response;
                new TcpRetry(upstream, queryData, upstreamId, loop,
                        new TcpRetryCallback() {
                            @Override
                            public void onResult(DnsMessage tcpResponse) {
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

        private void succeed(DnsMessage response) {
            if (metrics != null) {
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                metrics.upstreamQuery(durationMs);
            }
            DnsMessage finalResponse = new DnsMessage(
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
        void onResult(DnsMessage response);
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
                        gumdrop, upstream.getAddress(), upstream.getPort(), this, loop);
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
                DnsMessage response = DnsMessage.parse(ByteBuffer.wrap(respData));
                if (response.getId() != expectedId) {
                    finish(null, "TCP fallback to " + upstream
                            + " returned a mismatched response ID", null);
                    return;
                }
                finish(response, null, null);
            } catch (DnsFormatException e) {
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

        private void finish(DnsMessage response, String logMessage, Exception cause) {
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

    }

    public static final class Builder {
        private final UpstreamRelayHandler handler = new UpstreamRelayHandler();

        public Builder upstreamServers(String servers) {
            handler.setUpstreamServers(servers);
            return this;
        }

        public Builder useSystemResolvers(boolean use) {
            handler.setUseSystemResolvers(use);
            return this;
        }

        public Builder cacheEnabled(boolean enabled) {
            handler.setCacheEnabled(enabled);
            return this;
        }

        public Builder serveStaleEnabled(boolean enabled) {
            handler.setServeStaleEnabled(enabled);
            return this;
        }

        public Builder staleRetentionSeconds(int seconds) {
            handler.setStaleRetentionSeconds(seconds);
            return this;
        }

        public Builder staleAnswerTtl(int seconds) {
            handler.setStaleAnswerTtl(seconds);
            return this;
        }

        public Builder serveStalePolicy(ServeStalePolicy policy) {
            handler.setServeStalePolicy(policy);
            return this;
        }

        public Builder nxDomainCutPolicy(NxDomainCutPolicy policy) {
            handler.setNxDomainCutPolicy(policy);
            return this;
        }

        public Builder minimalAnyPolicy(MinimalAnyPolicy policy) {
            handler.setMinimalAnyPolicy(policy);
            return this;
        }

        public Builder dnssecEnabled(boolean enabled) {
            handler.setDnssecEnabled(enabled);
            return this;
        }

        public UpstreamRelayHandler build() {
            return handler;
        }
    }
}
