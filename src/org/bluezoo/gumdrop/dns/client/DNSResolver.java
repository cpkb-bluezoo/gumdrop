/*
 * DNSResolver.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DNSCache;
import org.bluezoo.gumdrop.dns.DNSClass;
import org.bluezoo.gumdrop.dns.DNSCookie;
import org.bluezoo.gumdrop.dns.DNSFormatException;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSQuestion;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSSECChainValidator;
import org.bluezoo.gumdrop.dns.DNSSECStatus;
import org.bluezoo.gumdrop.dns.DNSSECTrustAnchor;
import org.bluezoo.gumdrop.dns.DNSSECValidationCallback;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.GumdropNative;

/**
 * Asynchronous DNS stub resolver using non-blocking I/O.
 * RFC 1035 section 7: resolver implementation. This is a stub resolver
 * that forwards queries to configured recursive servers with RD (Recursion
 * Desired) set (section 4.1.1).
 *
 * <p>Key RFC 1035 behaviors:
 * <ul>
 * <li>Section 7.2: queries are sent to multiple servers with timeout/retry</li>
 * <li>Section 7.3: responses are matched by Message ID</li>
 * <li>Section 7.4: responses are cached using TTL</li>
 * <li>Section 4.2.1: truncated UDP responses trigger TCP retry</li>
 * </ul>
 *
 * <p>RFC 1034 section 3.6.2: CNAME records are chased up to a
 * configurable depth limit.
 *
 * <p>The transport used for DNS communication is pluggable via
 * {@link DNSClientTransport}. By default, plain UDP is used
 * ({@link UDPDNSClientTransport}).
 *
 * <p>SelectorLoop affinity: when used inside a Gumdrop service (e.g. from an
 * HTTP or SMTP handler), call {@link #setSelectorLoop(SelectorLoop)} with
 * the endpoint's SelectorLoop so that DNS callbacks run on the same thread
 * and avoid cross-thread coordination.
 *
 * <p>Example usage:
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.addServer("8.8.8.8");
 * resolver.open();
 *
 * resolver.queryTXT("_dmarc.example.com", new DNSQueryCallback() {
 *     &#64;Override
 *     public void onResponse(DNSMessage response) {
 *         for (DNSResourceRecord rr : response.getAnswers()) {
 *             if (rr.getType() == DNSType.TXT) {
 *                 String txt = rr.getTxtData();
 *                 // Process TXT record...
 *             }
 *         }
 *     }
 *
 *     &#64;Override
 *     public void onError(String error) {
 *         // Handle timeout or error
 *     }
 * });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSQueryCallback
 * @see DNSClientTransport
 */
public class DNSResolver {

    private static final Logger LOGGER = Logger.getLogger(DNSResolver.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    // RFC 1034 section 3.6.2: limit CNAME chain depth to prevent loops
    private static final int MAX_CNAME_DEPTH = 8;

    private static final Map<SelectorLoop, DNSResolver> resolvers =
            new ConcurrentHashMap<>();
    private static volatile DNSCache sharedCache = new DNSCache();
    private static volatile boolean defaultDnssecEnabled;

    /**
     * Sets the default DNSSEC enablement for resolvers created by
     * {@link #forLoop(SelectorLoop)}.
     *
     * @param enabled true to enable DNSSEC by default
     */
    public static void setDefaultDnssecEnabled(boolean enabled) {
        defaultDnssecEnabled = enabled;
    }

    /**
     * Returns a resolver bound to the given SelectorLoop, creating one
     * lazily if needed.
     *
     * <p>The returned resolver uses system nameservers and has its
     * SelectorLoop set so that callbacks run on the loop's thread.
     *
     * @param loop the SelectorLoop
     * @return the resolver for this loop
     */
    public static DNSResolver forLoop(SelectorLoop loop) {
        DNSResolver existing = resolvers.get(loop);
        if (existing != null) {
            return existing;
        }
        DNSResolver r = new DNSResolver();
        r.setSelectorLoop(loop);
        r.setDnssecEnabled(defaultDnssecEnabled);
        r.useSystemResolvers();
        try {
            r.open();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open resolver for loop", e);
            return r;
        }
        DNSResolver race = resolvers.putIfAbsent(loop, r);
        if (race != null) {
            r.close();
            return race;
        }
        return r;
    }

    /**
     * Removes and closes the resolver associated with the given
     * SelectorLoop.
     *
     * <p>Call this when a SelectorLoop is being shut down to prevent
     * resource leaks.
     *
     * @param loop the SelectorLoop being shut down
     */
    public static void removeForLoop(SelectorLoop loop) {
        DNSResolver r = resolvers.remove(loop);
        if (r != null) {
            r.close();
        }
    }

    /**
     * Sets the shared DNS cache used by all resolver instances.
     *
     * <p>By default a cache with default settings is used. Call this
     * method to provide a custom cache (e.g., with different max entries
     * or negative TTL), or for testing.
     *
     * @param cache the cache to use, or null to disable caching
     */
    public static void setCache(DNSCache cache) {
        sharedCache = cache;
    }

    /**
     * Returns the shared DNS cache.
     *
     * @return the shared cache, or null if caching is disabled
     */
    public static DNSCache getCache() {
        return sharedCache;
    }

    private final List<InetSocketAddress> servers;
    private final Map<Integer, PendingQuery> pendingQueries;
    private final AtomicInteger queryIdGenerator;
    private final List<DNSClientTransport> transports;

    private DNSClientTransport transportPrototype;
    private long timeoutMs;
    private boolean opened;
    private SelectorLoop selectorLoop;

    /** RFC 7873: DNS cookie manager for source address verification. */
    private final DNSCookie dnsCookie = new DNSCookie();

    /** RFC 4035: when true, set the DO bit and validate responses. */
    private boolean dnssecEnabled;
    private DNSSECChainValidator chainValidator;
    private DNSSECTrustAnchor trustAnchor;

    /**
     * Creates a new DNS resolver with no servers configured.
     * Call {@link #addServer(String)} or {@link #addServer(InetAddress, int)}
     * to add DNS servers before opening.
     */
    public DNSResolver() {
        this.servers = new ArrayList<>();
        this.pendingQueries = new ConcurrentHashMap<>();
        this.queryIdGenerator = new AtomicInteger(0);
        this.transports = new ArrayList<>();
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
        this.opened = false;
    }

    // -- Configuration --

    /**
     * Sets the transport implementation to use for DNS communication.
     *
     * <p>Must be called before {@link #open()}. If not called,
     * {@link UDPDNSClientTransport} is used by default.
     *
     * @param transport the transport prototype to use for each server
     */
    public void setTransport(DNSClientTransport transport) {
        this.transportPrototype = transport;
    }

    /**
     * Adds a DNS server by hostname or IP address.
     * Uses the default DNS port (53).
     *
     * <p>This method should be called during configuration, before
     * {@link #open()}. For IP address strings (e.g., "8.8.8.8"),
     * no network lookup is performed. For hostnames, a blocking DNS
     * lookup occurs.
     *
     * @param server the server hostname or IP address
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public void addServer(String server) throws UnknownHostException {
        addServer(InetAddress.getByName(server), DEFAULT_PORT);
    }

    /**
     * Adds a DNS server by hostname or IP address with a specific port.
     *
     * <p>This method should be called during configuration, before
     * {@link #open()}. For IP address strings, no network lookup is
     * performed. For hostnames, a blocking DNS lookup occurs.
     *
     * @param server the server hostname or IP address
     * @param port the port number
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public void addServer(String server, int port) throws UnknownHostException {
        addServer(InetAddress.getByName(server), port);
    }

    /**
     * Adds a DNS server by InetAddress.
     *
     * <p>This is the preferred method when you already have the address,
     * as it avoids any potential blocking DNS lookup.
     *
     * @param address the server address
     * @param port the port number
     */
    public void addServer(InetAddress address, int port) {
        servers.add(new InetSocketAddress(address, port));
    }

    /**
     * Sets the query timeout in milliseconds.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the SelectorLoop used for resolver I/O, or null if not set.
     *
     * @return the SelectorLoop, or null
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    /**
     * Enables or disables DNSSEC validation.
     * RFC 4035 section 3.2.1: when enabled, the DO bit is set in
     * outgoing queries and responses are validated via the chain of
     * trust before delivery.
     *
     * <p>Must be called before {@link #open()}.
     *
     * @param enabled true to enable DNSSEC validation
     */
    public void setDnssecEnabled(boolean enabled) {
        this.dnssecEnabled = enabled;
    }

    /**
     * Returns true if DNSSEC validation is enabled.
     *
     * @return true if DNSSEC is enabled
     */
    public boolean isDnssecEnabled() {
        return dnssecEnabled;
    }

    /**
     * Sets a custom trust anchor store. If not set and DNSSEC is
     * enabled, a default store with the IANA root anchors is used.
     *
     * @param trustAnchor the trust anchor store
     */
    public void setTrustAnchor(DNSSECTrustAnchor trustAnchor) {
        this.trustAnchor = trustAnchor;
    }

    /**
     * Sets the SelectorLoop for resolver I/O. When set, all DNS client
     * connections and timers use this loop, so callbacks are invoked on
     * the same thread. Use this when the resolver is used from a Gumdrop
     * service (e.g. HTTP or SMTP handler) to avoid cross-thread coordination.
     *
     * <p>Must be called before {@link #open()}.
     *
     * @param loop the SelectorLoop, or null to use a Gumdrop worker loop
     */
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    /**
     * Adds the system's default DNS resolvers.
     *
     * <p>Uses a native system call to discover the platform's configured
     * nameservers. Falls back to well-known public resolvers (8.8.8.8
     * and 1.1.1.1) if the native call fails or returns no servers.
     */
    public void useSystemResolvers() {
        try {
            String[] nameservers =
                    GumdropNative.getSystemNameservers();
            if (nameservers != null) {
                for (String ns : nameservers) {
                    try {
                        addServer(ns);
                    } catch (UnknownHostException e) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("Skipping invalid system "
                                    + "nameserver: " + ns);
                        }
                    }
                }
            }
        } catch (UnsatisfiedLinkError e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Native nameserver discovery "
                        + "unavailable: " + e.getMessage());
            }
        }
        if (servers.isEmpty()) {
            try {
                addServer("8.8.8.8");
                addServer("1.1.1.1");
            } catch (UnknownHostException e) {
                // Should not happen for IP address literals
            }
        }
    }

    // -- Lifecycle --

    /**
     * Opens the resolver by creating transport connections to all
     * configured servers.
     *
     * @throws IOException if a connection cannot be opened
     */
    public void open() throws IOException {
        if (opened) {
            return;
        }
        if (servers.isEmpty()) {
            throw new IOException(L10N.getString("err.no_dns_servers"));
        }
        if (dnssecEnabled) {
            if (trustAnchor == null) {
                trustAnchor = new DNSSECTrustAnchor();
            }
            chainValidator = new DNSSECChainValidator(this, trustAnchor);
        }
        TransportCallback callback = new TransportCallback();
        for (InetSocketAddress server : servers) {
            DNSClientTransport transport = createTransport();
            transport.open(server.getAddress(), server.getPort(),
                    selectorLoop, callback);
            transports.add(transport);
        }
        opened = true;
    }

    /**
     * Closes the resolver and all transport connections.
     */
    public void close() {
        if (!opened) {
            return;
        }
        for (PendingQuery pending : pendingQueries.values()) {
            if (pending.timeoutHandle != null) {
                pending.timeoutHandle.cancel();
            }
            pending.callback.onError(L10N.getString("err.resolver.closed"));
        }
        pendingQueries.clear();
        for (DNSClientTransport transport : transports) {
            transport.close();
        }
        transports.clear();
        opened = false;
    }

    // -- Query Methods --

    /**
     * Queries for TXT records.
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryTXT(String name, DNSQueryCallback callback) {
        query(name, DNSType.TXT, callback);
    }

    /**
     * Queries for A records (IPv4 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryA(String name, DNSQueryCallback callback) {
        query(name, DNSType.A, callback);
    }

    /**
     * Queries for AAAA records (IPv6 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryAAAA(String name, DNSQueryCallback callback) {
        query(name, DNSType.AAAA, callback);
    }

    /**
     * Queries for MX records (mail exchangers).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryMX(String name, DNSQueryCallback callback) {
        query(name, DNSType.MX, callback);
    }

    /**
     * Queries for PTR records (reverse DNS).
     *
     * @param name the domain name to query (e.g., "1.0.0.127.in-addr.arpa")
     * @param callback the callback to receive results
     */
    public void queryPTR(String name, DNSQueryCallback callback) {
        query(name, DNSType.PTR, callback);
    }

    /**
     * Performs a DNS query with the specified type.
     * RFC 1035 section 7.2: if the first server times out, the query is
     * automatically retried on the next configured server, up to one
     * attempt per server.
     *
     * @param name the domain name to query
     * @param type the record type to query
     * @param callback the callback to receive results
     */
    public void query(String name, DNSType type, DNSQueryCallback callback) {
        query(name, type, callback, 0);
    }

    /**
     * Performs an SRV record query.
     * RFC 2782: SRV records provide service location (host + port)
     * with priority and weight for load balancing.
     *
     * @param name the service name (e.g. _sip._tcp.example.com)
     * @param callback the callback to receive results
     */
    public void querySRV(String name, DNSQueryCallback callback) {
        query(name, DNSType.SRV, callback);
    }

    // -- High-Level Resolution --

    /**
     * Resolves a hostname to IP addresses by issuing parallel A and AAAA
     * queries.
     * RFC 1035 section 7.1: transform user request into queries.
     * RFC 8305 (Happy Eyeballs v2): IPv6 addresses are returned before
     * IPv4 for dual-stack readiness.
     *
     * <p>Resolution order:
     * <ol>
     * <li>Check the local hosts file ({@link HostsFile})</li>
     * <li>Check the shared {@link DNSCache} (RFC 1035 section 7.4)</li>
     * <li>Issue A and AAAA queries in parallel</li>
     * </ol>
     *
     * <p>If one query type fails but the other succeeds, the successful
     * results are delivered. The error callback is invoked only if both
     * queries fail.
     *
     * @param hostname the hostname to resolve
     * @param callback the callback to receive results
     */
    public void resolve(String hostname, final ResolveCallback callback) {
        if (Boolean.getBoolean("gumdrop.dns.debug")) {
            LOGGER.info("[DNS] resolve(" + hostname + ")");
        }
        if (hostname == null || hostname.isEmpty()) {
            callback.onError("Empty hostname");
            return;
        }
        hostname = hostname.trim();

        // 1. Literal IP addresses: parse without any network or file I/O (non-blocking)
        InetAddress literalV4 = HostsFile.parseLiteralIPv4(hostname);
        if (literalV4 != null) {
            if (Boolean.getBoolean("gumdrop.dns.debug")) {
                LOGGER.info("[DNS] literal IPv4: " + literalV4);
            }
            deliverResolved(Collections.singletonList(literalV4), callback);
            return;
        }
        InetAddress literalV6 = HostsFile.parseLiteralIPv6(hostname);
        if (literalV6 != null) {
            if (Boolean.getBoolean("gumdrop.dns.debug")) {
                LOGGER.info("[DNS] literal IPv6: " + literalV6);
            }
            deliverResolved(Collections.singletonList(literalV6), callback);
            return;
        }

        // 2. Hosts file lookup (local file read, typically cached)
        List<InetAddress> hostsResult = HostsFile.lookup(hostname);
        if (hostsResult != null && !hostsResult.isEmpty()) {
            if (Boolean.getBoolean("gumdrop.dns.debug")) {
                LOGGER.info("[DNS] hosts file: " + hostsResult);
            }
            deliverResolved(hostsResult, callback);
            return;
        }

        // 3. Built-in fallback for "localhost" when hosts file has no entry
        if ("localhost".equalsIgnoreCase(hostname) || "localhost.".equalsIgnoreCase(hostname)) {
            List<InetAddress> localhost = new ArrayList<>(2);
            try {
                localhost.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
                byte[] v6loopback = new byte[16];
                v6loopback[15] = 1;
                localhost.add(InetAddress.getByAddress(v6loopback));
            } catch (UnknownHostException e) {
                try {
                    localhost.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
                } catch (UnknownHostException e2) {
                    // Fall through to DNS query
                }
            }
            if (!localhost.isEmpty()) {
                if (Boolean.getBoolean("gumdrop.dns.debug")) {
                    LOGGER.info("[DNS] built-in localhost: " + localhost);
                }
                deliverResolved(localhost, callback);
                return;
            }
        }

        // 4. DNS query (async)
        if (Boolean.getBoolean("gumdrop.dns.debug")) {
            LOGGER.info("[DNS] falling through to query A/AAAA for " + hostname);
        }
        final DualQueryCollector collector =
                new DualQueryCollector(callback);
        queryAAAA(hostname, collector.v6Callback);
        queryA(hostname, collector.v4Callback);
    }

    private void deliverResolved(final List<InetAddress> result,
                                 final ResolveCallback callback) {
        if (Boolean.getBoolean("gumdrop.dns.debug")) {
            LOGGER.info("[DNS] deliverResolved " + result + " (selectorLoop=" + (selectorLoop != null) + ")");
        }
        if (selectorLoop != null) {
            selectorLoop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (Boolean.getBoolean("gumdrop.dns.debug")) {
                        LOGGER.info("[DNS] invoking onResolved callback");
                    }
                    callback.onResolved(result);
                }
            });
        } else {
            callback.onResolved(result);
        }
    }

    private void query(String name, DNSType type,
                       final DNSQueryCallback callback, int cnameDepth) {
        if (!opened) {
            callback.onError(L10N.getString("err.resolver_not_opened"));
            return;
        }
        if (transports.isEmpty()) {
            callback.onError(L10N.getString("err.no_dns_servers"));
            return;
        }
        final DNSQuestion question = new DNSQuestion(name, type, DNSClass.IN);
        DNSCache cache = sharedCache;
        if (cache != null) {
            if (cache.isNegativelyCached(name)) {
                deliverCachedNxdomain(name, type, callback);
                return;
            }
            List<DNSResourceRecord> cached = cache.lookup(question);
            if (cached != null) {
                deliverCachedResponse(name, type, cached, callback);
                return;
            }
        }
        int queryId = queryIdGenerator.getAndIncrement() & 0xFFFF;
        List<DNSQuestion> questions = new ArrayList<>();
        questions.add(question);
        // RFC 6891 section 6.1.1: include OPT pseudo-record to signal
        // EDNS0 support and advertise UDP payload size.
        // RFC 7873: include DNS cookie in the OPT record.
        // RFC 4035 section 3.2.1: set DO bit when DNSSEC is enabled.
        List<DNSResourceRecord> additionals = new ArrayList<>();
        String serverAddr = servers.isEmpty() ? "" :
                servers.get(0).getAddress().getHostAddress();
        byte[] cookieOption = dnsCookie.buildCookieOption(serverAddr);
        int ednsFlags = dnssecEnabled
                ? DNSResourceRecord.EDNS_FLAG_DO : 0;
        additionals.add(DNSResourceRecord.opt(
                DNSMessage.DEFAULT_EDNS_UDP_SIZE, ednsFlags,
                cookieOption));
        // RFC 1035 section 4.1.1: set RD to request recursive resolution
        int flags = DNSMessage.FLAG_RD;
        DNSMessage queryMsg = new DNSMessage(
                queryId,
                flags,
                questions,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                additionals
        );
        ByteBuffer serialized = queryMsg.serialize();
        long expiry = System.currentTimeMillis() + timeoutMs;
        final PendingQuery pending =
                new PendingQuery(queryId, name, type, callback, expiry,
                        0, serialized, cnameDepth);
        pendingQueries.put(queryId, pending);
        sendToServer(pending);
    }

    // -- Internal Methods --

    private void deliverCachedResponse(final String name, final DNSType type,
                                       final List<DNSResourceRecord> records,
                                       final DNSQueryCallback callback) {
        int syntheticId = queryIdGenerator.getAndIncrement() & 0xFFFF;
        List<DNSQuestion> questions = new ArrayList<>();
        questions.add(new DNSQuestion(name, type, DNSClass.IN));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA;
        final DNSMessage response = new DNSMessage(
                syntheticId, flags, questions, records,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList()
        );
        if (selectorLoop != null) {
            selectorLoop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    callback.onResponse(response);
                }
            });
        } else {
            callback.onResponse(response);
        }
    }

    private void deliverCachedNxdomain(final String name, final DNSType type,
                                       final DNSQueryCallback callback) {
        int syntheticId = queryIdGenerator.getAndIncrement() & 0xFFFF;
        List<DNSQuestion> questions = new ArrayList<>();
        questions.add(new DNSQuestion(name, type, DNSClass.IN));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA
                | DNSMessage.RCODE_NXDOMAIN;
        final DNSMessage response = new DNSMessage(
                syntheticId, flags, questions,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList()
        );
        if (selectorLoop != null) {
            selectorLoop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    callback.onResponse(response);
                }
            });
        } else {
            callback.onResponse(response);
        }
    }

    private void cacheResponse(DNSMessage response) {
        DNSCache cache = sharedCache;
        if (cache == null) {
            return;
        }
        List<DNSQuestion> questions = response.getQuestions();
        if (questions.isEmpty()) {
            return;
        }
        DNSQuestion question = questions.get(0);
        if (response.getRcode() == DNSMessage.RCODE_NXDOMAIN) {
            cache.cacheNegative(question.getName(),
                    response.getAuthorities());
        } else if (response.getRcode() == DNSMessage.RCODE_NOERROR) {
            List<DNSResourceRecord> answers = response.getAnswers();
            if (!answers.isEmpty()) {
                cache.cache(question, answers);
            }
        }
    }

    private boolean shouldChaseCname(PendingQuery pending, DNSMessage response) {
        if (pending.type == DNSType.CNAME) {
            return false;
        }
        List<DNSResourceRecord> answers = response.getAnswers();
        if (answers.isEmpty()) {
            return false;
        }
        boolean hasCname = false;
        boolean hasRequestedType = false;
        for (DNSResourceRecord rr : answers) {
            if (rr.getType() == DNSType.CNAME) {
                hasCname = true;
            } else if (rr.getType() == pending.type) {
                hasRequestedType = true;
            }
        }
        return hasCname && !hasRequestedType;
    }

    private String extractCname(DNSMessage response) {
        for (DNSResourceRecord rr : response.getAnswers()) {
            if (rr.getType() == DNSType.CNAME) {
                return rr.getTargetName();
            }
        }
        return null;
    }

    private void sendToServer(final PendingQuery pending) {
        DNSClientTransport transport = transports.get(pending.serverIndex);
        pending.timeoutHandle = transport.scheduleTimer(timeoutMs,
                new Runnable() {
                    @Override
                    public void run() {
                        handleTimeout(pending.queryId);
                    }
                });
        pending.queryData.rewind();
        transport.send(pending.queryData);
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(
                    L10N.getString("debug.sent_query"),
                    pending.name, pending.type, pending.queryId);
            LOGGER.fine(msg);
        }
    }

    private DNSClientTransport createTransport() {
        if (transportPrototype != null) {
            return transportPrototype;
        }
        return new UDPDNSClientTransport();
    }

    // RFC 1035 section 7.3: match response to query by Message ID
    private void handleResponse(DNSMessage response) {
        int queryId = response.getId();
        PendingQuery pending = pendingQueries.remove(queryId);
        if (pending == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String msg = MessageFormat.format(
                        L10N.getString("debug.received_response_unknown"),
                        queryId);
                LOGGER.fine(msg);
            }
            return;
        }
        // RFC 7873: extract and cache server cookie from the response
        processResponseCookies(response, pending);
        if (pending.timeoutHandle != null) {
            pending.timeoutHandle.cancel();
        }
        // RFC 1035 section 4.2.1: if TC bit set, retry query over TCP
        if (response.isTruncated()) {
            InetSocketAddress server = servers.get(pending.serverIndex);
            retryOverTcpAsync(pending, server, response);
            return;
        }
        deliverResponse(pending, response);
    }

    /**
     * RFC 7873: extracts the server cookie from a response's OPT record
     * and caches it for use in subsequent queries.
     */
    private void processResponseCookies(DNSMessage response,
                                         PendingQuery pending) {
        for (Object obj : response.getAdditionals()) {
            DNSResourceRecord rr = (DNSResourceRecord) obj;
            if (rr.getType() == DNSType.OPT) {
                byte[] cookieData = DNSCookie.findEdnsOption(
                        rr.getRData(), DNSCookie.EDNS_OPTION_COOKIE);
                if (cookieData != null) {
                    String serverAddr = servers.get(
                            pending.serverIndex).getAddress()
                            .getHostAddress();
                    dnsCookie.processResponseCookie(
                            serverAddr, cookieData);
                }
                break;
            }
        }
    }

    private void deliverResponse(PendingQuery pending, DNSMessage response) {
        cacheResponse(response);
        if (shouldChaseCname(pending, response)) {
            String cname = extractCname(response);
            if (cname != null && pending.cnameDepth < MAX_CNAME_DEPTH) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            "Following CNAME {0} -> {1} (depth {2})",
                            pending.name, cname, pending.cnameDepth + 1));
                }
                query(cname, pending.type, pending.callback,
                        pending.cnameDepth + 1);
                return;
            }
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            String msg = MessageFormat.format(
                    L10N.getString("debug.received_response"),
                    pending.name, response.getAnswers().size());
            LOGGER.fine(msg);
        }
        if (dnssecEnabled && chainValidator != null) {
            final DNSQueryCallback cb = pending.callback;
            final DNSMessage resp = response;
            chainValidator.validate(response,
                    new DNSSECValidationCallback() {
                        @Override
                        public void onValidated(DNSSECStatus status,
                                                DNSMessage validated) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine("DNSSEC status: " + status);
                            }
                            cb.onResponse(validated);
                        }
                    });
        } else {
            pending.callback.onResponse(response);
        }
    }

    private void handleTimeout(int queryId) {
        PendingQuery pending = pendingQueries.get(queryId);
        if (pending == null) {
            return;
        }
        int nextIndex = pending.serverIndex + 1;
        if (nextIndex < transports.size()) {
            pending.serverIndex = nextIndex;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        "Retrying query {0} on server {1}",
                        pending.name, nextIndex));
            }
            sendToServer(pending);
        } else {
            pendingQueries.remove(queryId);
            String msg = MessageFormat.format(
                    L10N.getString("err.query_timeout"), pending.name);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(msg);
            }
            pending.callback.onError(msg);
        }
    }

    private static final int TCP_TIMEOUT_MS = 5000;

    DNSClientTransport createTcpRetryTransport() {
        return new TCPDNSClientTransport();
    }

    private void retryOverTcpAsync(final PendingQuery pending,
                                   InetSocketAddress server,
                                   final DNSMessage truncatedResponse) {
        try {
            final DNSClientTransport tcpTransport =
                    createTcpRetryTransport();
            TcpRetryHandler handler = new TcpRetryHandler(
                    pending, truncatedResponse, tcpTransport);
            tcpTransport.open(server.getAddress(), server.getPort(),
                    selectorLoop, handler);
            handler.timeoutHandle = tcpTransport.scheduleTimer(
                    TCP_TIMEOUT_MS, handler::onTimeout);
            pending.queryData.rewind();
            tcpTransport.send(pending.queryData);
        } catch (IOException e) {
            LOGGER.log(Level.FINE,
                    "TCP retry failed for " + pending.name, e);
            deliverResponse(pending, truncatedResponse);
        }
    }

    private class TcpRetryHandler implements DNSClientTransportHandler {

        private final PendingQuery pending;
        private final DNSMessage truncatedResponse;
        private final DNSClientTransport transport;
        TimerHandle timeoutHandle;
        private boolean completed;

        TcpRetryHandler(PendingQuery pending,
                        DNSMessage truncatedResponse,
                        DNSClientTransport transport) {
            this.pending = pending;
            this.truncatedResponse = truncatedResponse;
            this.transport = transport;
        }

        @Override
        public void onReceive(ByteBuffer data) {
            if (completed) {
                return;
            }
            completed = true;
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            try {
                DNSMessage tcpResponse = DNSMessage.parse(data);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            "TCP retry for {0} succeeded ({1} answers)",
                            pending.name,
                            tcpResponse.getAnswers().size()));
                }
                deliverResponse(pending, tcpResponse);
            } catch (DNSFormatException e) {
                LOGGER.log(Level.WARNING,
                        "TCP retry parse error for " + pending.name, e);
                deliverResponse(pending, truncatedResponse);
            }
            transport.close();
        }

        @Override
        public void onError(Exception cause) {
            if (completed) {
                return;
            }
            completed = true;
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            LOGGER.log(Level.FINE,
                    "TCP retry failed for " + pending.name, cause);
            deliverResponse(pending, truncatedResponse);
            transport.close();
        }

        void onTimeout() {
            if (completed) {
                return;
            }
            completed = true;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("TCP retry timed out for " + pending.name);
            }
            deliverResponse(pending, truncatedResponse);
            transport.close();
        }
    }

    // -- Inner Classes --

    private static class PendingQuery {
        final int queryId;
        final String name;
        final DNSType type;
        final DNSQueryCallback callback;
        final long expiry;
        final ByteBuffer queryData;
        final int cnameDepth;
        int serverIndex;
        TimerHandle timeoutHandle;

        PendingQuery(int queryId, String name, DNSType type,
                     DNSQueryCallback callback, long expiry,
                     int serverIndex, ByteBuffer queryData,
                     int cnameDepth) {
            this.queryId = queryId;
            this.name = name;
            this.type = type;
            this.callback = callback;
            this.expiry = expiry;
            this.serverIndex = serverIndex;
            this.queryData = queryData;
            this.cnameDepth = cnameDepth;
        }
    }

    /**
     * Collects results from parallel A and AAAA queries for
     * {@link #resolve(String, ResolveCallback)}.
     */
    private static class DualQueryCollector {
        final ResolveCallback callback;
        final DNSQueryCallback v6Callback;
        final DNSQueryCallback v4Callback;
        private List<InetAddress> v6Addresses;
        private List<InetAddress> v4Addresses;
        private String v6Error;
        private String v4Error;
        private boolean v6Done;
        private boolean v4Done;

        DualQueryCollector(final ResolveCallback callback) {
            this.callback = callback;
            this.v6Callback = new DNSQueryCallback() {
                @Override
                public void onResponse(DNSMessage response) {
                    List<InetAddress> addrs = new ArrayList<>();
                    for (DNSResourceRecord rr : response.getAnswers()) {
                        if (rr.getType() == DNSType.AAAA) {
                            addrs.add(rr.getAddress());
                        }
                    }
                    synchronized (DualQueryCollector.this) {
                        v6Addresses = addrs;
                        v6Done = true;
                        checkComplete();
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (DualQueryCollector.this) {
                        v6Error = error;
                        v6Done = true;
                        checkComplete();
                    }
                }
            };
            this.v4Callback = new DNSQueryCallback() {
                @Override
                public void onResponse(DNSMessage response) {
                    List<InetAddress> addrs = new ArrayList<>();
                    for (DNSResourceRecord rr : response.getAnswers()) {
                        if (rr.getType() == DNSType.A) {
                            addrs.add(rr.getAddress());
                        }
                    }
                    synchronized (DualQueryCollector.this) {
                        v4Addresses = addrs;
                        v4Done = true;
                        checkComplete();
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (DualQueryCollector.this) {
                        v4Error = error;
                        v4Done = true;
                        checkComplete();
                    }
                }
            };
        }

        private void checkComplete() {
            if (!v6Done || !v4Done) {
                return;
            }
            List<InetAddress> combined = new ArrayList<>();
            if (v6Addresses != null) {
                combined.addAll(v6Addresses);
            }
            if (v4Addresses != null) {
                combined.addAll(v4Addresses);
            }
            if (combined.isEmpty()) {
                String error = v4Error != null ? v4Error : v6Error;
                callback.onError(error);
            } else {
                callback.onResolved(combined);
            }
        }
    }

    private class TransportCallback implements DNSClientTransportHandler {

        @Override
        public void onReceive(ByteBuffer data) {
            try {
                DNSMessage response = DNSMessage.parse(data);
                handleResponse(response);
            } catch (DNSFormatException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("err.malformed_response"), e);
            }
        }

        @Override
        public void onError(Exception cause) {
            LOGGER.log(Level.WARNING,
                    "DNS resolver transport error", cause);
        }
    }

}
