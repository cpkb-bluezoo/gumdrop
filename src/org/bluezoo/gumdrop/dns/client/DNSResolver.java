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
import org.bluezoo.gumdrop.dns.DNSFormatException;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSQuestion;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.GumdropNative;

/**
 * Asynchronous DNS resolver using non-blocking I/O.
 *
 * <p>DNSResolver provides non-blocking DNS lookups with callback-based
 * result delivery. It is designed for use in event-driven applications.
 *
 * <p>Queries are sent to configured upstream DNS servers and responses
 * are delivered via the {@link DNSQueryCallback} interface. Timeout
 * handling is integrated with the Gumdrop scheduler.
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
    private static final int MAX_CNAME_DEPTH = 8;

    private static final Map<SelectorLoop, DNSResolver> resolvers =
            new ConcurrentHashMap<>();
    private static volatile DNSCache sharedCache = new DNSCache();

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
     *
     * <p>If the first server times out, the query is automatically retried
     * on the next configured server (round-robin), up to one attempt per
     * server.
     *
     * @param name the domain name to query
     * @param type the record type to query
     * @param callback the callback to receive results
     */
    public void query(String name, DNSType type, DNSQueryCallback callback) {
        query(name, type, callback, 0);
    }

    // -- High-Level Resolution --

    /**
     * Resolves a hostname to IP addresses by issuing parallel A and AAAA
     * queries.
     *
     * <p>Resolution order:
     * <ol>
     * <li>Check the local hosts file ({@link HostsFile})</li>
     * <li>Check the shared {@link DNSCache}</li>
     * <li>Issue A and AAAA queries in parallel</li>
     * </ol>
     *
     * <p>The callback receives addresses with IPv6 first, then IPv4,
     * for Happy Eyeballs readiness. If one query type fails but the
     * other succeeds, the successful results are delivered. The error
     * callback is invoked only if both queries fail.
     *
     * @param hostname the hostname to resolve
     * @param callback the callback to receive results
     */
    public void resolve(String hostname, final ResolveCallback callback) {
        List<InetAddress> hostsResult = HostsFile.lookup(hostname);
        if (hostsResult != null && !hostsResult.isEmpty()) {
            if (selectorLoop != null) {
                final List<InetAddress> result = hostsResult;
                selectorLoop.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResolved(result);
                    }
                });
            } else {
                callback.onResolved(hostsResult);
            }
            return;
        }
        final DualQueryCollector collector =
                new DualQueryCollector(callback);
        queryAAAA(hostname, collector.v6Callback);
        queryA(hostname, collector.v4Callback);
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
        int flags = DNSMessage.FLAG_RD;
        DNSMessage queryMsg = new DNSMessage(
                queryId,
                flags,
                questions,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList()
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
            cache.cacheNegative(question.getName());
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
        if (pending.timeoutHandle != null) {
            pending.timeoutHandle.cancel();
        }
        if (response.isTruncated()) {
            // TODO: re-query over TCP/DoT for full response
            LOGGER.warning(MessageFormat.format(
                    "Truncated response for {0}, delivering partial result",
                    pending.name));
        }
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
        pending.callback.onResponse(response);
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
