/*
 * DnsResolver.java
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsBailiwick;
import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsQueryIdGenerator;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsCookie;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsMultiQType;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnssecAwareQueryCallback;
import org.bluezoo.gumdrop.dns.DnssecChainValidator;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnssecTrustAnchor;
import org.bluezoo.gumdrop.dns.DnssecValidationCallback;
import org.bluezoo.gumdrop.dns.DnsType;

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
 * <p>The transport used for DNS communication is pluggable via {@link
 * DnsClientTransport} and {@link #setTransport}. Without an explicit
 * override, each configured server gets its own transport chosen by
 * descending preference -- RFC 9250 DoQ, then RFC 7858 DoT, then RFC
 * 8484 DoH, then plain UDP ({@link UdpDNSClientTransport}) -- based on
 * what {@link DNSServerCapabilityCache} already knows that server
 * supports (seeded for well-known public resolvers; otherwise plain
 * UDP, since most servers support none of the encrypted transports and
 * probing every configured server for them by default would add
 * connection-timeout latency to the common case).
 *
 * <p>SelectorLoop affinity: when used inside a Gumdrop service (e.g. from an
 * HTTP or SMTP handler), call {@link #setSelectorLoop(SelectorLoop)} with
 * the endpoint's SelectorLoop so that DNS callbacks run on the same thread
 * and avoid cross-thread coordination.
 *
 * <p>Example usage:
 * <pre><code>
 * DnsResolver resolver = new DnsResolver();
 * resolver.addServer("8.8.8.8");
 * resolver.open();
 *
 * resolver.queryTXT("_dmarc.example.com", new DnsQueryCallback() {
 *     &#64;Override
 *     public void onResponse(DnsMessage response) {
 *         for (DnsResourceRecord rr : response.getAnswers()) {
 *             if (rr.getType() == DnsType.TXT) {
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
 * @see DnsQueryCallback
 * @see DnsClientTransport
 */
public class DnsResolver {

    private static final Logger LOGGER = Logger.getLogger(DnsResolver.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private static final int DEFAULT_PORT = 53;
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    // RFC 1034 section 3.6.2: limit CNAME chain depth to prevent loops
    private static final int MAX_CNAME_DEPTH = 8;

    private static final Map<SelectorLoop, DnsResolver> resolvers =
            new ConcurrentHashMap<>();
    private static volatile DnsCache sharedCache = new DnsCache();
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
    public static DnsResolver forLoop(SelectorLoop loop) {
        DnsResolver existing = resolvers.get(loop);
        if (existing != null) {
            return existing;
        }
        DnsResolver r = new DnsResolver();
        r.setSelectorLoop(loop);
        r.setDnssecEnabled(defaultDnssecEnabled);
        r.useSystemResolvers();
        try {
            r.open();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open resolver for loop", e);
            return r;
        }
        DnsResolver race = resolvers.putIfAbsent(loop, r);
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
        DnsResolver r = resolvers.remove(loop);
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
    public static void setCache(DnsCache cache) {
        sharedCache = cache;
    }

    /**
     * Returns the shared DNS cache.
     *
     * @return the shared cache, or null if caching is disabled
     */
    public static DnsCache getCache() {
        return sharedCache;
    }

    private final List<InetSocketAddress> servers;
    private final Map<Integer, PendingQuery> pendingQueries;
    private final List<DnsClientTransport> transports;

    private DnsClientTransport transportPrototype;
    private long timeoutMs;
    private boolean opened;
    private SelectorLoop selectorLoop;

    /** RFC 7873: DNS cookie manager for source address verification. */
    private final DnsCookie dnsCookie = new DnsCookie();

    /** RFC 4035: when true, set the DO bit and validate responses. */
    private boolean dnssecEnabled;
    private DnssecChainValidator chainValidator;
    private DnssecTrustAnchor trustAnchor;

    /** RFC 9462: when true, opportunistically discover encrypted endpoints. */
    private boolean ddrEnabled;

    /**
     * Creates a new DNS resolver with no servers configured.
     * Call {@link #addServer(String)} or {@link #addServer(InetAddress, int)}
     * to add DNS servers before opening.
     */
    public DnsResolver() {
        this.servers = new ArrayList<>();
        this.pendingQueries = new ConcurrentHashMap<>();
        this.transports = new ArrayList<>();
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
        this.opened = false;
    }

    // -- Configuration --

    /**
     * Sets the transport implementation to use for DNS communication,
     * overriding automatic per-server transport preference/fallback
     * (see the class Javadoc) with this exact instance for every
     * configured server.
     *
     * <p>Must be called before {@link #open()}. If not called, each
     * server gets its own transport chosen automatically.
     *
     * @param transport the transport prototype to use for each server
     */
    public void setTransport(DnsClientTransport transport) {
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
     * Enables or disables RFC 9462 Discovery of Designated Resolvers (DDR).
     *
     * <p>When enabled, {@link #open()} opportunistically asks each
     * configured server whose encrypted transport support isn't already
     * known (i.e. it was opened on plain UDP by default, not because
     * {@link DNSServerCapabilityCache} already knew better) whether it
     * also offers an encrypted equivalent, via a single extra plaintext
     * SVCB query to {@code _dns.resolver.arpa} sent to that same
     * server. On success, the discovered capability is recorded for
     * future opens -- this resolver's and others', since the cache is
     * process-wide -- and this server's active transport is upgraded
     * immediately. A DDR failure, timeout, or malformed response is
     * silently ignored: DDR never blocks or fails ordinary resolution,
     * it just leaves the server on the plaintext transport it already
     * has.
     *
     * <p>Must be called before {@link #open()}. Disabled by default,
     * like {@link #setDnssecEnabled}, since it adds an extra query per
     * not-yet-known server; has no effect when a transport was
     * explicitly configured via {@link #setTransport}.
     *
     * @param enabled true to enable DDR discovery
     */
    public void setDdrEnabled(boolean enabled) {
        this.ddrEnabled = enabled;
    }

    /**
     * Returns true if RFC 9462 DDR discovery is enabled.
     *
     * @return true if DDR is enabled
     */
    public boolean isDdrEnabled() {
        return ddrEnabled;
    }

    /**
     * Sets a custom trust anchor store. If not set and DNSSEC is
     * enabled, a default store with the IANA root anchors is used.
     *
     * @param trustAnchor the trust anchor store
     */
    public void setTrustAnchor(DnssecTrustAnchor trustAnchor) {
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
     * <p>Discovers the platform's configured nameservers by parsing
     * {@code /etc/resolv.conf} (see {@link ResolvConf}). Falls back to
     * well-known public resolvers (8.8.8.8 and 1.1.1.1) if none are found
     * or none are valid -- {@link DNSServerCapabilityCache} knows these
     * addresses support DoQ/DoT/DoH, so (absent an explicit {@link
     * #setTransport} override) this fallback path prefers an encrypted
     * transport rather than landing on plain UDP.
     */
    public void useSystemResolvers() {
        for (String ns : ResolvConf.getNameservers()) {
            try {
                addServer(ns);
            } catch (UnknownHostException e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Skipping invalid system "
                            + "nameserver: " + ns);
                }
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
                trustAnchor = new DnssecTrustAnchor();
            }
            chainValidator = new DnssecChainValidator(this, trustAnchor);
        }
        for (int i = 0; i < servers.size(); i++) {
            InetSocketAddress server = servers.get(i);
            TransportCallback callback = new TransportCallback(i);
            transports.add(openBestTransport(server, callback));
            // callback.transportType is only ever PLAIN here when nothing
            // better was already known (an explicit setTransport override
            // never sets it at all, so this also naturally excludes that
            // case) -- exactly the condition worth spending one DDR query on.
            if (ddrEnabled && callback.transportType == DNSTransportType.PLAIN) {
                startDdrDiscovery(i, server);
            }
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
        for (DnsClientTransport transport : transports) {
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
    public void queryTXT(String name, DnsQueryCallback callback) {
        query(name, DnsType.TXT, callback);
    }

    /**
     * Queries for A records (IPv4 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryA(String name, DnsQueryCallback callback) {
        query(name, DnsType.A, callback);
    }

    /**
     * Queries for AAAA records (IPv6 addresses).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryAAAA(String name, DnsQueryCallback callback) {
        query(name, DnsType.AAAA, callback);
    }

    /**
     * Queries for MX records (mail exchangers).
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryMX(String name, DnsQueryCallback callback) {
        query(name, DnsType.MX, callback);
    }

    /**
     * Queries for PTR records (reverse DNS).
     *
     * @param name the domain name to query (e.g., "1.0.0.127.in-addr.arpa")
     * @param callback the callback to receive results
     */
    public void queryPTR(String name, DnsQueryCallback callback) {
        query(name, DnsType.PTR, callback);
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
    public void query(String name, DnsType type, DnsQueryCallback callback) {
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
    public void querySRV(String name, DnsQueryCallback callback) {
        query(name, DnsType.SRV, callback);
    }

    /**
     * Queries for HTTPS records.
     * RFC 9460: HTTPS records advertise, among other things, which ALPN
     * protocols (e.g. "h3", "h2") a host supports, allowing a client to
     * pick a transport before ever opening a connection.
     *
     * @param name the domain name to query
     * @param callback the callback to receive results
     */
    public void queryHTTPS(String name, DnsQueryCallback callback) {
        query(name, DnsType.HTTPS, callback);
    }

    /**
     * Queries for TLSA records (DANE).
     * RFC 6698: TLSA records bind a certificate or public key to a
     * domain name for a specific port and protocol, e.g.
     * {@code _25._tcp.mail.example.com}.
     *
     * <p>Use a {@link DnssecAwareQueryCallback} rather than a plain
     * {@link DnsQueryCallback} to find out whether the answer was
     * DNSSEC-validated -- RFC 7672 section 3.1.3 requires a TLSA
     * lookup to be ignored unless it came back
     * {@link org.bluezoo.gumdrop.dns.DnssecStatus#SECURE}.
     *
     * @param name the TLSA owner name (e.g. "_25._tcp.mail.example.com")
     * @param callback the callback to receive results
     */
    public void queryTLSA(String name, DnsQueryCallback callback) {
        query(name, DnsType.TLSA, callback);
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
     * <li>Check the shared {@link DnsCache} (RFC 1035 section 7.4)</li>
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
            LOGGER.info(MessageFormat.format(L10N.getString("info.dns_resolve"), hostname));
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
                LOGGER.info(MessageFormat.format(L10N.getString("info.dns_literal_v4"), literalV4));
            }
            deliverResolved(Collections.singletonList(literalV4), callback);
            return;
        }
        InetAddress literalV6 = HostsFile.parseLiteralIPv6(hostname);
        if (literalV6 != null) {
            if (Boolean.getBoolean("gumdrop.dns.debug")) {
                LOGGER.info(MessageFormat.format(L10N.getString("info.dns_literal_v6"), literalV6));
            }
            deliverResolved(Collections.singletonList(literalV6), callback);
            return;
        }

        // 2. Hosts file lookup (local file read, typically cached)
        List<InetAddress> hostsResult = HostsFile.lookup(hostname);
        if (hostsResult != null && !hostsResult.isEmpty()) {
            if (Boolean.getBoolean("gumdrop.dns.debug")) {
                LOGGER.info(MessageFormat.format(L10N.getString("info.dns_hosts_file"), hostsResult));
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
                    LOGGER.info(MessageFormat.format(L10N.getString("info.dns_builtin_localhost"), localhost));
                }
                deliverResolved(localhost, callback);
                return;
            }
        }

        // 4. DNS query (async): a single batched AAAA+A request (RFC
        // 10029 where the server supports it; two round trips joined
        // client-side otherwise -- queryBatch hides the difference).
        if (Boolean.getBoolean("gumdrop.dns.debug")) {
            LOGGER.info(MessageFormat.format(L10N.getString("info.dns_query_fallthrough"), hostname));
        }
        final String finalHostname = hostname;
        final List<InetAddress> v6Addresses = Collections.synchronizedList(new ArrayList<InetAddress>());
        final List<InetAddress> v4Addresses = Collections.synchronizedList(new ArrayList<InetAddress>());
        final String[] lastError = new String[1];
        queryBatch(hostname, Arrays.asList(DnsType.AAAA, DnsType.A),
                new BatchQueryCallback() {
                    @Override
                    public void onResult(DnsType type, List<DnsResourceRecord> records) {
                        List<InetAddress> target = (type == DnsType.AAAA) ? v6Addresses : v4Addresses;
                        for (DnsResourceRecord rr : records) {
                            target.add(rr.getAddress());
                        }
                    }

                    @Override
                    public void onTypeError(DnsType type, String error) {
                        lastError[0] = error;
                    }

                    @Override
                    public void onComplete() {
                        List<InetAddress> combined = new ArrayList<>();
                        combined.addAll(v6Addresses);
                        combined.addAll(v4Addresses);
                        if (combined.isEmpty()) {
                            callback.onError(lastError[0] != null ? lastError[0]
                                    : "No A or AAAA records found for " + finalHostname);
                        } else {
                            callback.onResolved(combined);
                        }
                    }
                });
    }

    private void deliverResolved(final List<InetAddress> result,
                                 final ResolveCallback callback) {
        if (Boolean.getBoolean("gumdrop.dns.debug")) {
            LOGGER.info(MessageFormat.format(L10N.getString("info.dns_deliver_resolved"),
                    result, selectorLoop != null));
        }
        if (selectorLoop != null) {
            selectorLoop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (Boolean.getBoolean("gumdrop.dns.debug")) {
                        LOGGER.info(L10N.getString("info.dns_invoking_callback"));
                    }
                    callback.onResolved(result);
                }
            });
        } else {
            callback.onResolved(result);
        }
    }

    private void query(String name, DnsType type,
                       final DnsQueryCallback callback, int cnameDepth) {
        query(name, type, Collections.<DnsType>emptyList(), callback, cnameDepth);
    }

    // additionalTypes: RFC 10029 extra RRTYPEs to request via MQTYPE-Query
    // alongside the primary (name, type) question, when non-empty and the
    // target server isn't known not to support it. Used by queryBatch and
    // (to keep the option attached across a client-side CNAME chase) by
    // deliverResponse's CNAME re-query.
    private void query(String name, DnsType type, List<DnsType> additionalTypes,
                       final DnsQueryCallback callback, int cnameDepth) {
        if (!opened) {
            callback.onError(L10N.getString("err.resolver_not_opened"));
            return;
        }
        if (transports.isEmpty()) {
            callback.onError(L10N.getString("err.no_dns_servers"));
            return;
        }
        final DnsQuestion question = new DnsQuestion(name, type, DnsClass.IN);
        DnsCache cache = sharedCache;
        if (cache != null) {
            if (cache.isNegativelyCached(name)) {
                deliverCachedNxdomain(name, type, callback);
                return;
            }
            List<DnsResourceRecord> cached = cache.lookup(question);
            if (cached != null) {
                deliverCachedResponse(name, type, cached, callback);
                return;
            }
        }
        int queryId = DnsQueryIdGenerator.allocate(pendingQueries.keySet());
        List<DnsQuestion> questions = new ArrayList<>();
        questions.add(question);
        // RFC 6891 section 6.1.1: include OPT pseudo-record to signal
        // EDNS0 support and advertise UDP payload size.
        // RFC 7873: include DNS cookie in the OPT record.
        // RFC 4035 section 3.2.1: set DO bit when DNSSEC is enabled.
        List<DnsResourceRecord> additionals = new ArrayList<>();
        InetSocketAddress targetServer = servers.isEmpty() ? null : servers.get(0);
        String serverAddr = targetServer == null ? "" :
                targetServer.getAddress().getHostAddress();
        byte[] cookieOption = dnsCookie.buildCookieOption(serverAddr);
        // RFC 10029: attach MQTYPE-Query alongside the cookie option in
        // the same OPT record's RDATA (RFC 6891 section 6.1.2: multiple
        // EDNS0 options are simply concatenated), unless this server was
        // already observed not to honor it.
        boolean attachMQType = !additionalTypes.isEmpty() && targetServer != null
                && !DNSMultiQTypeCache.isKnownUnsupported(targetServer);
        byte[] optionData;
        if (attachMQType) {
            byte[] mqtypeOption = DnsMultiQType.buildMQTypeQueryOption(additionalTypes);
            optionData = new byte[cookieOption.length + mqtypeOption.length];
            System.arraycopy(cookieOption, 0, optionData, 0, cookieOption.length);
            System.arraycopy(mqtypeOption, 0, optionData, cookieOption.length, mqtypeOption.length);
        } else {
            optionData = cookieOption;
        }
        int ednsFlags = dnssecEnabled
                ? DnsResourceRecord.EDNS_FLAG_DO : 0;
        additionals.add(DnsResourceRecord.opt(
                DnsMessage.DEFAULT_EDNS_UDP_SIZE, ednsFlags,
                optionData));
        // RFC 1035 section 4.1.1: set RD to request recursive resolution
        int flags = DnsMessage.FLAG_RD;
        DnsMessage queryMsg = new DnsMessage(
                queryId,
                flags,
                questions,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                additionals
        );
        ByteBuffer serialized = queryMsg.serialize();
        long expiry = System.currentTimeMillis() + timeoutMs;
        final PendingQuery pending =
                new PendingQuery(queryId, name, type, additionalTypes, callback, expiry,
                        0, serialized, cnameDepth);
        pendingQueries.put(queryId, pending);
        sendToServer(pending);
    }

    // -- Internal Methods --

    private void deliverCachedResponse(final String name, final DnsType type,
                                       final List<DnsResourceRecord> records,
                                       final DnsQueryCallback callback) {
        int syntheticId = DnsQueryIdGenerator.allocateSynthetic();
        List<DnsQuestion> questions = new ArrayList<>();
        questions.add(new DnsQuestion(name, type, DnsClass.IN));
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        final DnsMessage response = new DnsMessage(
                syntheticId, flags, questions, records,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()
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

    private void deliverCachedNxdomain(final String name, final DnsType type,
                                       final DnsQueryCallback callback) {
        int syntheticId = DnsQueryIdGenerator.allocateSynthetic();
        List<DnsQuestion> questions = new ArrayList<>();
        questions.add(new DnsQuestion(name, type, DnsClass.IN));
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA
                | DnsMessage.RCODE_NXDOMAIN;
        final DnsMessage response = new DnsMessage(
                syntheticId, flags, questions,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()
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

    private void cacheResponse(DnsMessage response) {
        DnsCache cache = sharedCache;
        if (cache == null) {
            return;
        }
        List<DnsQuestion> questions = response.getQuestions();
        if (questions.isEmpty()) {
            return;
        }
        DnsQuestion question = questions.get(0);
        if (response.getRcode() == DnsMessage.RCODE_NXDOMAIN) {
            List<DnsResourceRecord> authorities = DnsBailiwick.filterAuthoritiesInBailiwick(
                    question.getName(), response.getAuthorities());
            cache.cacheNegative(question.getName(), authorities);
        } else if (response.getRcode() == DnsMessage.RCODE_NOERROR) {
            List<DnsResourceRecord> answers = DnsBailiwick.filterAnswersInBailiwick(
                    question.getName(), response.getAnswers());
            if (!answers.isEmpty()) {
                cache.cache(question, answers);
            }
        }
    }

    private boolean shouldChaseCname(PendingQuery pending, DnsMessage response) {
        if (pending.type == DnsType.CNAME) {
            return false;
        }
        List<DnsResourceRecord> answers = response.getAnswers();
        if (answers.isEmpty()) {
            return false;
        }
        boolean hasCname = false;
        boolean hasRequestedType = false;
        for (DnsResourceRecord rr : answers) {
            if (rr.getType() == DnsType.CNAME) {
                hasCname = true;
            } else if (rr.getType() == pending.type) {
                hasRequestedType = true;
            }
        }
        return hasCname && !hasRequestedType;
    }

    private String extractCname(PendingQuery pending, DnsMessage response) {
        for (DnsResourceRecord rr : response.getAnswers()) {
            if (rr.getType() == DnsType.CNAME
                    && DnsBailiwick.namesEqual(rr.getName(), pending.name)) {
                return rr.getTargetName();
            }
        }
        return null;
    }

    private void sendToServer(final PendingQuery pending) {
        DnsClientTransport transport = transports.get(pending.serverIndex);
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

    // Descending preference (issue #408): encrypted QUIC-based transport
    // first, then TLS-based, then HTTPS-based, then plain TCP/UDP.
    private static final DNSTransportType[] TRANSPORT_PREFERENCE_ORDER = {
        DNSTransportType.DOQ, DNSTransportType.DOT, DNSTransportType.DOH, DNSTransportType.PLAIN
    };

    /**
     * Opens a transport to {@code server}. If an explicit transport was
     * configured via {@link #setTransport}, that override is used as
     * before, with no capability-based selection. Otherwise, transports
     * are tried in {@link #TRANSPORT_PREFERENCE_ORDER}, skipping any
     * {@link DNSServerCapabilityCache} already knows this server doesn't
     * support, and falling through to the next preference if one fails
     * to open synchronously. Plain UDP is always the last preference and
     * essentially never fails synchronously, so this always returns a
     * transport or propagates its open() failure.
     *
     * <p>A transport that opens successfully here but later fails
     * asynchronously (e.g. a QUIC/TLS handshake failure reported after
     * this method returns) is handled reactively by {@link
     * TransportCallback#onError}, which records the failure in the
     * capability cache for future opens rather than replacing the
     * transport mid-session -- see that method's comment for why.
     */
    private DnsClientTransport openBestTransport(InetSocketAddress server,
                                                  TransportCallback callback) throws IOException {
        if (transportPrototype != null) {
            transportPrototype.open(server.getAddress(), server.getPort(),
                    selectorLoop, callback);
            return transportPrototype;
        }
        DNSServerCapabilities caps = DNSServerCapabilityCache.get(server);
        IOException lastFailure = null;
        for (DNSTransportType type : TRANSPORT_PREFERENCE_ORDER) {
            if (!supports(caps, type) || DNSServerCapabilityCache.isKnownUnsupported(server, type)) {
                continue;
            }
            DnsClientTransport transport = newTransportInstance(type, caps);
            if (transport == null) {
                continue; // e.g. DoH with no provider on the classpath
            }
            try {
                transport.open(server.getAddress(), portFor(type, caps, server), selectorLoop, callback);
                callback.transportType = type;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("debug.transport_selected"), type, server));
                }
                return transport;
            } catch (IOException e) {
                lastFailure = e;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, MessageFormat.format(
                            L10N.getString("debug.transport_open_failed"), type, server), e);
                }
                if (type != DNSTransportType.PLAIN) {
                    DNSServerCapabilityCache.markUnsupported(server, type);
                }
            }
        }
        // PLAIN is always in TRANSPORT_PREFERENCE_ORDER and always
        // "supported" (see supports() below), so the loop only reaches
        // here if even plain UDP's open() failed.
        throw lastFailure != null ? lastFailure
                : new IOException(L10N.getString("err.no_dns_servers"));
    }

    private static boolean supports(DNSServerCapabilities caps, DNSTransportType type) {
        switch (type) {
            case DOQ: return caps.isDoqSupported();
            case DOT: return caps.isDotSupported();
            case DOH: return caps.isDohSupported();
            case PLAIN: default: return true;
        }
    }

    /**
     * Returns the port to connect on for {@code type}: the server's own
     * configured port for PLAIN, or the capability's known port for an
     * encrypted transport -- which is usually 0 (meaning "let the
     * transport use its own well-known default", e.g. 853 for DoT/DoQ,
     * 443 for DoH), since encrypted DNS almost never runs on the same
     * port as plaintext DNS. Forwarding {@code server.getPort()}
     * (typically 53) to an encrypted transport here would be wrong.
     */
    private static int portFor(DNSTransportType type, DNSServerCapabilities caps, InetSocketAddress server) {
        switch (type) {
            case DOQ: return caps.getDoqPort();
            case DOT: return caps.getDotPort();
            case DOH: return caps.getDohPort();
            case PLAIN: default: return server.getPort();
        }
    }

    // Package-private (not private) and non-final so tests can override
    // it to inject mock transports per type, the same way
    // createTcpRetryTransport() below is overridable for the TC-retry path.
    DnsClientTransport newTransportInstance(DNSTransportType type, DNSServerCapabilities caps) {
        switch (type) {
            case DOQ:
                return new DoQClientTransport();
            case DOT:
                return TcpDNSClientTransport.createDoT();
            case DOH:
                return createDohTransport(caps.getDohPath());
            case PLAIN:
            default:
                return new UdpDNSClientTransport();
        }
    }

    private static volatile DoHTransportFactory dohTransportFactory;
    private static volatile boolean dohTransportFactoryLoaded;

    /**
     * Creates a DoH transport via the {@link DoHTransportFactory} SPI
     * (implemented by the HTTP module, which core cannot depend on
     * directly -- see that interface's Javadoc), or null if no provider
     * is on the classpath.
     */
    private static DnsClientTransport createDohTransport(String path) {
        if (!dohTransportFactoryLoaded) {
            synchronized (DnsResolver.class) {
                if (!dohTransportFactoryLoaded) {
                    for (DoHTransportFactory factory : ServiceLoader.load(DoHTransportFactory.class)) {
                        dohTransportFactory = factory;
                        break;
                    }
                    dohTransportFactoryLoaded = true;
                }
            }
        }
        DoHTransportFactory factory = dohTransportFactory;
        return factory != null ? factory.createTransport(path) : null;
    }

    // RFC 1035 section 7.3: match response to query by Message ID
    private void handleResponse(DnsMessage response) {
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
    private void processResponseCookies(DnsMessage response,
                                         PendingQuery pending) {
        for (Object obj : response.getAdditionals()) {
            DnsResourceRecord rr = (DnsResourceRecord) obj;
            if (rr.getType() == DnsType.OPT) {
                byte[] cookieData = DnsCookie.findEdnsOption(
                        rr.getRData(), DnsCookie.EDNS_OPTION_COOKIE);
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

    private void deliverResponse(PendingQuery pending, DnsMessage response) {
        cacheResponse(response);
        if (shouldChaseCname(pending, response)) {
            String cname = extractCname(pending, response);
            if (cname != null && pending.cnameDepth < MAX_CNAME_DEPTH) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            "Following CNAME {0} -> {1} (depth {2})",
                            pending.name, cname, pending.cnameDepth + 1));
                }
                query(cname, pending.type, pending.additionalTypes, pending.callback,
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
            final DnsQueryCallback cb = pending.callback;
            final DnsMessage resp = response;
            chainValidator.validate(response,
                    new DnssecValidationCallback() {
                        @Override
                        public void onValidated(DnssecStatus status,
                                                DnsMessage validated) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine("DNSSEC status: " + status);
                            }
                            deliverToCallback(cb, validated, status);
                        }
                    });
        } else {
            deliverToCallback(pending.callback, response,
                    DnssecStatus.INDETERMINATE);
        }
    }

    /**
     * Delivers a response to a query callback, passing the DNSSEC
     * validation status through to callbacks that asked for it
     * (RFC 7672 section 3.1.3 needs this to reject an insecure DANE
     * lookup) without changing behavior for plain callbacks.
     */
    private static void deliverToCallback(DnsQueryCallback callback,
                                          DnsMessage response,
                                          DnssecStatus status) {
        if (callback instanceof DnssecAwareQueryCallback) {
            ((DnssecAwareQueryCallback) callback)
                    .onResponse(response, status);
        } else {
            callback.onResponse(response);
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

    DnsClientTransport createTcpRetryTransport() {
        return new TcpDNSClientTransport();
    }

    private void retryOverTcpAsync(final PendingQuery pending,
                                   InetSocketAddress server,
                                   final DnsMessage truncatedResponse) {
        try {
            final DnsClientTransport tcpTransport =
                    createTcpRetryTransport();
            TcpRetryHandler handler = new TcpRetryHandler(
                    pending, truncatedResponse, tcpTransport);
            tcpTransport.open(server.getAddress(), server.getPort(),
                    selectorLoop, handler);
            handler.timeoutHandle = tcpTransport.scheduleTimer(
                    TCP_TIMEOUT_MS, new Runnable() {
                @Override
                public void run() {
                    handler.onTimeout();
                }
            });
            pending.queryData.rewind();
            tcpTransport.send(pending.queryData);
        } catch (IOException e) {
            LOGGER.log(Level.FINE,
                    "TCP retry failed for " + pending.name, e);
            deliverResponse(pending, truncatedResponse);
        }
    }

    private class TcpRetryHandler implements DnsClientTransportHandler {

        private final PendingQuery pending;
        private final DnsMessage truncatedResponse;
        private final DnsClientTransport transport;
        TimerHandle timeoutHandle;
        private boolean completed;

        TcpRetryHandler(PendingQuery pending,
                        DnsMessage truncatedResponse,
                        DnsClientTransport transport) {
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
                DnsMessage tcpResponse = DnsMessage.parse(data);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            "TCP retry for {0} succeeded ({1} answers)",
                            pending.name,
                            tcpResponse.getAnswers().size()));
                }
                deliverResponse(pending, tcpResponse);
            } catch (DnsFormatException e) {
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

    // -- RFC 9462: Discovery of Designated Resolvers (DDR) --

    // RFC 9462 §5.1: the special-use name a client queries on the
    // plaintext resolver itself to learn its encrypted equivalents.
    private static final String DDR_QUERY_NAME = "_dns.resolver.arpa";
    // Bounded and independent of the ordinary query timeout: DDR is
    // best-effort and must never make resolution wait longer than
    // necessary on a resolver that doesn't answer this query at all.
    private static final long DDR_TIMEOUT_MS = 3000;

    /**
     * Test-only seam: overridable to inject a mock transport for DDR's
     * one-off discovery query, the same way {@link
     * #createTcpRetryTransport} is overridable for the truncation-retry
     * path.
     */
    DnsClientTransport createDdrTransport() {
        return new UdpDNSClientTransport();
    }

    /**
     * RFC 9462 §5.1: sends a SVCB query for {@link #DDR_QUERY_NAME} to
     * {@code server}, over a fresh plaintext connection to that same
     * server, and on a usable response records what was discovered and
     * upgrades that server's active transport. See {@link
     * #setDdrEnabled} for the full behavior and failure handling.
     */
    private void startDdrDiscovery(final int serverIndex, final InetSocketAddress server) {
        try {
            final DnsClientTransport transport = createDdrTransport();
            final DDRHandler handler = new DDRHandler(serverIndex, server, transport);
            transport.open(server.getAddress(), server.getPort(), selectorLoop, handler);
            handler.timeoutHandle = transport.scheduleTimer(DDR_TIMEOUT_MS, new Runnable() {
                @Override
                public void run() {
                    handler.onTimeout();
                }
            });
            List<DnsQuestion> questions = Collections.singletonList(
                    new DnsQuestion(DDR_QUERY_NAME, DnsType.SVCB, DnsClass.IN));
            DnsMessage queryMsg = new DnsMessage(
                    DnsQueryIdGenerator.allocateSynthetic(), DnsMessage.FLAG_RD, questions,
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList());
            transport.send(queryMsg.serialize());
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "DDR discovery failed to start for " + server, e);
            }
        }
    }

    /**
     * Parses a DDR SVCB response into discovered capabilities, or
     * returns null if nothing usable was found (a NODATA/NXDOMAIN
     * response, or SVCB records advertising none of the ALPN IDs below).
     *
     * <p>RFC 9461 §4: the ALPN identifiers "dot" and "doq" indicate
     * DoT/DoQ support; "h2"/"h3" (RFC 9113/9114) alongside a "dohpath"
     * SvcParam (RFC 9461 §5, defaulting to {@link
     * DNSServerCapabilityCache#DOH_PATH} if absent per that section)
     * indicate DoH support. AliasForm records (SvcPriority 0) carry no
     * SvcParams and are skipped.
     */
    private DNSServerCapabilities parseDdrResponse(DnsMessage response) {
        if (response.getRcode() != DnsMessage.RCODE_NOERROR) {
            return null;
        }
        boolean doq = false;
        int doqPort = 0;
        boolean dot = false;
        int dotPort = 0;
        String dohPath = null;
        int dohPort = 0;
        for (DnsResourceRecord rr : response.getAnswers()) {
            if (rr.getType() != DnsType.SVCB || rr.isSVCBAliasForm()) {
                continue;
            }
            List<String> alpns = rr.getSVCBAlpnProtocols();
            int recordPort = rr.getSVCBPort(); // -1 if absent
            if (!doq && alpns.contains("doq")) {
                doq = true;
                doqPort = recordPort > 0 ? recordPort : 0;
            }
            if (!dot && alpns.contains("dot")) {
                dot = true;
                dotPort = recordPort > 0 ? recordPort : 0;
            }
            if (dohPath == null && (alpns.contains("h2") || alpns.contains("h3"))) {
                String path = rr.getSVCBDohPath();
                dohPath = stripUriTemplateSuffix(path != null ? path : DNSServerCapabilityCache.DOH_PATH);
                dohPort = recordPort > 0 ? recordPort : 0;
            }
        }
        if (!doq && !dot && dohPath == null) {
            return null;
        }
        return DNSServerCapabilities.of(doq, doqPort, dot, dotPort, dohPath, dohPort);
    }

    // RFC 9461 §5: "dohpath" is a URI Template that must contain
    // "{?dns}" for GET-style expansion; gumdrop's DoH client always
    // POSTs (RFC 8484 §4.1) and has no use for the template, so only
    // the literal path prefix before it is kept.
    private static String stripUriTemplateSuffix(String uriTemplate) {
        int braceIndex = uriTemplate.indexOf('{');
        return braceIndex >= 0 ? uriTemplate.substring(0, braceIndex) : uriTemplate;
    }

    /**
     * Re-selects and opens the best transport for {@code
     * servers.get(serverIndex)} using freshly-discovered capability
     * data, replacing its entry in {@link #transports} and closing the
     * old one. A no-op if the resolver has since closed, or the index
     * is otherwise stale.
     *
     * <p>A query already in flight on the old transport at the moment
     * of the swap simply recovers via the existing per-server
     * retry-on-timeout in {@link #handleTimeout} -- the same accepted
     * trade-off as {@link TransportCallback#onError}'s reactive
     * negative-caching, for the same reason: DDR discovery is a rare,
     * early, one-time event, not a live mid-query renegotiation.
     */
    private void upgradeServerTransport(int serverIndex) {
        if (serverIndex < 0 || serverIndex >= servers.size() || serverIndex >= transports.size()) {
            return;
        }
        InetSocketAddress server = servers.get(serverIndex);
        DnsClientTransport oldTransport = transports.get(serverIndex);
        TransportCallback callback = new TransportCallback(serverIndex);
        try {
            DnsClientTransport newTransport = openBestTransport(server, callback);
            transports.set(serverIndex, newTransport);
            oldTransport.close();
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("debug.ddr_upgraded"), server, callback.transportType));
            }
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "DDR-triggered transport upgrade failed for " + server, e);
            }
        }
    }

    /**
     * Handles the one-off DDR discovery exchange for a single server,
     * independent of the ordinary {@link #pendingQueries}/{@link
     * #handleTimeout} machinery (which is designed to fail over
     * between servers, not to bound a single side query like this
     * one) -- the same reasoning behind {@link TcpRetryHandler}'s
     * separate, dedicated transport and handler for the truncation-retry
     * path.
     */
    private class DDRHandler implements DnsClientTransportHandler {

        private final int serverIndex;
        private final InetSocketAddress server;
        private final DnsClientTransport transport;
        TimerHandle timeoutHandle;
        private boolean completed;

        DDRHandler(int serverIndex, InetSocketAddress server, DnsClientTransport transport) {
            this.serverIndex = serverIndex;
            this.server = server;
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
                DnsMessage response = DnsMessage.parse(data);
                DNSServerCapabilities discovered = parseDdrResponse(response);
                if (discovered != null) {
                    DNSServerCapabilityCache.learn(server, discovered);
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(MessageFormat.format(
                                L10N.getString("debug.ddr_discovered"), server));
                    }
                    upgradeServerTransport(serverIndex);
                }
            } catch (DnsFormatException e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Malformed DDR response from " + server, e);
                }
            }
            transport.close();
        }

        @Override
        public void onError(Exception cause) {
            finish();
        }

        void onTimeout() {
            finish();
        }

        private void finish() {
            if (completed) {
                return;
            }
            completed = true;
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            transport.close();
        }
    }

    // -- Inner Classes --

    private static class PendingQuery {
        final int queryId;
        final String name;
        final DnsType type;
        final List<DnsType> additionalTypes;
        final DnsQueryCallback callback;
        final long expiry;
        final ByteBuffer queryData;
        final int cnameDepth;
        int serverIndex;
        TimerHandle timeoutHandle;

        PendingQuery(int queryId, String name, DnsType type,
                     List<DnsType> additionalTypes,
                     DnsQueryCallback callback, long expiry,
                     int serverIndex, ByteBuffer queryData,
                     int cnameDepth) {
            this.queryId = queryId;
            this.name = name;
            this.type = type;
            this.additionalTypes = additionalTypes;
            this.callback = callback;
            this.expiry = expiry;
            this.serverIndex = serverIndex;
            this.queryData = queryData;
            this.cnameDepth = cnameDepth;
        }
    }

    // -- Batch queries (RFC 10029) --

    /**
     * Resolves several RRTYPEs for one name, in as few wire exchanges as
     * possible.
     *
     * <p>RFC 10029 (DNS Multiple QTYPEs): the first type in {@code types}
     * is sent as the primary QTYPE; the rest are requested via an
     * {@code MQTYPE-Query} EDNS0 option on the same message. A
     * supporting server merges answers for whichever of those it can
     * into the single response; anything it doesn't cover -- including
     * everything, if the server doesn't support the mechanism at all --
     * is transparently resolved with additional standalone queries. This
     * is purely a transport-level optimization: every type in {@code
     * types} is guaranteed a call to {@link BatchQueryCallback#onResult}
     * or {@link BatchQueryCallback#onTypeError}, followed by exactly one
     * {@link BatchQueryCallback#onComplete()}, regardless of how many
     * packets it actually took.
     *
     * @param name the domain name to query
     * @param types the record types to resolve; the first is the
     *              primary QTYPE
     * @param callback the callback to receive results
     */
    public void queryBatch(String name, List<DnsType> types, BatchQueryCallback callback) {
        final Set<DnsType> requested = new LinkedHashSet<>(types);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("types must not be empty");
        }
        final BatchCollector collector = new BatchCollector(requested, callback);
        Iterator<DnsType> it = requested.iterator();
        final DnsType primaryType = it.next();
        final List<DnsType> additionalTypes = new ArrayList<>();
        while (it.hasNext()) {
            additionalTypes.add(it.next());
        }

        final InetSocketAddress targetServer = servers.isEmpty() ? null : servers.get(0);
        final List<DnsType> optionTypes = (!additionalTypes.isEmpty() && targetServer != null
                && !DNSMultiQTypeCache.isKnownUnsupported(targetServer))
                ? additionalTypes : Collections.<DnsType>emptyList();

        query(name, primaryType, optionTypes, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                collector.deliver(primaryType, recordsOfType(response, primaryType));
                if (additionalTypes.isEmpty()) {
                    return;
                }
                if (optionTypes.isEmpty()) {
                    // Didn't attempt the option this round (known
                    // unsupported, or nothing to attach it to) --
                    // resolve every additional type independently.
                    for (DnsType t : additionalTypes) {
                        queryStandaloneForBatch(name, t, collector);
                    }
                    return;
                }
                List<DnsType> covered = mqTypeResponseCoverage(response, targetServer);
                for (DnsType t : additionalTypes) {
                    if (covered.contains(t)) {
                        collector.deliver(t, recordsOfType(response, t));
                    } else {
                        queryStandaloneForBatch(name, t, collector);
                    }
                }
            }

            @Override
            public void onError(String error) {
                // The whole exchange failed (timeout/network error, not
                // a per-type DNS-level outcome) -- every requested type
                // fails together.
                collector.fail(primaryType, error);
                for (DnsType t : additionalTypes) {
                    collector.fail(t, error);
                }
            }
        }, 0);
    }

    private void queryStandaloneForBatch(String name, final DnsType type,
                                         final BatchCollector collector) {
        query(name, type, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                collector.deliver(type, recordsOfType(response, type));
            }

            @Override
            public void onError(String error) {
                collector.fail(type, error);
            }
        });
    }

    private static List<DnsResourceRecord> recordsOfType(DnsMessage response, DnsType type) {
        List<DnsResourceRecord> result = new ArrayList<>();
        for (DnsResourceRecord rr : response.getAnswers()) {
            if (rr.getType() == type) {
                result.add(rr);
            }
        }
        return result;
    }

    // RFC 10029: returns the additional types the server reported having
    // merged into `response` via MQTYPE-Response, marking `server` as
    // not supporting the mechanism (so future queries skip attaching the
    // option, per DNSMultiQTypeCache) when that option is absent or the
    // server erroneously echoed MQTYPE-Query back instead.
    private List<DnsType> mqTypeResponseCoverage(DnsMessage response, InetSocketAddress server) {
        for (Object obj : response.getAdditionals()) {
            DnsResourceRecord rr = (DnsResourceRecord) obj;
            if (rr.getType() != DnsType.OPT) {
                continue;
            }
            byte[] rdata = rr.getRData();
            byte[] responseData = DnsCookie.findEdnsOption(
                    rdata, DnsMultiQType.EDNS_OPTION_MQTYPE_RESPONSE);
            if (responseData == null) {
                if (server != null) {
                    DNSMultiQTypeCache.markUnsupported(server);
                }
                return Collections.emptyList();
            }
            try {
                return DnsMultiQType.parseMQTypeResponseOption(responseData);
            } catch (DnsFormatException e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Malformed MQTYPE-Response option", e);
                }
                return Collections.emptyList();
            }
        }
        // No OPT record at all in the response: server doesn't even echo
        // EDNS0, so it certainly doesn't support RFC 10029.
        if (server != null) {
            DNSMultiQTypeCache.markUnsupported(server);
        }
        return Collections.emptyList();
    }

    /**
     * Joins per-type results from {@link #queryBatch} into the batch's
     * {@link BatchQueryCallback#onComplete()} signal, once every
     * requested type has reported a result or an error.
     */
    private static final class BatchCollector {
        private final BatchQueryCallback callback;
        private final Set<DnsType> outstanding;

        BatchCollector(Set<DnsType> requested, BatchQueryCallback callback) {
            this.callback = callback;
            this.outstanding = Collections.synchronizedSet(new HashSet<>(requested));
        }

        void deliver(DnsType type, List<DnsResourceRecord> records) {
            callback.onResult(type, records);
            settle(type);
        }

        void fail(DnsType type, String error) {
            callback.onTypeError(type, error);
            settle(type);
        }

        private void settle(DnsType type) {
            boolean done;
            synchronized (outstanding) {
                // A type can settle twice if it's both the primary type
                // and (defensively) requested again as an "additional"
                // type -- queryBatch already dedupes via LinkedHashSet,
                // but remove() is idempotent either way.
                outstanding.remove(type);
                done = outstanding.isEmpty();
            }
            if (done) {
                callback.onComplete();
            }
        }
    }

    private class TransportCallback implements DnsClientTransportHandler {

        private final int serverIndex;

        /**
         * Set by {@link #openBestTransport} once its transport.open()
         * call succeeds; null while an explicit {@link #setTransport}
         * override is in effect, since there's nothing to fall back
         * from in that case.
         */
        volatile DNSTransportType transportType;

        TransportCallback(int serverIndex) {
            this.serverIndex = serverIndex;
        }

        @Override
        public void onReceive(ByteBuffer data) {
            try {
                DnsMessage response = DnsMessage.parse(data);
                handleResponse(response);
            } catch (DnsFormatException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("err.malformed_response"), e);
            }
        }

        @Override
        public void onError(Exception cause) {
            LOGGER.log(Level.WARNING,
                    "DNS resolver transport error", cause);
            // An asynchronous failure (e.g. a QUIC/TLS handshake that
            // fails after open() already returned successfully) means
            // this transport doesn't actually work for this server.
            // Record that in the process-wide capability cache so
            // future opens -- this resolver's next one, or another
            // resolver's for the same server -- prefer a transport more
            // likely to succeed, rather than replacing this session's
            // already-open transport mid-flight. In-flight queries on
            // this session still recover via the existing per-server
            // retry-on-timeout in handleTimeout().
            DNSTransportType type = transportType;
            if (type != null && type != DNSTransportType.PLAIN
                    && serverIndex >= 0 && serverIndex < servers.size()) {
                DNSServerCapabilityCache.markUnsupported(servers.get(serverIndex), type);
            }
        }
    }

}
